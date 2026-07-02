package cn.novelmaker.wg1337.ui.ai

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Token 用量统计管理器
 * 按项目统计：系统提示词 / AI对话 / 工具调用的 Token 用量
 * 存储：ai_chats/token_usage_{projectId}.json
 */
class TokenUsageManager(private val context: Context) {

    companion object {
        const val MAX_CONTEXT_TOKENS = 1_048_576L  // DeepSeek 1M
        private const val AVG_TOKENS_PER_CHAR = 0.4  // 中英文混合估算
    }

    data class TokenStats(
        val systemPromptTokens: Long = 0,
        val chatTokens: Long = 0,
        val toolCallTokens: Long = 0
    ) {
        val totalTokens get() = systemPromptTokens + chatTokens + toolCallTokens
        val maxContext get() = MAX_CONTEXT_TOKENS
        val usagePercent get() = if (MAX_CONTEXT_TOKENS > 0) totalTokens.toDouble() / MAX_CONTEXT_TOKENS * 100 else 0.0
    }

    private fun getFile(projectId: String): File {
        val dir = File(context.filesDir, "ai_chats")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "token_usage_${projectId}.json")
    }

    fun recordSystemPrompt(projectId: String, charCount: Int) {
        val tokens = estimate(charCount)
        update(projectId) { it.copy(systemPromptTokens = it.systemPromptTokens + tokens) }
    }

    fun recordChat(projectId: String, inCharCount: Int, outCharCount: Int) {
        val tokens = estimate(inCharCount + outCharCount)
        update(projectId) { it.copy(chatTokens = it.chatTokens + tokens) }
    }

    fun recordToolCall(projectId: String, charCount: Int) {
        val tokens = estimate(charCount)
        update(projectId) { it.copy(toolCallTokens = it.toolCallTokens + tokens) }
    }

    fun getStats(projectId: String): TokenStats {
        val file = getFile(projectId)
        if (!file.exists()) return TokenStats()
        return try {
            val json = JSONObject(file.readText())
            TokenStats(
                systemPromptTokens = json.optLong("systemPromptTokens", 0),
                chatTokens = json.optLong("chatTokens", 0),
                toolCallTokens = json.optLong("toolCallTokens", 0)
            )
        } catch (_: Exception) { TokenStats() }
    }

    fun getProjectsWithStats(): List<String> {
        val dir = File(context.filesDir, "ai_chats")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("token_usage_") && it.name.endsWith(".json") }
            ?.map { it.name.removePrefix("token_usage_").removeSuffix(".json") }
            ?: emptyList()
    }

    private fun update(projectId: String, transform: (TokenStats) -> TokenStats) {
        val updated = transform(getStats(projectId))
        val json = JSONObject().apply {
            put("systemPromptTokens", updated.systemPromptTokens)
            put("chatTokens", updated.chatTokens)
            put("toolCallTokens", updated.toolCallTokens)
        }
        val file = getFile(projectId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun estimate(charCount: Int): Long =
        (charCount * AVG_TOKENS_PER_CHAR).toLong().coerceAtLeast(1)
}
