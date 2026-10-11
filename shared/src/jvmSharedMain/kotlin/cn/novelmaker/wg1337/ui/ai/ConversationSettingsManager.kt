package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform
import cn.novelmaker.wg1337.utils.PreferencesManager
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 上下文注入与系统提示词注入设置。
 *
 * ## 两层结构
 *
 * | 层级 | 作用范围 | 存储 |
 * |---|---|---|
 * | 工作区 | 该项目下所有对话的默认值 | `ai_chats/injection_ws_{projectId}.json` |
 * | 单对话 | 仅当前标签页，**按字段**覆盖工作区 | `ai_chats/injection_tab_{projectId}_{tabId}.json` |
 *
 * 工作区未保存过时回落到全局默认值（设置页「AI 写作设置」），便于新项目开箱可用。
 *
 * ## 关键设计：字段级覆盖，而不是整份快照
 *
 * 每个字段在单对话层都是**三态**：
 * - `null` —— 未设置，**继承工作区**
 * - 有值 —— 该字段使用对话自己的值
 *
 * 早期实现把单对话层当作「整份设置快照」保存（`conversation ?: workspace`），
 * 结果是：只要在对话层保存过一次，之后改工作区就再也影响不到这个对话；
 * 而且「在工作区改了 A 字段 → 切到对话层只改 B 字段 → 保存」会把 workspace 的 A
 * 一起固化进对话层，两层从此永久分叉。
 * 现在只保存与工作区**不同**的字段，未改动的字段保持 null 继续继承。
 *
 * 这样可以做到用户期望的效果：
 * - 对话层只设「上下文注入」，其余仍跟随工作区；
 * - 之后改工作区的系统提示词注入，该对话立即跟着变；
 * - 想恢复继承就清掉该字段（或整个对话覆盖）。
 */
object ConversationSettingsManager {

    private const val TAG = "ConvSettings"

    /** 工作区级默认：每 5 步注入一次 */
    const val DEFAULT_INJECTION_STEPS = 5

    /**
     * 上下文注入设置的**可空三态**版本。
     *
     * 三个字段独立可空：只覆盖其中一个时，另两个仍继承工作区。
     */
    data class ContextOverride(
        val enabled: Boolean? = null,
        val everyNSteps: Int? = null,
        val text: String? = null
    ) {
        val isEmpty: Boolean get() = enabled == null && everyNSteps == null && text == null

        /** 与另一份覆盖是否等价（用于保存时剔除无差异的字段） */
        fun sameAs(other: ContextOverride): Boolean =
            enabled == other.enabled && everyNSteps == other.everyNSteps && text == other.text
    }

    /** 系统提示词注入的**可空三态**版本 */
    data class SystemPromptOverride(
        val enabled: Boolean? = null,
        val text: String? = null
    ) {
        val isEmpty: Boolean get() = enabled == null && text == null

        fun sameAs(other: SystemPromptOverride): Boolean =
            enabled == other.enabled && text == other.text
    }

    /** 一个层级的原始覆盖（字段可空 = 继承上层） */
    data class Override(
        val context: ContextOverride = ContextOverride(),
        val systemPrompt: SystemPromptOverride = SystemPromptOverride()
    ) {
        val isEmpty: Boolean get() = context.isEmpty && systemPrompt.isEmpty
    }

    /**
     * 解析后的**生效值**（字段都可空 = 该字段最终未设置，按关闭处理）。
     * 上下文注入的语义：未显式启用即视为关闭。
     */
    data class ContextInjection(
        val enabled: Boolean = false,
        val everyNSteps: Int = DEFAULT_INJECTION_STEPS,
        val text: String = ""
    ) {
        val isUsable: Boolean get() = enabled && everyNSteps > 0 && text.isNotBlank()
    }

    data class SystemPromptInjection(
        val enabled: Boolean = false,
        val text: String = ""
    ) {
        val isUsable: Boolean get() = enabled && text.isNotBlank()
    }

    /** 每个字段的最终来源，供 UI 展示「继承自工作区」还是「本对话自定义」 */
    enum class FieldSource { WORKSPACE, CONVERSATION }

