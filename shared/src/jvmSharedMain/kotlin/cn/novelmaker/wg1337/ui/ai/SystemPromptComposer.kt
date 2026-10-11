package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import java.io.File

/**
 * 系统提示词**拼装**（唯一真源）。
 *
 * 此前这段逻辑散落在 `SystemPromptManager`、`AiChatViewModel.buildSystemPrompt()`、
 * `AiChatViewModel.buildPrefixMessages()` 和两个设置对话框里，导致：
 * 「查看完整」看到的不是真实内容、用户不知道哪段来自哪里、
 * 以及**用户改一次提示词就把定稿章节的缓存一起废掉**。
 *
 * 现在统一为 4 层，每层有名字、有归属、有明确的缓存属性：
 *
 * | 层 | 来源 | 可编辑 | 进缓存前缀 | 变化频率 |
 * |---|---|---|---|---|
 * | 1 基础提示词 | 项目自定义 / 内置模板 | 是 | **是** | 极少 |
 * | 2 引用文件 | 用户在设置中勾选的项目文件 | 是（选文件） | **是** | Plan 阶段会变 |
 * | 3 已定稿章节 | 文件树中标记为定稿的章节 | 是（标记） | **是** | 定稿后不再变 |
 * | 4 用户追加 | 「系统提示词注入」 | 是 | 否 | 随时可改 |
 * | 5 平台固定规则 | 代码内置（联网搜索、模式协议） | 否 | 否 | 恒定 |
 * | 6 当前编辑内容 | 编辑器正在编辑的正文 | 间接 | 否 | 每次编辑都变 |
 *
 * **缓存断点落在第 1–3 层之后**：第 4 层（用户随时会改）与第 5、6 层
 * （模式切换/编辑就变）都排在断点之外，所以改提示词注入不会连累
 * 定稿章节那一大块前缀缓存。这正是 KV-Cache 的用法：把最稳的放最前、能缓存的尽量靠前。
 */
object SystemPromptComposer {

    private const val TAG = "PromptComposer"

    /** 拼装所需的全部上下文 */
    data class Input(
        val projectId: String,
        val projectName: String,
        /** 当前 AI 模式（决定第 5 层的模式协议文本） */
        val mode: AiChatViewModel.AiMode,
        /** 生效的注入设置（取 SystemPromptInjection 作为第 4 层） */
        val injection: ConversationSettingsManager.SystemPromptInjection =
            ConversationSettingsManager.SystemPromptInjection(),
        /** 当前编辑的正文（第 6 层）；空则整层省略 */
        val currentContent: String = "",
        /** 当前编辑的文件标题，用于第 6 层的标注 */
        val currentTitle: String = ""
    )

    /** 一层拼装结果 */
    data class Part(
        val label: String,
        val content: String,
        val role: String = "system",
        /** 是否位于缓存断点之前 */
        val cacheable: Boolean = false
    ) {
        val isUsable: Boolean get() = content.isNotBlank()
    }

    /** 拼装结果 */
    data class Composed(
        val parts: List<Part>,
        /** 缓存断点位置：前 N 条 part 标 cache_control */
        val cacheableCount: Int
    ) {
        /** 真正会发给模型的 system 消息（供「查看完整」使用，与实际请求一致） */
        val systemMessages: List<AiChatMessage>
            get() = parts.filter { it.isUsable }.map { AiChatMessage(role = it.role, content = it.content) }

        val totalChars: Int get() = parts.sumOf { it.content.length }
    }

