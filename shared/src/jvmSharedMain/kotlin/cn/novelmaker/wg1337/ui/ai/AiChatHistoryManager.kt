package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AiChatHistoryManager {

    companion object {
        private const val TAG = "AiChatHistory"
        /** 旧版单文件聊天记录名：chat_{projectId}.json */
        private val LEGACY_FILE_REGEX = Regex("^chat_(.+)\\.json$")
        /** 新版按标签页聊天记录名：chat_{projectId}_t{tabId}.json */
        private val TAB_FILE_REGEX = Regex("^chat_(.+)_t(\\d+)\\.json$")
    }

    private fun getChatDir(): File {
        val dir = File(Platform.internalDataDir(), "ai_chats")
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
            AppLogger.e(TAG, "迁移旧聊天记录失败: projectId=$projectId", e)
        }
    }

    fun loadChatHistory(projectId: String, tabId: Int = 1): List<AiChatMessage> {
        migrateLegacy(projectId)
        val file = getChatFile(projectId, tabId)
        // Windows 旧版本可能只写入了 .tmp 而改名失败；这里优先读取更新的 .tmp 恢复数据
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        val source = if (tmpFile.exists() && (!file.exists() || tmpFile.lastModified() > file.lastModified())) tmpFile else file
        if (!source.exists()) return emptyList()
        return try {
            val json = source.readText()
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
                    images = parseImages(obj.optJSONArray("images")),
                    isInjection = obj.optBoolean("is_injection", false),
                    localOnly = obj.optBoolean("local_only", false),
                    timestamp = obj.getLong("timestamp")
                )
            }
            // 注意：这里不再「就地清洗」不完整的 tool_calls。
            // 历史上曾在加载/保存时把悬空 tool_calls 从消息上剥离，结果是：
            // 用户还没来得及重试或续传，历史就已经被破坏性改写。
            // 现在改为在**构建请求时**由 ApiMessageSanitizer 统一净化，
            // 磁盘上的历史保持原样，UI 也能如实展示发生过什么。
            messages
        } catch (e: Exception) {
            AppLogger.e(TAG, "加载聊天记录失败: projectId=$projectId tabId=$tabId", e)
            val backupFile = File(file.parentFile, "${file.name}.bak")
            try { file.copyTo(backupFile, overwrite = true) } catch (_: Exception) {}
            emptyList()
        }
    }

    /**
     * 解析消息携带的图片列表。
     * 只保留格式合法的条目；图片文件丢失时仍保留引用（界面显示占位，发送时自动跳过）。
     */
    private fun parseImages(arr: JSONArray?): List<ChatImage> {
        if (arr == null || arr.length() == 0) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val file = o.optString("file", "")
            if (file.isEmpty()) return@mapNotNull null
            ChatImage(
                fileName = file,
                mimeType = o.optString("mime", "image/png"),
                sizeBytes = o.optLong("size", 0L),
                detail = o.optString("detail", DeepSeekApi.DETAIL_AUTO)
            )
        }
    }

    fun saveChatHistory(projectId: String, tabId: Int, messages: List<AiChatMessage>) {
        try {
            val array = JSONArray()
            messages.forEach { msg ->
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

                    // 图片只存引用（文件本体在 ai_images/ 下，按内容哈希命名）
                    if (msg.hasImages()) {
                        put("images", JSONArray().apply {
                            msg.images.forEach { img ->
                                put(JSONObject().apply {
                                    put("file", img.fileName)
                                    put("mime", img.mimeType)
                                    put("size", img.sizeBytes)
                                    put("detail", img.detail)
                                })
                            }
                        })
                    }
                    if (msg.localOnly) put("local_only", true)
                    if (msg.isInjection) put("is_injection", true)
                }
                array.put(obj)
            }
            // 先写临时文件再重命名，防止写入中断导致文件损坏
            val file = getChatFile(projectId, tabId)
            val tmpFile = File(file.parentFile, "${file.name}.tmp")
            tmpFile.writeText(array.toString(2))
            // Windows 上 renameTo 遇到已存在目标会失败，这里用覆盖移动
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存聊天记录失败: projectId=$projectId tabId=$tabId", e)
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
            AppLogger.e(TAG, "创建标签页记录失败: projectId=$projectId tabId=$tabId", e)
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
