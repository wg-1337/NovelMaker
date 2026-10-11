package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek Chat Completions 客户端。
 *
 * 请求参数严格对齐官方文档，集中说明见 [DeepSeekApi]。
 * 消息序列的自洽性由 [ApiMessageSanitizer] 保证，本类只负责传输。
 */
class AiApiClient(
    private val apiKey: String,
    private val baseUrl: String = DeepSeekApi.DEFAULT_BASE_URL,
    private val model: String = DeepSeekApi.MODEL_FLASH,
    /** 思考强度：none / low / high / max（none 即关闭思考） */
    private val reasoningEffort: String = DeepSeekApi.DEFAULT_EFFORT,
    private val jsonModeEnabled: Boolean = false,
    /** 非思考模式默认 8K、思考模式默认 64K；这里显式给足上限 */
    private val maxTokens: Int = DeepSeekApi.MAX_OUTPUT_TOKENS
) {

    companion object {
        private const val TAG = "AiApiClient"
        private const val STREAM_TIMEOUT_MS = 120_000
        private const val SYNC_TIMEOUT_MS = 120_000
    }

    private val chatEndpoint: String
        get() = "${baseUrl.trimEnd('/')}/v1/chat/completions"

    private val thinkingEnabled: Boolean
        get() = DeepSeekApi.isThinkingEnabled(reasoningEffort)

    data class ApiResult(
        val success: Boolean,
        val content: String = "",
        val reasoningContent: String = "",
        val toolCalls: List<ToolCall>? = null,
        val errorMessage: String? = null,
        /** 服务端返回的 token 用量（可能为 null） */
        val usage: Usage? = null,
        /** finish_reason，用于诊断截断等问题 */
        val finishReason: String = ""
    )

    /** 服务端 usage 统计 */
    data class Usage(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val cacheHitTokens: Long,
        val cacheMissTokens: Long,
        val reasoningTokens: Long
    )

    // ───────────────────── 流式请求 ─────────────────────

    /**
     * 流式请求。
     *
     * @param messagesJson 已由 [ApiMessageSanitizer] 构建好的 messages 数组
     * @param tools 工具定义；非空时服务端会要求历史轮次回传 reasoning_content
     * @param onUsage 收到最终 usage 时回调（可能是最后一个 chunk）
     */
    fun sendChatRequestStream(
        messagesJson: JSONArray,
        tools: JSONArray? = null,
        onToken: (content: String, reasoning: String) -> Unit,
        onToolCalls: (List<ToolCall>) -> Unit,
        onComplete: (success: Boolean, error: String?) -> Unit,
        onUsage: ((Usage) -> Unit)? = null,
        timeoutMs: Int = STREAM_TIMEOUT_MS
    ) {
        Thread {
            try {
                val url = URL(chatEndpoint)
                AppLogger.d(
                    TAG,
                    "流式请求 -> $chatEndpoint | 模型: $model | 思考: $reasoningEffort | " +
                        "消息数: ${messagesJson.length()} | 工具: ${tools?.length() ?: 0}"
                )

                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "text/event-stream")
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    doOutput = true
                }

                val requestBody = buildRequestBody(messagesJson, tools, stream = true)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).apply {
                    write(requestBody.toString()); flush(); close()
                }

                val responseCode = connection.responseCode
                AppLogger.d(TAG, "流式响应码: $responseCode")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val err = connection.errorStream
                        ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
                        ?: "未知错误"
                    AppLogger.e(TAG, "流式请求失败: HTTP $responseCode")
                    AppLogger.e(TAG, "错误响应体: $err")
                    connection.disconnect()
                    onComplete(false, describeHttpError(responseCode, err))
                    return@Thread
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                val tcInfos = mutableMapOf<Int, ToolCallAccumulator>()
                var toolCallsHandled = false
                var apiError: String? = null
                var finishReason = ""

                reader.use { r ->
                    var line: String?
                    streamLoop@ while (r.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (!currentLine.startsWith("data:")) continue
                        val data = currentLine.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break@streamLoop

                        val json = try {
                            JSONObject(data)
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "流式 chunk 解析失败，跳过: ${e.message}")
                            continue
                        }

                        // 流中错误：记录后立即结束读取（注意要用带标签的 break 跳出循环）
                        val errObj = json.optJSONObject("error")
                        if (errObj != null) {
                            val errMsg = errObj.optString("message", "API 错误")
                            AppLogger.e(
                                TAG,
                                "流式 API 返回错误: type=${errObj.optString("type", "")}, message=$errMsg"
                            )
                            apiError = errMsg
                            break@streamLoop
                        }

                        // usage 附加在最后一个内容块上
                        json.optJSONObject("usage")?.let { u -> onUsage?.invoke(parseUsage(u)) }

                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val choice = choices.getJSONObject(0)
                        val fr = choice.optString("finish_reason", "")
                        if (fr.isNotEmpty() && fr != "null") finishReason = fr

                        val delta = choice.optJSONObject("delta")
                        if (delta != null) {
                            // 思考内容
                            if (!delta.isNull("reasoning_content")) {
                                val reasoning = delta.optString("reasoning_content", "")
                                if (reasoning.isNotEmpty()) onToken("", reasoning)
                            }
                            // 正文
                            if (!delta.isNull("content")) {
                                val content = delta.optString("content", "")
                                if (content.isNotEmpty()) onToken(content, "")
                            }
                            // 工具调用增量
                            delta.optJSONArray("tool_calls")?.let { toolCallsJson ->
                                accumulateToolCalls(toolCallsJson, tcInfos)
                            }
                        }

                        // finish_reason == tool_calls 时构建完整工具调用
                        if (fr == "tool_calls" && tcInfos.isNotEmpty()) {
                            val calls = tcInfos.entries
                                .sortedBy { it.key }
                                .mapNotNull { (_, info) -> info.build() }
                            tcInfos.clear()
                            if (calls.isNotEmpty()) {
                                toolCallsHandled = true
                                onToolCalls(calls)
                            }
                        }
                    }
                }
                connection.disconnect()

                if (!toolCallsHandled) {
                    if (apiError != null) {
                        onComplete(false, apiError)
                    } else if (finishReason == "insufficient_system_resource") {
                        onComplete(false, "服务端推理资源不足，生成被中断，请重试")
                    } else if (finishReason == "content_filter") {
                        onComplete(false, "输出内容触发了服务端内容过滤")
                    } else {
                        onComplete(true, null)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "流式请求网络异常", e)
                onComplete(false, "网络异常: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    // ───────────────────── 非流式请求 ─────────────────────

    fun sendChatRequest(
        messagesJson: JSONArray,
        tools: JSONArray? = null,
        timeoutMs: Int = SYNC_TIMEOUT_MS
    ): ApiResult {
        AppLogger.d(TAG, "非流式请求 -> $chatEndpoint | 模型: $model | 消息数: ${messagesJson.length()}")
        return try {
            val url = URL(chatEndpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
            }
            val requestBody = buildRequestBody(messagesJson, tools, stream = false)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).apply {
                write(requestBody.toString()); flush(); close()
            }

            val respCode = connection.responseCode
            AppLogger.d(TAG, "非流式响应码: $respCode")

            if (respCode != HttpURLConnection.HTTP_OK) {
                val err = connection.errorStream
                    ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
                    ?: "未知错误"
                AppLogger.e(TAG, "非流式请求失败: HTTP $respCode")
                AppLogger.e(TAG, "错误响应体: $err")
                connection.disconnect()
                return ApiResult(false, errorMessage = describeHttpError(respCode, err))
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).readText()
            connection.disconnect()
            val json = JSONObject(response)

            json.optJSONObject("error")?.let { errObj ->
                val errMsg = errObj.optString("message", "API 错误")
                AppLogger.e(TAG, "非流式 API 业务错误: type=${errObj.optString("type", "")}, message=$errMsg")
                return ApiResult(false, errorMessage = "API 错误: $errMsg")
            }

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                AppLogger.e(TAG, "非流式返回格式异常: choices 为空")
                return ApiResult(false, errorMessage = "API 返回格式异常")
            }
            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message")
            val usage = json.optJSONObject("usage")?.let { parseUsage(it) }

            ApiResult(
                success = true,
                content = message?.optString("content", "").orEmpty(),
                reasoningContent = message?.optString("reasoning_content", "").orEmpty(),
                toolCalls = parseToolCalls(message?.optJSONArray("tool_calls")),
                usage = usage,
                finishReason = choice.optString("finish_reason", "")
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "非流式请求网络异常", e)
            ApiResult(false, errorMessage = "网络异常: ${e.message}")
        }
    }

    // ───────────────────── 请求体构建 ─────────────────────

    private fun buildRequestBody(messagesJson: JSONArray, tools: JSONArray?, stream: Boolean): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("stream", stream)
        body.put("messages", messagesJson)

        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            // 思考模式下不支持 tool_choice=required 或指定工具（返回 400），保持 auto
            body.put("tool_choice", "auto")
        }

        // 思考模式参数
        body.put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
        if (thinkingEnabled) {
            body.put("reasoning_effort", DeepSeekApi.normalizeEffort(reasoningEffort))
        }

        if (jsonModeEnabled) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }

        body.put("max_tokens", maxTokens)

        // 注意：temperature 在思考模式下不生效、presence_penalty / frequency_penalty 已废弃，
        // 因此不再发送，避免误导与无谓的请求体膨胀。

        if (stream) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        return body
    }

    // ───────────────────── 解析工具 ─────────────────────

    /** 流式工具调用增量累加器：首个 chunk 带 id/name，后续只带 arguments 片段 */
    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()

        fun build(): ToolCall? {
            if (name.isEmpty()) return null
            return ToolCall(
                id = id.ifEmpty { java.util.UUID.randomUUID().toString() },
                type = "function",
                function = ToolFunction(name = name, arguments = args.toString())
            )
        }
    }

    private fun accumulateToolCalls(toolCallsJson: JSONArray, acc: MutableMap<Int, ToolCallAccumulator>) {
        for (i in 0 until toolCallsJson.length()) {
            val tc = toolCallsJson.optJSONObject(i) ?: continue
            val idx = tc.optInt("index", -1)
            if (idx < 0) continue
            val info = acc.getOrPut(idx) { ToolCallAccumulator() }

            tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { info.id = it }

            tc.optJSONObject("function")?.let { func ->
                if (!func.isNull("name")) {
                    func.optString("name", "").takeIf { it.isNotEmpty() }?.let { info.name = it }
                }
                if (!func.isNull("arguments")) {
                    info.args.append(func.optString("arguments", ""))
                }
            }
        }
    }

    private fun parseToolCalls(arr: JSONArray?): List<ToolCall>? {
        if (arr == null || arr.length() == 0) return null
        val calls = (0 until arr.length()).mapNotNull { i ->
            val tc = arr.optJSONObject(i) ?: return@mapNotNull null
            val func = tc.optJSONObject("function") ?: return@mapNotNull null
            val name = if (func.isNull("name")) "" else func.optString("name", "")
            if (name.isEmpty()) return@mapNotNull null
            ToolCall(
                id = tc.optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() },
                type = tc.optString("type", "function"),
                function = ToolFunction(
                    name = name,
                    arguments = if (func.isNull("arguments")) "" else func.optString("arguments", "")
                )
            )
        }
        return calls.ifEmpty { null }
    }

    private fun parseUsage(u: JSONObject): Usage {
        val details = u.optJSONObject("prompt_tokens_details")
        val completionDetails = u.optJSONObject("completion_tokens_details")
        val hit = u.optLong("prompt_cache_hit_tokens", details?.optLong("cached_tokens", 0) ?: 0)
        val miss = u.optLong("prompt_cache_miss_tokens", 0)
        return Usage(
            promptTokens = u.optLong("prompt_tokens", 0),
            completionTokens = u.optLong("completion_tokens", 0),
            totalTokens = u.optLong("total_tokens", 0),
            cacheHitTokens = hit,
            cacheMissTokens = if (miss > 0) miss else (u.optLong("prompt_tokens", 0) - hit).coerceAtLeast(0),
            reasoningTokens = completionDetails?.optLong("reasoning_tokens", 0) ?: 0
        )
    }

    /**
     * 把服务端错误体整理成用户能看懂的话。
     * DeepSeek 的错误体形如 {"error":{"message":"...","type":"..."}}，直接抛原文太长。
     */
    private fun describeHttpError(code: Int, rawBody: String): String {
        val detail = try {
            JSONObject(rawBody).optJSONObject("error")?.optString("message", "")
                ?.takeIf { it.isNotEmpty() }
                ?: rawBody.take(500)
        } catch (_: Exception) {
            rawBody.take(500)
        }
        val hint = when (code) {
            400 -> "（请求格式或上下文不合法）"
            401 -> "（API Key 无效）"
            402 -> "（余额不足）"
            422 -> "（参数不合法）"
            429 -> "（请求过于频繁或已达并发上限）"
            500, 502, 503 -> "（服务端暂时不可用，请稍后重试）"
            else -> ""
        }
        return "API 请求失败 ($code)$hint: $detail"
    }
}
