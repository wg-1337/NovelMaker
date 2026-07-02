package cn.novelmaker.wg1337.ui.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiChatHistoryManager(private val context: Context) {

    private fun getChatFile(projectId: String): File {
        val dir = File(context.filesDir, "ai_chats")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "chat_${projectId}.json")
    }

    fun loadChatHistory(projectId: String): List<AiChatMessage> {
        val file = getChatFile(projectId)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val array = JSONArray(json)
            val messages = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val toolCallsJson = obj.optJSONArray("tool_calls")
                val toolCalls = if (toolCallsJson != null && toolCallsJson.length() > 0) {
                    (0 until toolCallsJson.length()).map { j ->
                        val tc = toolCallsJson.getJSONObject(j)
                        val func = tc.optJSONObject("function")
                        ToolCall(
                            id = tc.optString("id", ""),
                            type = tc.optString("type", "function"),
                            function = ToolFunction(
                                name = func?.optString("name", "") ?: "",
                                arguments = func?.optString("arguments", "") ?: ""
                            )
                        )
                    }
                } else null

                AiChatMessage(
                    id = obj.getString("id"),
                    role = obj.getString("role"),
                    content = obj.getString("content"),
                    reasoningContent = obj.optString("reasoning_content", ""),
                    toolCalls = toolCalls,
                    toolCallId = obj.optString("tool_call_id", ""),
                    timestamp = obj.getLong("timestamp")
                )
            }
            // 清理不完整的 tool_calls：移除没有后续 tool 响应的 tool_calls
            cleanupIncompleteToolCalls(messages)
        } catch (e: Exception) {
            android.util.Log.e("AiChatHistory", "加载聊天记录失败: projectId=$projectId", e)
            val backupFile = File(file.parentFile, "${file.name}.bak")
            try { file.copyTo(backupFile, overwrite = true) } catch (_: Exception) {}
            emptyList()
        }
    }

    /**
     * 移除悬空的 tool_calls（有 assistant tool_calls 但后续没有足够的 tool 响应）
     */
    private fun cleanupIncompleteToolCalls(messages: List<AiChatMessage>): List<AiChatMessage> {
        val result = mutableListOf<AiChatMessage>()
        for (msg in messages) {
            if (msg.hasToolCalls()) {
                val requiredIds = msg.toolCalls!!.map { it.id }.toSet()
                // 统计后续 tool 消息中匹配的 tool_call_id
                var matchedCount = 0
                val checkFrom = messages.indexOf(msg) + 1
                for (j in checkFrom until messages.size) {
                    val next = messages[j]
                    if (next.isTool() && next.toolCallId in requiredIds) matchedCount++
                    if (next.isAssistant() || next.isUser()) break // 遇到新对话轮次停止检查
                }
                if (matchedCount < requiredIds.size) {
                    // 不完整的 tool_calls，移除 tool_calls 字段但保留消息
                    result.add(msg.copy(toolCalls = null))
                    continue
                }
            }
            result.add(msg)
        }
        return result
    }

    fun saveChatHistory(projectId: String, messages: List<AiChatMessage>) {
        try {
            // 保存前清理不完整的 tool_calls 链
            val cleaned = cleanupIncompleteToolCalls(messages)
            val array = JSONArray()
            cleaned.forEach { msg ->
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role)
                    put("content", msg.content)
                    put("reasoning_content", msg.reasoningContent)
                    put("timestamp", msg.timestamp)

                    if (msg.hasToolCalls()) {
                        val calls = JSONArray()
                        msg.toolCalls!!.forEach { tc ->
                            calls.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", tc.type)
                                put("function", JSONObject().apply {
                                    put("name", tc.function.name)
                                    put("arguments", tc.function.arguments)
                                })
                            })
                        }
                        put("tool_calls", calls)
                    }

                    if (msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
                }
                array.put(obj)
            }
            // 先写临时文件再重命名，防止写入中断导致文件损坏
            val file = getChatFile(projectId)
            val tmpFile = File(file.parentFile, "${file.name}.tmp")
            tmpFile.writeText(array.toString(2))
            tmpFile.renameTo(file)
        } catch (e: Exception) {
            android.util.Log.e("AiChatHistory", "保存聊天记录失败: projectId=$projectId", e)
        }
    }

    fun deleteChatHistory(projectId: String) {
        val file = getChatFile(projectId)
        if (file.exists()) file.delete()
    }

    fun getProjectsWithChatHistory(): List<String> {
        val dir = File(context.filesDir, "ai_chats")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("chat_") && it.name.endsWith(".json") }
            ?.map { it.name.removePrefix("chat_").removeSuffix(".json") }
            ?: emptyList()
    }

    fun getChatHistorySize(projectId: String): Long = getChatFile(projectId).length()
    fun getChatHistoryCount(projectId: String): Int = loadChatHistory(projectId).size
}
