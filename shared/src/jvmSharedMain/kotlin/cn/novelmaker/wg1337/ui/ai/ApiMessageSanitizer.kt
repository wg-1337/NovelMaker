package cn.novelmaker.wg1337.ui.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * API 请求消息净化器。
 *
 * ## 为什么需要它
 *
 * DeepSeek（OpenAI 兼容）协议对消息序列有硬性约束，违反任意一条都会返回 **400**：
 *
 * | 约束 | 说明 |
 * |---|---|
 * | `system` 只能在开头 | 中途出现 system 会被拒（本应用用它做状态提示） |
 * | `tool_calls` 必须配齐响应 | 每条 assistant.tool_calls 后面必须紧跟**数量与 ID 完全匹配**的 tool 消息 |
 * | tool 消息必须能配对 | 没有对应 tool_calls 的 tool 消息是孤立的 |
 * | 带 tools 时必须回传 reasoning_content | 否则 400（思考模式官方文档明确要求） |
 * | reasoning_content 不得中途丢失 | 同一轮工具循环内必须连续回传 |
 *
 * 对话历史是**用户可编辑**的（支持删除单条、重发、中断续传），
 * 因此历史里出现悬空 tool_calls、孤立 tool 结果、中途 system 都是正常现象。
 * 直接把这些历史拼进请求就会触发 400 —— 这就是「工具调用污染对话」的根因。
 *
 * ## 策略
 *
 * 不做局部修补，而是**重建**一条自洽的消息序列：
 * 1. 逐条扫描历史（顺序保持），维护「已声明的 tool_call_id」集合；
 * 2. assistant 的 tool_calls 先记账，只有后续 tool 消息配齐了才保留，否则整条丢弃；
 * 3. 配对不上的 tool 消息直接丢弃；
 * 4. 中途的 role=system 提示降级为 user 消息（保留信息、满足协议）；
 * 5. 相邻同角色消息合并，避免不合法重复；
 * 6. 按是否携带 tools 决定 reasoning_content 的回传策略。
 *
 * 净化结果保证满足上述全部约束。
 */
object ApiMessageSanitizer {

    /** 中途 state 提示降级为 user 时加的前缀，让模型知道这不是用户发言 */
    private const val STATUS_PREFIX = "【系统状态提示】"

    /** 中转后的内部消息（role 已被归一化） */
    private data class Out(
        val role: String,
        var content: String,
        var reasoning: String = "",
        var toolCalls: List<ToolCall>? = null,
        var toolCallId: String? = null,
        val images: List<ChatImage> = emptyList(),
        /** 固定前缀（键值固定、可安全缓存） */
        val isPrefix: Boolean = false,
        /**
         * 系统状态提示（非用户发言、非上下文注入）。
         * 这类消息要保留前缀让模型知道不是用户说的，且不与用户发言合并。
         */
        val isStatus: Boolean = false
    )

    /**
     * 构建发送给 API 的消息数组。
     *
     * @param prefixMessages 固定前缀（系统提示词、定稿章节、当前编辑内容），只能放在最前
     * @param history 对话历史（用户可编辑，可能不自洽）
     * @param projectId 用于解析图片路径；为空时跳过图片
     * @param includeReasoning 请求是否携带 tools。
     *        携带时必须回传 reasoning_content，否则 API 返回 400；
     *        未携带时 API 会忽略，回传反而浪费 token，故剔除。
     * @param cacheablePrefixCount 前多少条前缀消息打 cache_control。
     *        **只有键值固定的内容能标缓存**：系统提示词与定稿章节可以，
     *        而「当前编辑内容」每次编辑都会变，一旦标缓存就会击穿整个前缀缓存。
     * @param allowImages 是否允许携带图片。当前模型不支持图像理解时必须为 false，
     *        否则历史中已有的图片会让请求返回 400。
     * @return 净化后的 JSONArray
     */
    fun build(
        prefixMessages: List<AiChatMessage>,
        history: List<AiChatMessage>,
        projectId: String,
        includeReasoning: Boolean,
        cacheablePrefixCount: Int,
        allowImages: Boolean = true
    ): JSONArray {
        val out = mutableListOf<Out>()

        // ── 1. 固定前缀 ──
        prefixMessages.forEachIndexed { index, msg ->
            val text = msg.content
            val images = if (allowImages && msg.isUser()) msg.images else emptyList()
            if (text.isEmpty() && images.isEmpty()) return@forEachIndexed
            out.add(
                Out(
                    role = msg.role,
                    content = text,
                    images = images,
                    isPrefix = index < cacheablePrefixCount
                )
            )
        }

        // ── 2. 历史净化 ──
        val availableToolIds = mutableSetOf<String>()

        history.forEach { msg ->
            if (!msg.isApiVisible()) return@forEach

            when (msg.role) {
                "tool" -> {
                    val id = msg.toolCallId
                    if (id == null || id !in availableToolIds) {
                        // 孤立 tool 结果：没有配对的 tool_calls，丢弃
                        return@forEach
                    }
                    availableToolIds.remove(id)
                    out.add(
                        Out(
                            role = "tool",
                            content = msg.content,
                            toolCallId = id
                        )
                    )
                }

                "assistant" -> {
                    val pending = msg.toolCalls
                    if (pending.isNullOrEmpty()) {
                        // 纯文本回复；空回复（被中断的流）直接丢弃，否则会污染上下文
                        if (msg.content.isEmpty()) return@forEach
                        appendOrMergeAssistant(
                            out,
                            Out(
                                role = "assistant",
                                content = msg.content,
                                reasoning = if (includeReasoning) msg.reasoningContent else ""
                            )
                        )
                    } else {
                        // 先记账，等 tool 结果到齐后才决定是否保留
                        pending.forEach { availableToolIds.add(it.id) }
                        out.add(
                            Out(
                                role = "assistant",
                                content = msg.content,
                                reasoning = if (includeReasoning) msg.reasoningContent else "",
                                toolCalls = pending
                            )
                        )
                    }
                }

                "user" -> {
                    val images = if (allowImages && projectId.isNotEmpty()) msg.images else emptyList()
                    if (msg.content.isEmpty() && images.isEmpty()) return@forEach
                    // 上下文注入按普通 user 消息处理：它会与相邻的用户发言合并，
                    // 合并时「用户发言在前、注入在后」（见 appendOrMergeUser），
                    // 正是「用户说完话才注入」所要求的顺序。
                    appendOrMergeUser(
                        out,
                        Out(
                            role = "user",
                            content = msg.content,
                            images = images
                        )
                    )
                }

                "system" -> {
                    // 中途状态提示：协议不允许 system 出现在非开头位置，降级为 user
                    if (msg.content.isEmpty()) return@forEach
                    appendOrMergeUser(
                        out,
                        Out(
                            role = "user",
                            content = "$STATUS_PREFIX${msg.content}",
                            isStatus = true
                        )
                    )
                }
            }
        }

        // ── 3. 收尾：丢弃 tool_calls 未配齐的 assistant 消息 ──
        // 这些消息的 tool 结果被用户删除或生成被中断，保留会导致 400
        var i = 0
        while (i < out.size) {
            val msg = out[i]
            if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                val needed = msg.toolCalls!!.size
                var got = 0
                var j = i + 1
                while (j < out.size && out[j].role == "tool") {
                    got++
                    j++
                }
                if (got < needed) {
                    // 整条丢弃（包括其后已收下的 tool 结果，它们此时也无意义）
                    repeat(got) { out.removeAt(i + 1) }
                    out.removeAt(i)
                    continue
                }
            }
            i++
        }

