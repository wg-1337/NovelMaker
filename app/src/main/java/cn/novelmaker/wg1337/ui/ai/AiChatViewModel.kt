package cn.novelmaker.wg1337.ui.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.novelmaker.wg1337.ui.home.AppContextHolder
import cn.novelmaker.wg1337.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import cn.novelmaker.wg1337.utils.ProjectStorageManager

class AiChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "AiChatVM"
        const val MAX_TABS = 5
    }

    private val context = AppContextHolder.context
    private val prefsManager = PreferencesManager(context)
    private val chatHistoryManager = AiChatHistoryManager(context)
    private val systemPromptManager = SystemPromptManager(context)
    private val tokenUsageManager = TokenUsageManager(context)
    val finalizedManager = FinalizedManager(context)  // 公开，供编辑器共享

    enum class AiMode { PLAN, AGENT }
    private val _currentMode = MutableStateFlow(AiMode.PLAN)
    val currentMode: StateFlow<AiMode> = _currentMode.asStateFlow()

    private var projectName = ""
    private var projectId = ""
    private var getCurrentContent: (() -> String)? = null
    private var getCurrentTitle: (() -> String)? = null

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _showResume = MutableStateFlow(false)
    val showResume: StateFlow<Boolean> = _showResume.asStateFlow()

    /** 文件变更版本号：AI 每次成功写/删/标记文件后 +1，供编辑器刷新文件树 */
    private val _fileChangeVersion = MutableStateFlow(0)
    val fileChangeVersion: StateFlow<Int> = _fileChangeVersion.asStateFlow()

    // ── 标签页（每项目最多 MAX_TABS 个独立会话） ──
    // 合并为单一 State，避免 tabs / activeTabId 双 StateFlow 收集竞态
    private val _tabState = MutableStateFlow(AiTabState(listOf(AiChatTab(1, "对话1")), 1))
    val tabState: StateFlow<AiTabState> = _tabState.asStateFlow()

    @Volatile private var accumulatedContent = ""
    @Volatile private var accumulatedReasoning = ""

    private var lastApiClient: AiApiClient? = null
    private var lastFTools: AiFileTools? = null
    private var lastTools: JSONArray? = null
    private var lastMaxToolRounds = 5
    private var lastCurrentRound = 0

    fun init(projectName: String, projectId: String, getContent: () -> String, getTitle: () -> String) {
        this.projectName = projectName; this.projectId = projectId
        this.getCurrentContent = getContent; this.getCurrentTitle = getTitle
        // 恢复持久化的模式
        if (projectId.isNotEmpty()) {
            val savedMode = prefsManager.getAiMode(projectId)
            _currentMode.value = if (savedMode == 1) AiMode.AGENT else AiMode.PLAN
            // 发现已有标签页（含旧数据迁移）
            val usedIds = chatHistoryManager.getUsedTabIds(projectId)
            // 恢复上次激活的标签页（若已不存在则回退到第一个）
            val savedTab = prefsManager.getActiveAiTab(projectId)
            val active = if (usedIds.contains(savedTab)) savedTab else usedIds.first()
            _tabState.value = AiTabState(usedIds.map { AiChatTab(it, "对话$it") }, active)
            _messages.value = chatHistoryManager.loadChatHistory(projectId, active)
        }
    }

    /** 新建标签页（最多 MAX_TABS 个）；AI 处理中禁止 */
    fun addTab() {
        if (_isProcessing.value) return
        val current = _tabState.value
        if (current.tabs.size >= MAX_TABS) return
        val newId = (1..MAX_TABS).firstOrNull { id -> current.tabs.none { it.id == id } } ?: return
        saveCurrentChat()
        _tabState.value = AiTabState(current.tabs + AiChatTab(newId, "对话$newId"), newId)
        _messages.value = emptyList()
        _showResume.value = false
        accumulatedContent = ""; accumulatedReasoning = ""
        // 一个对话一个记录文件：空对话也立即创建，重启后对话不丢失、可被管理/备份
        if (projectId.isNotEmpty()) {
            chatHistoryManager.ensureTabFile(projectId, newId)
            prefsManager.saveActiveAiTab(projectId, newId)
        }
    }

    /** 切换到指定标签页；AI 处理中禁止 */
    fun switchTab(tabId: Int) {
        if (_isProcessing.value) return
        val current = _tabState.value
        if (tabId == current.activeTabId) return
        if (current.tabs.none { it.id == tabId }) return
        saveCurrentChat()
        _tabState.value = current.copy(activeTabId = tabId)
        _messages.value = chatHistoryManager.loadChatHistory(projectId, tabId)
        _showResume.value = false
        accumulatedContent = ""; accumulatedReasoning = ""
        if (projectId.isNotEmpty()) {
            chatHistoryManager.ensureTabFile(projectId, tabId) // 首次切换到的对话也确保有记录文件
            prefsManager.saveActiveAiTab(projectId, tabId)
        }
    }

    fun sendMessage(text: String) {
        if (prefsManager.aiApiKey.isNullOrEmpty() || _isProcessing.value) return
        _messages.update { it + AiChatMessage(role = "user", content = text) }
        _isProcessing.value = true
        _showResume.value = false
        performRequest(retry = false)
    }

    fun stopGeneration() {
        _isProcessing.value = false
        _showResume.value = accumulatedContent.isNotEmpty()
        saveCurrentChat()
    }

    fun resumeFromInterruption() {
        _messages.update { if (it.lastOrNull()?.role == "system") it.dropLast(1) else it }
        _isProcessing.value = true
        _showResume.value = false
        performRequest(retry = true)
    }

    fun editMessage(index: Int, newContent: String) {
        _messages.update { msgs -> msgs.toMutableList().also { if (index in it.indices) it[index] = it[index].copy(content = newContent) } }
        saveCurrentChat()
    }

    /**
     * 从指定索引重发消息：截断该消息之后的所有对话，修改该消息内容，重新发送
     */
    fun resendFrom(index: Int, newContent: String) {
        if (index !in _messages.value.indices) return
        if (_isProcessing.value) return

        _messages.update { msgs ->
            val truncated = msgs.take(index).toMutableList()
            truncated.add(msgs[index].copy(content = newContent))
            truncated
        }
        _isProcessing.value = true
        _showResume.value = false
        performRequest(retry = false)
    }

    fun deleteMessage(index: Int) {
        _messages.update { msgs -> msgs.toMutableList().also { if (index in it.indices) it.removeAt(index) } }
        saveCurrentChat()
    }

    private fun performRequest(retry: Boolean) {
        val apiKey = prefsManager.aiApiKey
        if (apiKey.isNullOrEmpty()) {
            // 未配置 API Key：复位处理状态，避免永久卡在“AI 思考中”
            _isProcessing.value = false
            _showResume.value = false
            return
        }
        val baseUrl = prefsManager.aiBaseUrl ?: "https://api.deepseek.com"
        val model = prefsManager.aiModel ?: "deepseek-chat"
        val useStream = prefsManager.aiStreamEnabled
        val maxRounds = prefsManager.aiMaxToolRounds
        val fTools = AiFileTools(
            projectName, projectId, _currentMode.value,
            onFileChanged = { _fileChangeVersion.update { it + 1 } },
            fetchPlainText = prefsManager.aiFetchPlainText
        )
        val tools = fTools.getToolDefinitions()
        val apiClient = AiApiClient(
            apiKey = apiKey, baseUrl = baseUrl, model = model,
            reasoningEffort = prefsManager.aiReasoningEffort ?: "high",
            thinkingEnabled = true
        )
        lastApiClient = apiClient; lastFTools = fTools; lastTools = tools; lastMaxToolRounds = maxRounds
        if (!retry) lastCurrentRound = 0
        sendWithToolLoop(apiClient, fTools, tools, useStream, maxRounds, if (retry) lastCurrentRound else 0)
    }

    private fun sendWithToolLoop(
        apiClient: AiApiClient, fTools: AiFileTools, tools: JSONArray,
        useStream: Boolean, maxRounds: Int, startRound: Int
    ) {
        if (!_isProcessing.value) return
        val apiMessages = mutableListOf<AiChatMessage>()

        // 1. 系统提示词（固定前缀 → 标记 cache_control）
        val fullPrompt = buildSystemPrompt()
        val sysMsg = AiChatMessage(role = "system", content = fullPrompt)
        apiMessages.add(sysMsg)

        // 2. 已定稿章节拼接（固定前缀 → 标记 cache_control）
        val finalized = buildFinalizedChapters()
        if (finalized.isNotEmpty()) {
            apiMessages.add(AiChatMessage(role = "system", content = finalized))
        }

        // 3. 当前编辑内容（可变 → 不缓存）
        val ctx = buildContextContent()
        if (ctx.isNotEmpty()) {
            apiMessages.add(AiChatMessage(role = "system", content = ctx))
        }

        // 4. 历史对话（排除 choice 角色——那是 UI 专用；保留 system 状态提示）
        apiMessages.addAll(_messages.value.filter { it.role != "choice" })

        // 构建 JSON：仅真正的固定前缀（系统提示词 + 定稿章节）加 cache_control。
        // 当前编辑内容 ctx 是可变的，绝不能标缓存，否则每次编辑都会打碎缓存。
        val cacheCount = 1 + (if (finalized.isNotEmpty()) 1 else 0)
        val msgsJson = JSONArray()
        for (i in apiMessages.indices) {
            val cache = i < cacheCount
            msgsJson.put(apiMessages[i].toJsonObject(cacheControl = cache))
        }

        // Token 统计：记录系统提示词
        if (projectId.isNotEmpty()) {
            tokenUsageManager.recordSystemPrompt(projectId, fullPrompt.length + finalized.length)
        }

        val assistantIdx = _messages.value.size
        _messages.update { it + AiChatMessage(role = "assistant", content = "", reasoningContent = "") }
        accumulatedContent = ""; accumulatedReasoning = ""
        var toolCallsHandled = false

        apiClient.sendChatRequestStream(
            messages = emptyList(), tools = tools, prebuiltMessagesJson = msgsJson,
            onToken = { content, reasoning ->
                if (!_isProcessing.value) return@sendChatRequestStream
                accumulatedContent += content; accumulatedReasoning += reasoning
                _messages.update { msgs ->
                    msgs.toMutableList().also { if (assistantIdx < it.size) it[assistantIdx] = it[assistantIdx].copy(content = accumulatedContent, reasoningContent = accumulatedReasoning) }
                }
            },
            onToolCalls = { tcs -> toolCallsHandled = true; handleToolCalls(apiClient, fTools, tools, useStream, maxRounds, startRound, assistantIdx, tcs) },
            onComplete = { success, error ->
                if (!toolCallsHandled) {
                    _isProcessing.value = false
                    if (!success || error != null) { _showResume.value = true; _messages.update { msgs -> msgs.toMutableList().also { if (assistantIdx < it.size && it[assistantIdx].content.isEmpty() && error != null) it[assistantIdx] = it[assistantIdx].copy(content = "⚠️ $error") } } }
                    // Token 统计：记录对话
                    if (projectId.isNotEmpty()) {
                        val lastUserMsg = _messages.value.getOrNull(assistantIdx - 1)?.content?.length ?: 0
                        tokenUsageManager.recordChat(projectId, lastUserMsg, accumulatedContent.length)
                    }
                    saveCurrentChat()
                }
            }
        )
    }

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        // 基础提示词 + Plan 阶段追加
        sb.append(systemPromptManager.getFullPrompt(projectId))
        // 联网搜索说明（两种模式通用）
        sb.append("\n\n【联网搜索】当你遇到不了解的信息、不确定的事实、没见过的文体/写作风格或概念，或用户需要最新资料、范文示例时，使用 webSearch 工具联网搜索。优先使用 bing 搜索引擎（默认），google 作为备选。搜索关键词由你根据用户需求自行拟定，可以一次搜索多个关键词。需要查看搜索结果中某个链接的具体内容或获取参考资料原文时，使用 fetchUrl 工具打开该网址获取页面 HTML，然后综合内容回答用户。")
        // Plan 模式指示
        if (_currentMode.value == AiMode.PLAN) {
            sb.append("\n\n【当前模式：Plan 计划模式】\n")
            sb.append("你只能在大纲/目录下创建和修改文件。不能写入小说主体/目录。\n")
            sb.append("在计划过程中，请将收集到的写作要求（类型、风格、语言、字数、每章字数等）写入 大纲/写作设定.txt。\n")
            sb.append("【何时输出 [PLAN_COMPLETE]】当大纲、角色、章节规划已完善，且关键设定已写入大纲文件后，在回复末尾单独一行输出 [PLAN_COMPLETE]，系统会弹出选项。切换后会自动将大纲文件内容导入系统提示词。\n")
            sb.append("\n")
            sb.append("【方向选择】需要用户选择时，务必使用（每次只问一个问题）：\n")
            sb.append("[DIRECTION_CHOICES:选项1|选项2|选项3]\n")
        } else {
            sb.append("\n\n【当前模式：Agent 写作模式】可以自由读写所有目录，专注创作小说正文。如需调整计划，用户可以手动切换回 Plan 模式。")
            // 定稿主动引导
            sb.append("\n\n【主动引导定稿】当小说主体目录已有较多章节（3 章及以上）时，应主动提醒用户将已完成、确定不再修改的章节标记为定稿，并向用户说明定稿的好处：1) 定稿章节会纳入缓存前缀，后续对话命中缓存，更省 Token、回复更快；2) 每次写作前你都会回顾全部定稿章节，保证剧情、人物设定与写作风格前后一致；3) 避免上下文过长导致早期关键设定被挤出。用户同意后，引导用户使用文件树中长按章节的「标记定稿」功能（由用户在编辑器里手动操作，你无法代替用户定稿）。")
        }
        return sb.toString()
    }

    private fun buildFinalizedChapters(): String {
        if (projectId.isEmpty()) return ""
        val files = finalizedManager.getFinalizedFiles(projectId)
        if (files.isEmpty()) return ""
        val sb = StringBuilder("【已定稿章节】\n")
        val projectDir = ProjectStorageManager.getProjectDir(projectName)
        files.sortedWith(compareBy({ ProjectStorageManager.extractChapterNumber(it) }, { it.lowercase() })).forEach { path ->
            val file = File(projectDir, path)
            if (file.exists()) {
                sb.appendLine("--- ${file.name} ---")
                sb.appendLine(file.readText())
            }
        }
        return sb.toString()
    }

    private fun buildContextContent(): String {
        val content = getCurrentContent?.invoke() ?: return ""
        if (content.isBlank()) return ""
        return "【正在编辑：${getCurrentTitle?.invoke() ?: "未命名"}】\n$content"
    }

    fun switchToAgentMode() {
        _currentMode.value = AiMode.AGENT
        if (projectId.isNotEmpty()) prefsManager.saveAiMode(projectId, 1)
        // 将大纲目录中的设定文件内容追加到系统提示词
        appendOutlineToPrompt()
        systemPromptManager.exportToProjectFile(projectName, projectId)
        _messages.update { it + AiChatMessage(role = "system", content = "✅ 已切换到 Agent 写作模式，现在可以写入小说主体了。") }
    }

    fun switchToPlanMode() {
        _currentMode.value = AiMode.PLAN
        if (projectId.isNotEmpty()) prefsManager.saveAiMode(projectId, 0)
        _messages.update { it + AiChatMessage(role = "system", content = "🗺️ 已切换到 Plan 计划模式，只能修改大纲目录。如需调整计划请继续。") }
    }

    /**
     * 将大纲目录下的设定文件内容追加到系统提示词
     */
    private fun appendOutlineToPrompt() {
        try {
            val outlineDir = ProjectStorageManager.getProjectSubDir(projectName, "大纲") ?: return
            if (!outlineDir.exists()) return
            val txtFiles = outlineDir.listFiles()?.filter { it.extension.equals("txt", true) && it.isFile } ?: return
            if (txtFiles.isEmpty()) return
            val sb = StringBuilder()
            for (file in txtFiles.sortedBy { it.name }) {
                val content = file.readText().take(8000) // 限制单文件
                if (content.isNotBlank()) {
                    sb.appendLine("=== ${file.nameWithoutExtension} ===")
                    sb.appendLine(content)
                    sb.appendLine()
                }
            }
            if (sb.isNotEmpty()) {
                systemPromptManager.addToPrompt(projectId, "【写作设定（来自大纲）】\n$sb")
                systemPromptManager.exportToProjectFile(projectName, projectId)
            }
        } catch (_: Exception) {}
    }

    fun onUserChoice(choiceText: String) {
        if (_isProcessing.value) return  // 防止重复/并发请求
        if (prefsManager.aiApiKey.isNullOrEmpty()) return  // 未配置 Key 时静默忽略，避免卡死
        _messages.update { it + AiChatMessage(role = "user", content = choiceText) }
        _isProcessing.value = true
        _showResume.value = false
        performRequest(retry = false)
    }

    private fun handleToolCalls(
        apiClient: AiApiClient, fTools: AiFileTools, tools: JSONArray,
        useStream: Boolean, maxRounds: Int, currentRound: Int,
        assistantIdx: Int, toolCalls: List<ToolCall>
    ) {
        _messages.update { msgs -> msgs.toMutableList().also { if (assistantIdx < it.size) it[assistantIdx] = it[assistantIdx].copy(toolCalls = toolCalls) } }
        val results = toolCalls.map { tc ->
            val r = try { fTools.executeToolCall(tc.function.name, tc.function.arguments) } catch (e: Exception) { "❌ ${e.message}" }
            // Token 统计：记录工具调用
            if (projectId.isNotEmpty()) tokenUsageManager.recordToolCall(projectId, r.length)
            AiChatMessage(role = "tool", content = r, toolCallId = tc.id)
        }
        _messages.update { it + results }
        if (!_isProcessing.value) return
        val nextRound = currentRound + 1
        if (maxRounds < 0 || nextRound < maxRounds) {
            lastCurrentRound = nextRound
            viewModelScope.launch(Dispatchers.IO) { sendWithToolLoop(apiClient, fTools, tools, useStream, maxRounds, nextRound) }
        } else {
            _messages.update { it + AiChatMessage(role = "system", content = "已达到最大工具调用轮次($maxRounds)") }
            _isProcessing.value = false; saveCurrentChat()
        }
    }

    private fun saveCurrentChat() {
        if (_messages.value.isNotEmpty() && projectId.isNotEmpty()) {
            chatHistoryManager.saveChatHistory(projectId, _tabState.value.activeTabId, _messages.value)
        }
    }

    override fun onCleared() { super.onCleared(); saveCurrentChat() }
}

/** AI 标签页（每项目最多 5 个独立会话） */
data class AiChatTab(
    val id: Int,        // 1..5，稳定标识
    val name: String    // "对话1" .. "对话5"
)

/** AI 标签页整体状态（tabs + 当前激活 id，合并为单一 State 供 UI 原子读取） */
data class AiTabState(
    val tabs: List<AiChatTab>,
    val activeTabId: Int
)
