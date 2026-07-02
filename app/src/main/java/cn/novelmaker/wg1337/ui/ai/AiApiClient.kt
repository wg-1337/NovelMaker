package cn.novelmaker.wg1337.ui.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AiApiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-chat",
    private val reasoningEffort: String = "high",
    private val thinkingEnabled: Boolean = true,
    private val jsonModeEnabled: Boolean = false,
    private val maxTokens: Int = 128000,

    private val maxContext: Int = 1048576
) {

    companion object {
        private const val TAG = "AiApiClient"
    }

    private val chatEndpoint: String get() = "$baseUrl/v1/chat/completions"

    data class ApiResult(
        val success: Boolean,
        val content: String = "",
        val reasoningContent: String = "",
        val toolCalls: List<ToolCall>? = null,
        val errorMessage: String? = null
    )

    // ───────────────────── 流式请求 ─────────────────────

    fun sendChatRequestStream(
        messages: List<AiChatMessage>,
        systemPrompt: String? = null,
        tools: JSONArray? = null,
        prebuiltMessagesJson: JSONArray? = null,
        onToken: (content: String, reasoning: String) -> Unit,
        onToolCalls: (List<ToolCall>) -> Unit,
        onComplete: (Boolean, String?) -> Unit,
        timeoutMs: Int = 60000
    ) {
        Thread {
            try {
                val url = URL(chatEndpoint)
                Log.d(TAG, "流式请求 -> $chatEndpoint | 模型: $model | 消息数: ${messages.size} | 工具: ${tools?.length() ?: 0}")

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
                val requestBody = if (prebuiltMessagesJson != null) {
                    JSONObject().apply {
                        put("model", model)
                        put("messages", prebuiltMessagesJson)
                        if (tools != null && tools.length() > 0) put("tools", tools)
                        put("stream", true)
                        put("max_tokens", maxTokens)
                        put("temperature", 0.7)
                    }
                } else {
                    buildRequestBody(messages, systemPrompt, tools, stream = true)
                }
                OutputStreamWriter(connection.outputStream).apply {
                    write(requestBody.toString()); flush(); close()
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "流式响应码: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    var line: String?
                    data class TcInfo(var id: String = "", var name: String = "", var args: String = "")
                    val tcInfos = mutableMapOf<Int, TcInfo>()
                    var toolCallsHandled = false // 标记是否已触发工具调用
                    var apiError: String? = null // API返回的错误信息

                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (currentLine.startsWith("data: ")) {
                            val data = currentLine.removePrefix("data: ")
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)

                                // 检查API错误（DeepSeek可能在流式中间返回错误）
                                val errObj = json.optJSONObject("error")
                                if (errObj != null) {
                                    val errMsg = errObj.optString("message", "API错误")
                                    val errType = errObj.optString("type", "")
                                    Log.e(TAG, "流式API返回错误: type=$errType, message=$errMsg")
                                    Log.e(TAG, "完整错误响应: $data")
                                    apiError = errMsg
                                    break
                                }

                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val choice = choices.getJSONObject(0)
                                    val delta = choice.optJSONObject("delta")
                                    val finishReason = choice.optString("finish_reason", "")

                                    if (delta != null) {
                                        val reasoning = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                                        if (reasoning.isNotEmpty()) onToken("", reasoning)
                                        val content = if (delta.isNull("content")) "" else delta.optString("content", "")
                                        if (content.isNotEmpty()) onToken(content, "")

                                        // 工具调用流式处理
                                        val toolCallsJson = delta.optJSONArray("tool_calls")
                                        if (toolCallsJson != null) {
                                            for (i in 0 until toolCallsJson.length()) {
                                                val tc = toolCallsJson.getJSONObject(i)
                                                val idx = tc.optInt("index", -1)
                                                if (idx < 0) continue
                                                val tcId = tc.optString("id", "")
                                                val func = tc.optJSONObject("function")

                                                val info = tcInfos.getOrPut(idx) { TcInfo() }
                                                if (tcId.isNotEmpty()) info.id = tcId
                                                if (func != null) {
                                                    if (!func.isNull("name")) {
                                                        val n = func.optString("name", "")
                                                        if (n.isNotEmpty()) info.name = n
                                                    }
                                                    if (!func.isNull("arguments")) {
                                                        info.args += func.optString("arguments", "")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // finish_reason出现时构建工具调用
                                    if (finishReason == "tool_calls" && tcInfos.isNotEmpty()) {
                                        val calls = tcInfos.entries.sortedBy { it.key }.mapNotNull { (_, info) ->
                                            if (info.name.isEmpty()) null
                                            else ToolCall(
                                                id = info.id.ifEmpty { java.util.UUID.randomUUID().toString() },
                                                type = "function",
                                                function = ToolFunction(name = info.name, arguments = info.args)
                                            )
                                        }
                                        if (calls.isNotEmpty()) {
                                            toolCallsHandled = true
                                            onToolCalls(calls)
                                            tcInfos.clear()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "流式数据解析异常，跳过该chunk: ${e.message}")
                                Log.w(TAG, "异常chunk内容: $data")
                            }
                        }
                    }
                    reader.close()
                    connection.disconnect()

                    // 只有当没有触发工具调用且没有API错误时才调用onComplete(success)
                    if (!toolCallsHandled) {
                        if (apiError != null) {
                            onComplete(false, apiError)
                        } else {
                            onComplete(true, null)
                        }
                    }
                } else {
                    val err = connection.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: "未知错误"
                    Log.e(TAG, "流式请求失败: HTTP $responseCode")
                    Log.e(TAG, "错误响应体: $err")
                    connection.disconnect()
                    onComplete(false, "API请求失败 ($responseCode): $err")
                }
            } catch (e: Exception) {
                Log.e(TAG, "流式请求网络异常", e)
                onComplete(false, "网络异常: ${e.message}")
            }
        }.apply { isDaemon = true }.start()

    }

    // ───────────────────── 非流式请求 ─────────────────────

    fun sendChatRequest(
        messages: List<AiChatMessage>,
        systemPrompt: String? = null,
        tools: JSONArray? = null,
        timeoutMs: Int = 60000
    ): ApiResult {
        Log.d(TAG, "非流式请求 -> $chatEndpoint | 模型: $model | 消息数: ${messages.size} | 工具: ${tools?.length() ?: 0}")
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
            val requestBody = buildRequestBody(messages, systemPrompt, tools, stream = false)
            OutputStreamWriter(connection.outputStream).apply {
                write(requestBody.toString()); flush(); close()
            }

            val respCode = connection.responseCode
            Log.d(TAG, "非流式响应码: $respCode")

            if (respCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
                connection.disconnect()
                val json = JSONObject(response)

                // 检查API业务错误
                val errObj = json.optJSONObject("error")
                if (errObj != null) {
                    val errMsg = errObj.optString("message", "API错误")
                    val errType = errObj.optString("type", "")
                    Log.e(TAG, "非流式API业务错误: type=$errType, message=$errMsg")
                    Log.e(TAG, "完整错误响应: $response")
                    return ApiResult(false, errorMessage = "API错误: $errMsg")
                }

                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    val toolCallsJson = message?.optJSONArray("tool_calls")
                    val toolCalls = if (toolCallsJson != null && toolCallsJson.length() > 0) {
                        (0 until toolCallsJson.length()).mapNotNull { i ->
                            val tc = toolCallsJson.getJSONObject(i)
                            val func = tc.optJSONObject("function")
                            if (func != null) {
                                val name = if (func.isNull("name")) "" else func.optString("name", "")
                                if (name.isEmpty()) null else {
                                    ToolCall(
                                        id = tc.optString("id", ""),
                                        type = tc.optString("type", "function"),
                                        function = ToolFunction(
                                            name = name,
                                            arguments = if (func.isNull("arguments")) "" else func.optString("arguments", "")
                                        )
                                    )
                                }
                            } else null
                        }
                    } else null

                    ApiResult(true,
                        content = message?.optString("content", "") ?: "",
                        reasoningContent = message?.optString("reasoning_content", "") ?: "",
                        toolCalls = toolCalls
                    )
                } else {
                    Log.e(TAG, "非流式API返回格式异常: choices为空或格式错误")
                    Log.e(TAG, "完整响应: $response")
                    ApiResult(false, errorMessage = "API返回格式异常")
                }
            } else {
                val err = connection.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: "未知错误"
                Log.e(TAG, "非流式请求失败: HTTP $respCode")
                Log.e(TAG, "错误响应体: $err")
                connection.disconnect()
                ApiResult(false, errorMessage = "API请求失败 ($respCode): $err")
            }
        } catch (e: Exception) {
            Log.e(TAG, "非流式请求网络异常", e)
            ApiResult(false, errorMessage = "网络异常: ${e.message}")
        }
    }

    private fun buildRequestBody(
        messages: List<AiChatMessage>,
        systemPrompt: String?,
        tools: JSONArray?,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("stream", stream)

        val arr = JSONArray()
        if (systemPrompt != null) {
            val sysMsg = JSONObject()
            sysMsg.put("role", "system")
            sysMsg.put("content", systemPrompt)
            // 标记系统提示词为可缓存块，提升DeepSeek缓存命中率
            sysMsg.put("cache_control", JSONObject().apply { put("type", "ephemeral") })
            arr.put(sysMsg)
        }
        // 按顺序构建消息列表并标记最后一个user前面的assistant消息为缓存断点
        val msgList = messages.toList()
        for (i in msgList.indices) {
            val jsonObj = msgList[i].toJsonObject()
            arr.put(jsonObj)
        }
        body.put("messages", arr)

        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
        }

        if (thinkingEnabled) {
            body.put("reasoning_effort", reasoningEffort)
        }

        if (jsonModeEnabled) {
            val respFormat = JSONObject()
            respFormat.put("type", "json_object")
            body.put("response_format", respFormat)
        }

        body.put("max_tokens", maxTokens)
        body.put("temperature", 0.7)

        return body
    }
}
