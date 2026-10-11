package cn.novelmaker.wg1337.utils

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform
import cn.novelmaker.wg1337.ui.ai.ApiProfileManager
import cn.novelmaker.wg1337.ui.ai.ConversationSettingsManager
import cn.novelmaker.wg1337.ui.ai.DeepSeekApi

class PreferencesManager {
    private val prefs = Platform.keyValueStore()

    companion object {
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_THEME_MODE = "theme_mode"

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

    // ── AI 设置：统一走「当前启用的 API 配置」 ──
    //
    // 多 API 配置由 ApiProfileManager 管理；这里的读写都代理到启用项，
    // 使调用方（AiApiClient、UI 等）无需关心多配置的存在。
    // 旧版单配置会在 ApiProfileManager 首次读取时自动迁移。

    var aiBaseUrl: String?
        get() = ApiProfileManager.getActiveOrDefault().baseUrl
        set(value) {
            val p = ApiProfileManager.getActiveOrDefault()
            ApiProfileManager.upsert(p.copy(baseUrl = value ?: DeepSeekApi.DEFAULT_BASE_URL))
        }

    var aiModel: String?
        get() = ApiProfileManager.getActiveOrDefault().model
        set(value) {
            val p = ApiProfileManager.getActiveOrDefault()
            ApiProfileManager.upsert(
                p.copy(model = DeepSeekApi.normalizeModel(value ?: DeepSeekApi.MODEL_FLASH))
            )
        }

    var aiApiKey: String?
        get() = ApiProfileManager.getActiveOrDefault().apiKey.ifEmpty { null }
        set(value) {
            val p = ApiProfileManager.getActiveOrDefault()
            ApiProfileManager.upsert(p.copy(apiKey = value.orEmpty()))
        }

    var aiStreamEnabled: Boolean
        get() = prefs.getBoolean("ai_stream_enabled", true)
        set(value) = prefs.putBoolean("ai_stream_enabled", value)

    // 思考强度: none(不思考) / low / high / max
    // 兼容旧值 off / minimal / medium / xhigh / ultra，读取时统一归一化
    var aiReasoningEffort: String
        get() = DeepSeekApi.normalizeEffort(prefs.getString("ai_reasoning_effort", DeepSeekApi.DEFAULT_EFFORT))
        set(value) = prefs.putString("ai_reasoning_effort", DeepSeekApi.normalizeEffort(value))

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

    // ── 上下文注入 / 系统提示词注入：全局默认值 ──
    // 工作区与单对话可在设置页 / 编辑器快捷设置中各自覆盖

    /** 是否启用自动上下文注入 */
    var injectionContextEnabled: Boolean
        get() = prefs.getBoolean("injection_context_enabled", false)
        set(value) = prefs.putBoolean("injection_context_enabled", value)

    /** 每经过 N 次模型回复后注入一次 */
    var injectionContextSteps: Int
        get() = prefs.getInt("injection_context_steps", ConversationSettingsManager.DEFAULT_INJECTION_STEPS)
        set(value) = prefs.putInt("injection_context_steps", value.coerceAtLeast(1))

    /** 注入的上下文内容 */
    var injectionContextText: String?
        get() = prefs.getString("injection_context_text", null)
        set(value) = prefs.putString("injection_context_text", value)

    /** 是否启用自定义系统提示词注入 */
    var injectionSystemPromptEnabled: Boolean
        get() = prefs.getBoolean("injection_system_prompt_enabled", false)
        set(value) = prefs.putBoolean("injection_system_prompt_enabled", value)

    /** 自定义系统提示词内容（追加到系统提示词末尾，随时可改） */
    var injectionSystemPromptText: String?
        get() = prefs.getString("injection_system_prompt_text", null)
        set(value) = prefs.putString("injection_system_prompt_text", value)

    // 注：曾有一项 global customBasePrompt（全局默认基础提示词），它没有任何 UI 能写入，
    // 属于死代码；基础提示词的优先级已简化为「项目自定义 → 内置模板」（见 SystemPromptManager）。
    // 这里在读取时顺手清理历史残留键，避免旧数据迷惑排查。

    // ── 上下文注入：每个对话已累计的模型回复步数 ──
    // 用于「每 N 步注入一次」的计数，跨重启保持

    fun getInjectionStepCounter(projectId: String, tabId: Int): Int =
        prefs.getInt("injection_steps_${projectId}_$tabId", 0)

    fun setInjectionStepCounter(projectId: String, tabId: Int, value: Int) {
        prefs.putInt("injection_steps_${projectId}_$tabId", value)
    }
}