    data class Effective(
        val context: ContextInjection,
        val systemPrompt: SystemPromptInjection,
        /** 各字段来源 */
        val contextEnabledSource: FieldSource,
        val contextStepsSource: FieldSource,
        val contextTextSource: FieldSource,
        val systemPromptEnabledSource: FieldSource,
        val systemPromptTextSource: FieldSource
    ) {
        /** 是否有任意字段来自对话层 */
        val hasConversationOverride: Boolean
            get() = listOf(
                contextEnabledSource, contextStepsSource, contextTextSource,
                systemPromptEnabledSource, systemPromptTextSource
            ).any { it == FieldSource.CONVERSATION }
    }

    // ───────────────────── 工作区层 ─────────────────────

    /** 工作区层的原始覆盖（未保存过时用全局默认值填充成完整值） */
    fun getWorkspaceOverride(projectId: String): Override {
        if (projectId.isEmpty()) return Override()
        return readOverride(workspaceFile(projectId)) ?: Override()
    }

    /**
     * 工作区层生效值：文件优先，未保存过则用全局默认值。
     * （工作区层本身就是「完整值」，不再往下继承字段。）
     */
    fun getWorkspace(projectId: String, prefs: PreferencesManager): Override {
        val saved = if (projectId.isEmpty()) null else readOverride(workspaceFile(projectId))
        if (saved != null) return saved
        // 未保存过 → 用全局默认值填满
        val g = globalDefault(prefs)
        return Override(
            context = ContextOverride(
                enabled = g.context.enabled,
                everyNSteps = g.context.everyNSteps,
                text = g.context.text
            ),
            systemPrompt = SystemPromptOverride(
                enabled = g.systemPrompt.enabled,
                text = g.systemPrompt.text
            )
        )
    }

    fun saveWorkspace(projectId: String, override: Override) {
        if (projectId.isEmpty()) return
        writeOverride(workspaceFile(projectId), override)
    }

    // ───────────────────── 单对话层 ─────────────────────

    fun getConversationOverride(projectId: String, tabId: Int): Override {
        if (projectId.isEmpty() || tabId <= 0) return Override()
        return readOverride(conversationFile(projectId, tabId)) ?: Override()
    }

    /**
     * 保存单对话覆盖。
     *
     * **必须只传显式自定义的字段**（未自定义的字段传 null）。
     * 这是「两级能分开保存」的前提：传了值就等于在该对话固化了这个字段，
     * 之后改工作区不会再影响它。UI 侧由「自定义 / 恢复继承」开关负责保证这一点
     * （见 ConversationInjectionDialog.buildOverride）。
     */
    fun saveConversationOverride(projectId: String, tabId: Int, override: Override) {
        if (projectId.isEmpty() || tabId <= 0) return
        writeOverride(conversationFile(projectId, tabId), override)
    }

    /** 清除该对话的所有覆盖（全部恢复继承工作区） */
    fun clearConversationOverride(projectId: String, tabId: Int) {
        if (projectId.isEmpty() || tabId <= 0) return
        val f = conversationFile(projectId, tabId)
        if (f.exists()) f.delete()
    }

    /**
     * 计算生效值：按**字段**合并工作区与对话层。
     *
     * 对话层字段非 null 时覆盖工作区，为 null 时继承工作区。
     */
    fun resolve(
        projectId: String,
        tabId: Int,
        prefs: PreferencesManager
    ): Effective {
        val ws = getWorkspace(projectId, prefs)
        val cv = getConversationOverride(projectId, tabId)

        fun <T> pick(conv: T?, wsValue: T): Pair<T, FieldSource> =
            if (conv != null) conv to FieldSource.CONVERSATION else wsValue to FieldSource.WORKSPACE

        val (ctxEnabled, s1) = pick(cv.context.enabled, ws.context.enabled ?: false)
        val (ctxSteps, s2) = pick(
            cv.context.everyNSteps,
            ws.context.everyNSteps ?: DEFAULT_INJECTION_STEPS
        )
        val (ctxText, s3) = pick(cv.context.text, ws.context.text ?: "")
        val (sysEnabled, s4) = pick(cv.systemPrompt.enabled, ws.systemPrompt.enabled ?: false)
        val (sysText, s5) = pick(cv.systemPrompt.text, ws.systemPrompt.text ?: "")

        return Effective(
            context = ContextInjection(
                enabled = ctxEnabled,
                everyNSteps = ctxSteps.coerceAtLeast(1),
                text = ctxText
            ),
            systemPrompt = SystemPromptInjection(enabled = sysEnabled, text = sysText),
            contextEnabledSource = s1,
            contextStepsSource = s2,
            contextTextSource = s3,
            systemPromptEnabledSource = s4,
            systemPromptTextSource = s5
        )
    }