    /**
     * 按 4 层结构拼装。
     *
     * @param includeDynamic 是否包含第 6 层（当前编辑内容）。
     *        预览时可以关掉 —— 预览关心的是「提示词定义」，不是此刻编辑器里有什么。
     */
    fun compose(input: Input, includeDynamic: Boolean = true): Composed {
        val parts = mutableListOf<Part>()

        // ── 1. 基础提示词 ──
        val base = SystemPromptManager().getBasePrompt(input.projectId)
        parts.add(Part(label = "基础提示词", content = base.trim(), cacheable = true))

        // ── 2. 引用文件（路径引用 → 每次读取最新内容） ──
        val refs = buildContextFilesSection(input.projectId, input.projectName)
        parts.add(Part(label = "引用文件", content = refs, cacheable = true))

        // ── 3. 已定稿章节 ──
        val finalized = buildFinalizedSection(input.projectId, input.projectName)
        parts.add(Part(label = "已定稿章节", content = finalized, cacheable = true))

        // ★ 缓存断点：以上三层键值稳定，改动不频繁
        val cacheableCount = parts.size

        // ── 4. 用户追加（随时可改 → 不进缓存） ──
        // 直接使用用户填写的内容，**不加任何前缀**：
        // 用户写什么就发什么，避免额外修饰干扰模型对指令的理解。
        if (input.injection.isUsable) {
            parts.add(
                Part(
                    label = "系统提示词注入",
                    content = input.injection.text.trim(),
                    cacheable = false
                )
            )
        }

        // ── 5. 平台固定规则（恒定，但是按模式切换的 → 不进缓存） ──
        parts.add(Part(label = "平台规则", content = platformRules(input.mode), cacheable = false))

        // ── 6. 当前编辑内容（每次编辑都变 → 不进缓存） ──
        if (includeDynamic && input.currentContent.isNotBlank()) {
            parts.add(
                Part(
                    label = "当前编辑内容",
                    content = "【正在编辑：${input.currentTitle.ifBlank { "未命名" }}】\n${input.currentContent}",
                    cacheable = false
                )
            )
        }

        return Composed(parts = parts, cacheableCount = cacheableCount)
    }

    /**
     * 供设置界面展示的预览：带来源标注的完整文本。
     * 与实际请求使用同一套拼装，不会再出现「看到的不是实际发的」。
     */
    fun preview(input: Input): String {
        val composed = compose(input, includeDynamic = false)
        val sb = StringBuilder()
        composed.parts.forEach { part ->
            if (!part.isUsable) return@forEach
            sb.append("══════ ").append(part.label)
            sb.append(if (part.cacheable) "（进缓存前缀）" else "（不进缓存）")
            sb.append(" ══════\n")
            sb.append(part.content.trim()).append("\n\n")
        }
        return sb.toString().trim()
    }

    // ───────────────────── 各层构建 ─────────────────────

    /**
     * 第 2 层：引用文件。
     *
     * 存的是路径，每次拼装时重新读取 —— 文件更新后提示词自动跟随，
     * 且不会像旧的 additions 快照那样反复累积同一份内容。
     */
    private fun buildContextFilesSection(projectId: String, projectName: String): String {
        if (projectId.isEmpty() || projectName.isEmpty()) return ""
        val files = SystemPromptManager().getContextFiles(projectId)
        if (files.isEmpty()) return ""

        val projectDir = ProjectStorageManager.getProjectDir(projectName)
        val sb = StringBuilder("【引用文件】\n")
        var included = 0

        files.forEach { relPath ->
            val file = File(projectDir, relPath)
            // 路径必须落在项目目录内（引用配置可能因项目改名而失效）
            val inside = try {
                file.canonicalPath.startsWith(projectDir.canonicalPath + File.separator)
            } catch (_: Exception) { false }
            if (!inside || !file.exists() || !file.isFile) {
                sb.appendLine("--- $relPath（文件不存在，已跳过） ---")
                return@forEach
            }
            val text = try {
                file.readText().take(SystemPromptManager.MAX_CONTEXT_FILE_CHARS)
            } catch (e: Exception) {
                AppLogger.w(TAG, "读取引用文件失败: $relPath - ${e.message}")
                sb.appendLine("--- $relPath（读取失败，已跳过） ---")
                return@forEach
            }
            if (text.isBlank()) return@forEach
            sb.appendLine("--- $relPath ---")
            sb.appendLine(text)
            sb.appendLine()
            included++
        }

        return if (included == 0) "" else sb.toString().trim()
    }

