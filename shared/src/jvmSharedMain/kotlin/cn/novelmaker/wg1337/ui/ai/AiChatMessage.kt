package cn.novelmaker.wg1337.ui.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI聊天消息数据模型
 */
data class AiChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" / "assistant" / "system" / "tool"
    val content: String = "",
    val reasoningContent: String = "", // DeepSeek思考过程
    val toolCalls: List<ToolCall>? = null, // API返回的工具调用
    val toolCallId: String? = null, // 工具调用结果关联ID
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isUser() = role == "user"
    fun isAssistant() = role == "assistant"
    fun isSystem() = role == "system"
    fun isTool() = role == "tool"
    fun hasReasoning() = reasoningContent.isNotEmpty()
    fun hasToolCalls() = !toolCalls.isNullOrEmpty()

    /** 转换为API请求用的JSONObject */
    fun toJsonObject(cacheControl: Boolean = false): JSONObject {
        val obj = JSONObject()
        obj.put("role", role)
        obj.put("content", content)

        if (cacheControl) {
            obj.put("cache_control", JSONObject().put("type", "ephemeral"))
        }
        if (isAssistant() && reasoningContent.isNotEmpty()) {
            obj.put("reasoning_content", reasoningContent)
        }
        if (isTool() && toolCallId != null) {
            obj.put("tool_call_id", toolCallId)
        }
        if (isAssistant() && hasToolCalls()) {
            val calls = JSONArray()
            for (tc in toolCalls!!) {
                val func = JSONObject()
                func.put("name", tc.function.name)
                func.put("arguments", tc.function.arguments)
                val call = JSONObject()
                call.put("id", tc.id)
                call.put("type", tc.type)
                call.put("function", func)
                calls.put(call)
            }
            obj.put("tool_calls", calls)
        }
        return obj
    }
}

/**
 * 工具调用定义（API返回的 function calling 指令）
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val arguments: String // JSON格式的参数字符串
)
