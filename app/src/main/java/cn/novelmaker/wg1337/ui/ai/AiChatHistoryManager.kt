package cn.novelmaker.wg1337.ui.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiChatHistoryManager(private val context: Context) {

    companion object {
        private const val TAG = "AiChatHistory"
        /** 旧版单文件聊天记录名：chat_{projectId}.json */
        private val LEGACY_FILE_REGEX = Regex("^chat_(.+)\\.json$")
        /** 新版按标签页聊天记录名：chat_{projectId}_t{tabId}.json */
        private val TAB_FILE_REGEX = Regex("^chat_(.+)_t(\\d+)\\.json$")
    }

    private fun getChatDir(): File {
        val dir = File(context.filesDir, "ai_chats")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getChatFile(projectId: String, tabId: Int): File =
        File(getChatDir(), "chat_${projectId}_t$tabId.json")

    /**
     * 迁移旧版单文件聊天记录 chat_{projectId}.json → chat_{projectId}_t1.json（第一个标签页）
     */
    private fun migrateLegacy(projectId: String) {
        try {
            val dir = getChatDir()
            val legacy = File(dir, "chat_$projectId.json")
            if (legacy.exists() && !File(dir, "chat_${projectId}_t1.json").exists()) {
                legacy.renameTo(File(dir, "chat_${projectId}_t1.json"))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "迁移旧聊天记录失败: projectId=$projectId", e)
        }
    }

    fun loadChatHistory(projectId: String, tabId: Int = 1): List<AiChatMessage> {
        migrateLegacy(projectId)
        val file = getChatFile(projectId, tabId)
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
            android.util.Log.e(TAG, "加载聊天记录失败: projectId=$projectId tabId=$tabId", e)
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

    fun saveChatHistory(projectId: String, tabId: Int, messages: List<AiChatMessage>) {
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
            val file = getChatFile(projectId, tabId)
            val tmpFile = File(file.parentFile, "${file.name}.tmp")
            tmpFile.writeText(array.toString(2))
            tmpFile.renameTo(file)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "保存聊天记录失败: projectId=$projectId tabId=$tabId", e)
        }
    }

    /** 删除指定标签页的聊天记录 */
    fun deleteChatHistory(projectId: String, tabId: Int) {
        val file = getChatFile(projectId, tabId)
        if (file.exists()) file.delete()
    }

    /** 删除项目全部标签页的聊天记录（设置入口用，兼容旧版单文件） */
    fun deleteChatHistory(projectId: String) {
        val dir = getChatDir()
        dir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.startsWith("chat_${projectId}_t") && name.endsWith(".json")) {
                f.delete()
            }
        }
        File(dir, "chat_$projectId.json").delete() // 旧版单文件兜底
    }

    /**
     * 确保标签页记录文件存在：每个对话（标签页）都有独立记录文件。
     * 新建/切换到的空对话也创建文件（内容为 []），保证对话列表可被持久化发现、可被聊天记录管理展示与备份。
     */
    fun ensureTabFile(projectId: String, tabId: Int) {
        try {
            val file = getChatFile(projectId, tabId)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText("[]")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "创建标签页记录失败: projectId=$projectId tabId=$tabId", e)
        }
    }

    /**
     * 发现项目已使用的标签页 id 列表（升序）。
     * 触发旧数据迁移；没有任何记录时返回 [1]（保证至少一个标签页）。
     */
    fun getUsedTabIds(projectId: String): List<Int> {
        migrateLegacy(projectId)
        val dir = getChatDir()
        val ids = dir.listFiles()
            ?.filter { it.name.startsWith("chat_${projectId}_t") && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                file.name.removePrefix("chat_${projectId}_t").removeSuffix(".json").toIntOrNull()
            }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
        return ids.ifEmpty { listOf(1) }
    }

    fun getProjectsWithChatHistory(): List<String> {
        val dir = getChatDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("chat_") && it.name.endsWith(".json") }
            ?.mapNotNull { f ->
                val name = f.name
                // 新格式 chat_{projectId}_t{tabId}.json → projectId；旧格式 chat_{projectId}.json → 原样
                TAB_FILE_REGEX.find(name)?.groupValues?.get(1)
                    ?: LEGACY_FILE_REGEX.find(name)?.groupValues?.get(1)
            }
            ?.distinct()
            ?: emptyList()
    }

    fun getChatHistorySize(projectId: String, tabId: Int): Long = getChatFile(projectId, tabId).length()
    fun getChatHistoryCount(projectId: String, tabId: Int): Int = loadChatHistory(projectId, tabId).size
}