        // ── 4. 丢弃无归属 tool（理论上已清空，双保险） ──
        out.removeAll { it.role == "tool" && it.toolCallId == null }

        // ── 5. 序列化 ──
        val json = JSONArray()
        out.forEach { msg ->
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("content", contentOf(msg, projectId))

            // 固定前缀标记缓存断点（仅系统提示词 / 定稿章节这类键值固定的内容）
            if (msg.isPrefix) {
                obj.put("cache_control", JSONObject().put("type", "ephemeral"))
            }
            if (msg.role == "assistant" && msg.reasoning.isNotEmpty()) {
                obj.put("reasoning_content", msg.reasoning)
            }
            if (msg.role == "tool" && msg.toolCallId != null) {
                obj.put("tool_call_id", msg.toolCallId)
            }
            if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                obj.put("tool_calls", AiChatMessage.toolCallsToJson(msg.toolCalls!!))
            }
            json.put(obj)
        }
        return json
    }

    /** content 字段：无图片用纯字符串，有图片用内容块数组 */
    private fun contentOf(msg: Out, projectId: String): Any {
        if (msg.images.isEmpty()) return msg.content
        val blocks = JSONArray()
        if (msg.content.isNotEmpty()) {
            blocks.put(JSONObject().apply {
                put("type", "text")
                put("text", msg.content)
            })
        }
        msg.images.forEach { img ->
            val dataUrl = ChatImageStore.toDataUrl(projectId, img) ?: return@forEach
            blocks.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", dataUrl)
                    put("detail", img.detail)
                })
            })
        }
        // 图片全部读取失败时退化为纯文本，避免发出空 content 触发 400
        if (blocks.length() == 0) return msg.content.ifEmpty { "[图片读取失败]" }
        return blocks
    }

    /**
     * 合并相邻 assistant 消息。
     * 同一轮里出现多条 assistant（工具循环 + 中断重发）时，重复的同类消息不合法。
     */
    private fun appendOrMergeAssistant(out: MutableList<Out>, msg: Out) {
        val last = out.lastOrNull()
        if (last != null && last.role == "assistant" &&
            !last.isPrefix && !msg.isPrefix &&
            last.toolCalls == null && msg.toolCalls == null
        ) {
            if (last.content == msg.content) return // 完全重复，丢弃
            last.content = listOf(last.content, msg.content)
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
            if (msg.reasoning.isNotEmpty()) {
                last.reasoning = listOf(last.reasoning, msg.reasoning)
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
            }
        } else {
            out.add(msg)
        }
    }

    /**
     * 合并相邻 user 消息。
     *
     * 顺序语义：后出现的追加在末尾。
     * 因此「用户发言 → 上下文注入」会合并成
     * `用户发言\n上下文注入`，即**用户发言在前、注入在后** ——
     * 这正是「用户说完话才注入」时要求的顺序，
     * 也保证注入不会抢在用户指令之前被模型读到。
     *
     * 状态提示（如「已切换到 Agent 模式」）单独成条，不与用户发言混排。
     */
    private fun appendOrMergeUser(out: MutableList<Out>, msg: Out) {
        val last = out.lastOrNull()
        if (last != null && last.role == "user" &&
            last.isStatus == msg.isStatus &&
            last.images.isEmpty() && msg.images.isEmpty()
        ) {
            // 后到的追加到末尾
            last.content = listOf(last.content, msg.content)
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        } else {
            out.add(msg)
        }
    }
}
