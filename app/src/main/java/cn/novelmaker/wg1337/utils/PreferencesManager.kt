package cn.novelmaker.wg1337.utils

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "novelmaker_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_THEME_MODE = "theme_mode"

        const val THEME_DYNAMIC = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_DYNAMIC)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    // 编辑器设置
    var editorFontSize: Float
        get() = prefs.getFloat("editor_font_size", 16f)
        set(value) = prefs.edit().putFloat("editor_font_size", value).apply()

    var editorLineSpacing: Float
        get() = prefs.getFloat("editor_line_spacing", 5f)
        set(value) = prefs.edit().putFloat("editor_line_spacing", value).apply()

    var isAutoSaveEnabled: Boolean
        get() = prefs.getBoolean("editor_auto_save", true)
        set(value) = prefs.edit().putBoolean("editor_auto_save", value).apply()

    // AI 设置
    var aiBaseUrl: String?
        get() = prefs.getString("ai_base_url", "https://api.deepseek.com")
        set(value) = prefs.edit().putString("ai_base_url", value).apply()

    var aiModel: String?
        get() = prefs.getString("ai_model", "deepseek-chat")
        set(value) = prefs.edit().putString("ai_model", value).apply()

    var aiApiKey: String?
        get() = prefs.getString("ai_api_key", null)
        set(value) = prefs.edit().putString("ai_api_key", value).apply()

    var aiStreamEnabled: Boolean
        get() = prefs.getBoolean("ai_stream_enabled", true)
        set(value) = prefs.edit().putBoolean("ai_stream_enabled", value).apply()

    // 思考强度: "high" 或 "max"
    var aiReasoningEffort: String?
        get() = prefs.getString("ai_reasoning_effort", "high")
        set(value) = prefs.edit().putString("ai_reasoning_effort", value).apply()

    // AI面板模式: 0=底部上拉栏(默认), 1=右侧侧拉栏
    var aiPanelMode: Int
        get() = prefs.getInt("ai_panel_mode", 0)
        set(value) = prefs.edit().putInt("ai_panel_mode", value).apply()

    // AI最大工具调用轮次: -1=无限制, >0=具体轮次(默认5)
    var aiMaxToolRounds: Int
        get() = prefs.getInt("ai_max_tool_rounds", 5)
        set(value) = prefs.edit().putInt("ai_max_tool_rounds", value).apply()

    // 项目级 AI 模式持久化（PLAN=0, AGENT=1）
    fun saveAiMode(projectId: String, mode: Int) {
        prefs.edit().putInt("ai_mode_$projectId", mode).apply()
    }

    fun getAiMode(projectId: String): Int {
        return prefs.getInt("ai_mode_$projectId", 0) // 默认 PLAN
    }

    // 编辑器：最大定稿章节数（0 = 无限制）
    var maxFinalizedChapters: Int
        get() = prefs.getInt("max_finalized_chapters", 0)
        set(value) = prefs.edit().putInt("max_finalized_chapters", value).apply()

    // 批量淘汰章节数（超限时一次移除最早N章）
    var bulkEvictChapters: Int
        get() = prefs.getInt("bulk_evict_chapters", 1)
        set(value) = prefs.edit().putInt("bulk_evict_chapters", value).apply()
}
