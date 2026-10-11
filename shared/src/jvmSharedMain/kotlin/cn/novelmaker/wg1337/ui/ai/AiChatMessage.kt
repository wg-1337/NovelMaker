package cn.novelmaker.wg1337.ui.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息中携带的一张图片（图片本体以文件形式保存在 ai_images/ 下）。
 *
 * 发送给 API 时统一以 base64 data URL 内联（本地文件最简单可靠的方式），
 * 详见 Vision 指南：https://api-docs.deepseek.com/zh-cn/guides/vision
 */
data class ChatImage(
    /** 图片文件名（位于该项目的图片目录内） */
    val fileName: String,
    /** MIME 类型：image/jpeg / image/png / image/gif / image/webp */
    val mimeType: String,
    /** 文件字节数 */
    val sizeBytes: Long = 0L,
    /**
     * 细节级别：low / high / original / auto。
     * low 会缩放到 512×512，更快更省 token；其余保留原图。
     */
    val detail: String = DeepSeekApi.DETAIL_AUTO
) {
    /** 界面展示用的短标签（文件名为内容哈希，缩写显示即可） */
    val displayName: String get() = fileName
}

/**
 * AI 聊天消息数据模型
 *
 * role 说明：
 * - user / assistant / tool：与 API 协议一致，会发送给模型
 * - system：**仅用于界面状态提示**（如「已切换到 Agent 模式」）。
 *   API 协议要求 system 消息只能出现在开头，因此发送前会被转换为 user 消息，
 *   详见 [ApiMessageSanitizer]。
 * - choice：纯 UI 角色（内联选项），永不发送给 API
 */
data class AiChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" / "assistant" / "system" / "tool" / "choice"
    val content: String = "",
    val reasoningContent: String = "", // DeepSeek 思考过程
    val toolCalls: List<ToolCall>? = null, // API 返回的工具调用
    val toolCallId: String? = null, // 工具调用结果关联 ID
    /** 该消息携带的图片（仅 user 消息允许携带，API 规范所限） */
    val images: List<ChatImage> = emptyList(),
    /**
     * 该消息是「上下文注入」（自动插入的预设上下文），不是用户本人发言。
     *
     * 用标记而非文本前缀来识别：注入内容**不加任何前缀**，
     * 所以不能再靠字符串匹配判断。界面据此折叠展示，净化器据此与用户发言合并。
     */
    val isInjection: Boolean = false,
    /**
     * 仅本地可见：不会发送给模型。
     * 用于纯 UI 提示，避免污染上下文。
     */
    val localOnly: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isUser() = role == "user"
    fun isAssistant() = role == "assistant"
    fun isSystem() = role == "system"
    fun isTool() = role == "tool"
    fun isChoice() = role == "choice"
    fun hasReasoning() = reasoningContent.isNotEmpty()
    fun hasToolCalls() = !toolCalls.isNullOrEmpty()
    fun hasImages() = images.isNotEmpty()

    /** 该消息是否应进入 API 请求（choice 与 localOnly 除外） */
    fun isApiVisible(): Boolean = !isChoice() && !localOnly

    /** 是否有可展示/可发送的正文（图片也算内容） */
    fun hasPayload(): Boolean = content.isNotEmpty() || hasImages()

    companion object {
        /** 序列化工具调用列表为 API 结构 */
        fun toolCallsToJson(calls: List<ToolCall>): JSONArray {
            val arr = JSONArray()
            calls.forEach { tc ->
                arr.put(JSONObject().apply {
                    put("id", tc.id)
                    put("type", tc.type)
                    put("function", JSONObject().apply {
                        put("name", tc.function.name)
                        put("arguments", tc.function.arguments)
                    })
                })
            }
            return arr
        }
    }
}

/**
 * 工具调用定义（API 返回的 function calling 指令）
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val arguments: String // JSON 格式的参数字符串
)
