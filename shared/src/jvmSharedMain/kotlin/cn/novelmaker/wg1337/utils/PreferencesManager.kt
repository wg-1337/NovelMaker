package cn.novelmaker.wg1337.utils

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform

class PreferencesManager {
    private val prefs = Platform.keyValueStore()
    private val secureSecretStore = Platform.secureSecretStore()

    companion object {
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AI_API_KEY_ENCRYPTED = "ai_api_key_encrypted"
        private const val KEY_AI_API_KEY_LEGACY = "ai_api_key"

        const val THEME_DYNAMIC = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.putBoolean(KEY_ONBOARDING_COMPLETED, value)

    var isTutorialCompleted: Boolean
        get() = prefs.getBoolean("tutorial_completed", false)
        set(value) = prefs.putBoolean("tutorial_completed", value)

    var isEditorTutorialCompleted: Boolean
        get() = prefs.getBoolean("editor_tutorial_completed", false)
        set(value) = prefs.putBoolean("editor_tutorial_completed", value)

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_DYNAMIC)
        set(value) = prefs.putInt(KEY_THEME_MODE, value)

    // 编辑器设置
    var editorFontSize: Float
        get() = prefs.getFloat("editor_font_size", 16f)
        set(value) = prefs.putFloat("editor_font_size", value)

    var editorLineSpacing: Float
        get() = prefs.getFloat("editor_line_spacing", 5f)
        set(value) = prefs.putFloat("editor_line_spacing", value)

    var isAutoSaveEnabled: Boolean
        get() = prefs.getBoolean("editor_auto_save", true)
        set(value) = prefs.putBoolean("editor_auto_save", value)

    // AI 设置
    var aiBaseUrl: String?
        get() = prefs.getString("ai_base_url", "https://api.deepseek.com")
        set(value) = prefs.putString("ai_base_url", value)

    var aiModel: String?
        get() = prefs.getString("ai_model", "deepseek-chat")
        set(value) = prefs.putString("ai_model", value)

    var aiApiKey: String?
        get() = readApiKey()
        set(value) {
            prefs.remove(KEY_AI_API_KEY_LEGACY)
            if (value.isNullOrEmpty()) {
                prefs.remove(KEY_AI_API_KEY_ENCRYPTED)
            } else {
                val encrypted = try {
                    secureSecretStore.encrypt(value)
                } catch (e: Exception) {
                    AppLogger.e("PreferencesManager", "加密 API Key 失败", e)
                    null
                }
                if (encrypted != null) {
                    prefs.putString(KEY_AI_API_KEY_ENCRYPTED, encrypted)
                }
            }
        }

    /**
     * 读取 API Key：
     * 1. 优先读取 Keystore/DPAPI 加密后的密文并解密；
     * 2. 若只有旧版明文，自动迁移到密文存储并删除明文。
     */
    private fun readApiKey(): String? {
        prefs.getString(KEY_AI_API_KEY_ENCRYPTED, null)?.let { stored ->
            return secureSecretStore.decrypt(stored)
        }
        val legacy = prefs.getString(KEY_AI_API_KEY_LEGACY, null) ?: return null
        val encrypted = try {
            secureSecretStore.encrypt(legacy)
        } catch (e: Exception) {
            AppLogger.e("PreferencesManager", "迁移旧 API Key 加密失败，继续使用旧值", e)
            null
        }
        if (encrypted != null) {
            prefs.putString(KEY_AI_API_KEY_ENCRYPTED, encrypted)
            prefs.remove(KEY_AI_API_KEY_LEGACY)
        }
        return legacy
    }

    var aiStreamEnabled: Boolean
        get() = prefs.getBoolean("ai_stream_enabled", true)
        set(value) = prefs.putBoolean("ai_stream_enabled", value)

    // 思考强度: "off"(不思考) / "high" / "max"
    var aiReasoningEffort: String?
        get() = prefs.getString("ai_reasoning_effort", "high")
        set(value) = prefs.putString("ai_reasoning_effort", value)

    // AI 打开网页：true=提取正文纯文本（默认，省 Token）；false=返回原始 HTML
    var aiFetchPlainText: Boolean
        get() = prefs.getBoolean("ai_fetch_plain_text", true)
        set(value) = prefs.putBoolean("ai_fetch_plain_text", value)

    // AI面板模式: 0=底部上拉栏(默认), 1=右侧侧拉栏
    var aiPanelMode: Int
        get() = prefs.getInt("ai_panel_mode", 0)
        set(value) = prefs.putInt("ai_panel_mode", value)

    // AI最大工具调用轮次: -1=无限制, >0=具体轮次(默认5)
    var aiMaxToolRounds: Int
        get() = prefs.getInt("ai_max_tool_rounds", 5)
        set(value) = prefs.putInt("ai_max_tool_rounds", value)

    // 项目级 AI 模式持久化（PLAN=0, AGENT=1）
    fun saveAiMode(projectId: String, mode: Int) {
        prefs.putInt("ai_mode_$projectId", mode)
    }

    fun getAiMode(projectId: String): Int {
        return prefs.getInt("ai_mode_$projectId", 0) // 默认 PLAN
    }

    // 项目级 AI 标签页：记录上次激活的标签页 id（默认 1）
    fun saveActiveAiTab(projectId: String, tabId: Int) {
        prefs.putInt("ai_active_tab_$projectId", tabId)
    }

    fun getActiveAiTab(projectId: String): Int {
        return prefs.getInt("ai_active_tab_$projectId", 1)
    }

    // 编辑器：最大定稿章节数（0 = 无限制，默认 50）
    var maxFinalizedChapters: Int
        get() = prefs.getInt("max_finalized_chapters", 50)
        set(value) = prefs.putInt("max_finalized_chapters", value)

    // 批量淘汰章节数（超限时一次移除最早N章，默认 20）
    var bulkEvictChapters: Int
        get() = prefs.getInt("bulk_evict_chapters", 20)
        set(value) = prefs.putInt("bulk_evict_chapters", value)
}
