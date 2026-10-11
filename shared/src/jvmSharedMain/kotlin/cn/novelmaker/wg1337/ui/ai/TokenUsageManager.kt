package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.Platform
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Token 用量统计管理器
 * 按项目统计：系统提示词 / AI对话 / 工具调用的 Token 用量
 * 存储：ai_chats/token_usage_{projectId}.json
 *
 * 优先使用服务端返回的真实 usage（含缓存命中统计）；
 * 服务端未返回时回落到字符数估算。
 */
class TokenUsageManager {

    companion object {
        /** 上下文长度 1M（deepseek-flash / deepseek-v4-pro 相同） */
        const val MAX_CONTEXT_TOKENS = DeepSeekApi.CONTEXT_WINDOW_TOKENS
        private const val AVG_TOKENS_PER_CHAR = 0.4  // 中英文混合估算
    }

    data class TokenStats(
        val systemPromptTokens: Long = 0,
        val chatTokens: Long = 0,
        val toolCallTokens: Long = 0,
        /** 累计命中的缓存 token（来自服务端 usage） */
        val cacheHitTokens: Long = 0,
        /** 累计未命中的缓存 token（来自服务端 usage） */
        val cacheMissTokens: Long = 0
    ) {
        val totalTokens get() = systemPromptTokens + chatTokens + toolCallTokens
        val maxContext get() = MAX_CONTEXT_TOKENS
        val usagePercent get() = if (MAX_CONTEXT_TOKENS > 0) totalTokens.toDouble() / MAX_CONTEXT_TOKENS * 100 else 0.0

        /** 缓存命中率（0..1）；无数据时为 0 */
        val cacheHitRate: Double
            get() {
                val total = cacheHitTokens + cacheMissTokens
                return if (total > 0) cacheHitTokens.toDouble() / total else 0.0
            }
    }

    private fun getFile(projectId: String): File {
        val dir = File(Platform.internalDataDir(), "ai_chats")
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

    /**
     * 使用服务端返回的真实 usage 记账。
     *
     * 注意：prompt_tokens 已包含系统提示词、历史对话与工具结果，
     * 因此这里把它整体计入「AI 对话」，并将缓存命中/未命中单独累计用于展示命中率，
     * 不做重复分摊（避免总量虚高）。
     */
    fun recordFromUsage(projectId: String, usage: AiApiClient.Usage) {
        if (usage.totalTokens <= 0 && usage.promptTokens <= 0) return
        update(projectId) {
            it.copy(
                chatTokens = it.chatTokens + usage.promptTokens + usage.completionTokens,
                cacheHitTokens = it.cacheHitTokens + usage.cacheHitTokens,
                cacheMissTokens = it.cacheMissTokens + usage.cacheMissTokens
            )
        }
    }

    fun getStats(projectId: String): TokenStats {
        val file = getFile(projectId)
        if (!file.exists()) return TokenStats()
        return try {
            val json = JSONObject(file.readText())
            TokenStats(
                systemPromptTokens = json.optLong("systemPromptTokens", 0),
                chatTokens = json.optLong("chatTokens", 0),
                toolCallTokens = json.optLong("toolCallTokens", 0),
                cacheHitTokens = json.optLong("cacheHitTokens", 0),
                cacheMissTokens = json.optLong("cacheMissTokens", 0)
            )
        } catch (_: Exception) { TokenStats() }
    }

    fun getProjectsWithStats(): List<String> {
        val dir = File(Platform.internalDataDir(), "ai_chats")
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
            put("cacheHitTokens", updated.cacheHitTokens)
            put("cacheMissTokens", updated.cacheMissTokens)
        }
        val file = getFile(projectId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun estimate(charCount: Int): Long =
        (charCount * AVG_TOKENS_PER_CHAR).toLong().coerceAtLeast(1)
}