    // ───────────────────── 全局默认值 ─────────────────────

    /** 全局默认值（设置页可改），作为工作区层未保存时的初值 */
    data class GlobalDefault(
        val context: ContextInjection,
        val systemPrompt: SystemPromptInjection
    )

    fun globalDefault(prefs: PreferencesManager): GlobalDefault = GlobalDefault(
        context = ContextInjection(
            enabled = prefs.injectionContextEnabled,
            everyNSteps = prefs.injectionContextSteps,
            text = prefs.injectionContextText.orEmpty()
        ),
        systemPrompt = SystemPromptInjection(
            enabled = prefs.injectionSystemPromptEnabled,
            text = prefs.injectionSystemPromptText.orEmpty()
        )
    )

    // ───────────────────── 存储 ─────────────────────

    private fun dir(): File {
        val d = File(Platform.internalDataDir(), "ai_chats")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun workspaceFile(projectId: String) = File(dir(), "injection_ws_$projectId.json")

    private fun conversationFile(projectId: String, tabId: Int) =
        File(dir(), "injection_tab_${projectId}_$tabId.json")

    /**
     * 读取覆盖配置。
     *
     * 只写入**显式设置过**的字段：JSON 里缺 key 或值为 null → 该字段为 null（继承）。
     * 兼容早期版本写入的完整值（那些字段会被当作显式覆盖读入）。
     */
    private fun readOverride(file: File): Override? {
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val ctx = json.optJSONObject("context")
            val sys = json.optJSONObject("systemPrompt")
            Override(
                context = ContextOverride(
                    enabled = ctx?.optBooleanOrNull("enabled"),
                    everyNSteps = ctx?.optIntOrNull("everyNSteps"),
                    text = ctx?.optStringOrNull("text")
                ),
                systemPrompt = SystemPromptOverride(
                    enabled = sys?.optBooleanOrNull("enabled"),
                    text = sys?.optStringOrNull("text")
                )
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取注入设置失败: ${file.name} - ${e.message}")
            null
        }
    }

    /** 写入时只落盘非 null 字段（null = 继承，不必存储） */
    private fun writeOverride(file: File, override: Override) {
        try {
            val json = JSONObject()
            val ctx = JSONObject()
            override.context.enabled?.let { ctx.put("enabled", it) }
            override.context.everyNSteps?.let { ctx.put("everyNSteps", it.coerceAtLeast(1)) }
            override.context.text?.let { ctx.put("text", it) }
            if (ctx.length() > 0) json.put("context", ctx)

            val sys = JSONObject()
            override.systemPrompt.enabled?.let { sys.put("enabled", it) }
            override.systemPrompt.text?.let { sys.put("text", it) }
            if (sys.length() > 0) json.put("systemPrompt", sys)

            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.toString(2))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存注入设置失败: ${file.name}", e)
        }
    }

    // org.json 没有「区分 null 与缺省」的取值方法，这里补上

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    /** 删除项目的全部注入设置 */
    fun deleteProjectSettings(projectId: String) {
        try {
            dir().listFiles()?.forEach { f ->
                if (f.name.contains(projectId) &&
                    (f.name.startsWith("injection_ws_") || f.name.startsWith("injection_tab_"))
                ) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "删除注入设置失败: ${e.message}")
        }
    }
}