    /** 第 3 层：已定稿章节（内容较长，放在缓存前缀里收益最大） */
    private fun buildFinalizedSection(projectId: String, projectName: String): String {
        if (projectId.isEmpty() || projectName.isEmpty()) return ""
        val files = FinalizedManager().getFinalizedFiles(projectId)
        if (files.isEmpty()) return ""

        val projectDir = ProjectStorageManager.getProjectDir(projectName)
        val sb = StringBuilder("【已定稿章节】\n")
        files
            .sortedWith(
                compareBy(
                    { ProjectStorageManager.extractChapterNumber(it) },
                    { it.lowercase() }
                )
            )
            .forEach { path ->
                val file = File(projectDir, path)
                if (file.exists() && file.isFile) {
                    sb.appendLine("--- ${file.name} ---")
                    sb.appendLine(file.readText())
                }
            }
        return sb.toString().trim()
    }

    /**
     * 第 5 层：平台固定规则。
     * 这些是应用与模型之间的协议（工具用法、模式约束、特殊标记），
     * 用户不可编辑，但**必须能被看到** —— 之前它们散在 ViewModel 里，
     * 导致「查看完整」显示的不是真实内容。
     */
    private fun platformRules(mode: AiChatViewModel.AiMode): String {
        val sb = StringBuilder()

        sb.append(
            "【联网搜索】当你遇到不了解的信息、不确定的事实、没见过的文体/写作风格或概念，" +
                "或用户需要最新资料、范文示例时，使用 webSearch 工具联网搜索。" +
                "优先使用 bing 搜索引擎（默认），google 作为备选。" +
                "搜索关键词由你根据用户需求自行拟定，可以一次搜索多个关键词。" +
                "需要查看搜索结果中某个链接的具体内容或获取参考资料原文时，" +
                "使用 fetchUrl 工具打开该网址获取页面内容，然后综合内容回答用户。"
        )

        sb.append("\n\n【图片理解】用户可能会发送图片（截图、参考图、手写设定等）。"
            + "收到图片时请先准确描述你看到的内容，再结合用户的文字要求处理；"
            + "如果图片与写作需求相关，把图中信息纳入你的构思。")

        if (mode == AiChatViewModel.AiMode.PLAN) {
            sb.append("\n\n【当前模式：Plan 计划模式】\n")
            sb.append("你只能在大纲目录下创建和修改文件。不能写入小说主体目录。\n")
            sb.append("在计划过程中，请将收集到的写作要求（类型、风格、语言、字数、每章字数等）写入 大纲/写作设定.txt。\n")
            sb.append(
                "【何时输出 [PLAN_COMPLETE]】当大纲、角色、章节规划已完善，且关键设定已写入大纲文件后，" +
                    "在回复末尾单独一行输出 [PLAN_COMPLETE]，系统会弹出选项。" +
                    "切换后会自动把大纲设定文件加入系统提示词的引用文件。\n"
            )
            sb.append("\n【方向选择】需要用户选择时，务必使用（每次只问一个问题）：\n")
            sb.append("[DIRECTION_CHOICES:选项1|选项2|选项3]")
        } else {
            sb.append(
                "\n\n【当前模式：Agent 写作模式】可以自由读写所有目录，专注创作小说正文。" +
                    "如需调整计划，用户可以手动切换回 Plan 模式。"
            )
            sb.append(
                "\n\n【主动引导定稿】当小说主体目录已有较多章节（3 章及以上）时，" +
                    "应主动提醒用户将已完成、确定不再修改的章节标记为定稿，并向用户说明定稿的好处：" +
                    "1) 定稿章节会纳入缓存前缀，后续对话命中缓存，更省 Token、回复更快；" +
                    "2) 每次写作前你都会回顾全部定稿章节，保证剧情、人物设定与写作风格前后一致；" +
                    "3) 避免上下文过长导致早期关键设定被挤出。" +
                    "用户同意后，引导用户使用文件树中长按章节的「标记定稿」功能" +
                    "（由用户在编辑器里手动操作，你无法代替用户定稿）。"
            )
        }

        return sb.toString()
    }
}
