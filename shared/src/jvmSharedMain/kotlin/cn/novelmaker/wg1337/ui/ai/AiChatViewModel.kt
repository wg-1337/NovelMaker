package cn.novelmaker.wg1337.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.utils.PreferencesManager
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

class AiChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "AiChatVM"
        const val MAX_TABS = 5
        // 上下文注入不再使用文本前缀标识，改用 AiChatMessage.isInjection 标记，
        // 这样注入内容可以原样发送给模型（不加任何修饰）。
    }

    private val prefsManager = PreferencesManager()
    private val chatHistoryManager = AiChatHistoryManager()
    private val systemPromptManager = SystemPromptManager()
    private val tokenUsageManager = TokenUsageManager()
    val finalizedManager = FinalizedManager() // 公开，供编辑器共享

    enum class AiMode { PLAN, AGENT }

    private val _currentMode = MutableStateFlow(AiMode.PLAN)
    val currentMode: StateFlow<AiMode> = _currentMode.asStateFlow()

    private var projectName = ""
    private var projectId = ""
    private var getCurrentContent: (() -> String)? = null
    private var getCurrentTitle: (() -> String)? = null

    /** 当前项目 id（供设置界面读写项目级配置） */
    val currentProjectId: String get() = projectId

    /** 当前项目名（供设置界面展示） */
    val currentProjectName: String get() = projectName

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /** 是否可续传（上次生成被中断且有未完成内容） */
    private val _showResume = MutableStateFlow(false)
    val showResume: StateFlow<Boolean> = _showResume.asStateFlow()

    /** 该轮是否已经有任何内容产出（用于续传判断） */
    @Volatile private var turnProducedOutput = false

    /**
     * 本轮是否已结算（防止 stopGeneration 与 onComplete 回调交错导致重复计数/重复收尾）。
     */
    @Volatile private var turnFinalized = false

    /** 文件变更版本号：AI 每次成功写/删/标记文件后 +1，供编辑器刷新文件树 */
    private val _fileChangeVersion = MutableStateFlow(0)
    val fileChangeVersion: StateFlow<Int> = _fileChangeVersion.asStateFlow()

    /** 待发送的图片附件（发送后清空） */
    private val _pendingImages = MutableStateFlow<List<ChatImage>>(emptyList())
    val pendingImages: StateFlow<List<ChatImage>> = _pendingImages.asStateFlow()

    /** 当前生效的注入设置（工作区 + 单对话**按字段**合并的结果） */
    private val _effectiveSettings = MutableStateFlow(
        ConversationSettingsManager.Effective(
            context = ConversationSettingsManager.ContextInjection(),
            systemPrompt = ConversationSettingsManager.SystemPromptInjection(),
            contextEnabledSource = ConversationSettingsManager.FieldSource.WORKSPACE,
            contextStepsSource = ConversationSettingsManager.FieldSource.WORKSPACE,
            contextTextSource = ConversationSettingsManager.FieldSource.WORKSPACE,
            systemPromptEnabledSource = ConversationSettingsManager.FieldSource.WORKSPACE,
            systemPromptTextSource = ConversationSettingsManager.FieldSource.WORKSPACE
        )
    )
    val effectiveSettings: StateFlow<ConversationSettingsManager.Effective> =
        _effectiveSettings.asStateFlow()

    /**
     * 系统提示词配置版本号。
     * 基础提示词或引用文件变化时 +1，供设置界面刷新预览。
     */
    private val _promptConfigVersion = MutableStateFlow(0)
    val promptConfigVersion: StateFlow<Int> = _promptConfigVersion.asStateFlow()

    /** 设置界面改动系统提示词配置后调用，通知刷新 */
    fun notifyPromptConfigChanged() {
        _promptConfigVersion.update { it + 1 }
    }

    // ── 标签页（每项目最多 MAX_TABS 个独立会话） ──
    private val _tabState = MutableStateFlow(AiTabState(listOf(AiChatTab(1, "对话1")), 1))
    val tabState: StateFlow<AiTabState> = _tabState.asStateFlow()

    @Volatile private var accumulatedContent = ""
    @Volatile private var accumulatedReasoning = ""

    /**
     * 当前流式 assistant 消息的 id。
     * 用 id 而非索引定位，避免工具循环 / 用户删消息导致索引失效后写错消息。
     */
    @Volatile private var activeAssistantId: String? = null

    /**
     * 本轮回复对应的 assistant 占位消息 id。
     * 工具循环的多轮会复用同一条消息，因此这里跨轮次保留。
     */
    @Volatile private var accumulatedAssistantId: String? = null

    private var lastApiClient: AiApiClient? = null
    private var lastFTools: AiFileTools? = null
    private var lastTools: JSONArray? = null
    private var lastMaxToolRounds = 5
    private var lastCurrentRound = 0

    // ───────────────────── 初始化 ─────────────────────

    fun init(projectName: String, projectId: String, getContent: () -> String, getTitle: () -> String) {
        this.projectName = projectName
        this.projectId = projectId
        this.getCurrentContent = getContent
        this.getCurrentTitle = getTitle
        if (projectId.isEmpty()) return

        // 恢复持久化的模式
        val savedMode = prefsManager.getAiMode(projectId)
        _currentMode.value = if (savedMode == 1) AiMode.AGENT else AiMode.PLAN

        // 发现已有标签页（含旧数据迁移）
        val usedIds = chatHistoryManager.getUsedTabIds(projectId)
        // 恢复上次激活的标签页（若已不存在则回退到第一个）
        val savedTab = prefsManager.getActiveAiTab(projectId)
        val active = if (usedIds.contains(savedTab)) savedTab else usedIds.first()
        _tabState.value = AiTabState(usedIds.map { AiChatTab(it, "对话$it") }, active)
        _messages.value = chatHistoryManager.loadChatHistory(projectId, active)
        refreshEffectiveSettings()
    }

    /** 重新解析当前对话的注入设置（设置改动后调用） */
    fun refreshEffectiveSettings() {
        if (projectId.isEmpty()) return
        _effectiveSettings.value = ConversationSettingsManager.resolve(
            projectId, _tabState.value.activeTabId, prefsManager
        )
    }

    // ───────────────────── 标签页 ─────────────────────

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
        clearAccumulators()
        _pendingImages.value = emptyList()
        if (projectId.isNotEmpty()) {
            // 一个对话一个记录文件：空对话也立即创建，重启后对话不丢失、可被管理/备份
            chatHistoryManager.ensureTabFile(projectId, newId)
            prefsManager.saveActiveAiTab(projectId, newId)
        }
        refreshEffectiveSettings()
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
        clearAccumulators()
        _pendingImages.value = emptyList()
        if (projectId.isNotEmpty()) {
            chatHistoryManager.ensureTabFile(projectId, tabId) // 首次切换到的对话也确保有记录文件
            prefsManager.saveActiveAiTab(projectId, tabId)
        }
        refreshEffectiveSettings()
    }

    // ───────────────────── 图片附件 ─────────────────────

    /**
     * 添加一张图片到待发送附件。
     * @return null 表示成功，否则为错误提示文案
     */
    fun attachImage(bytes: ByteArray, sourceName: String): String? {
        if (projectId.isEmpty()) return "项目未初始化"
        val model = DeepSeekApi.normalizeModel(prefsManager.aiModel)
        if (!DeepSeekApi.supportsVision(model)) {
            return "当前模型「$model」不支持图像理解，请切换到 ${DeepSeekApi.MODEL_FLASH}"
        }
        val image = ChatImageStore.save(projectId, bytes, sourceName)
            ?: return "图片无法识别或超过 32MB 限制（支持 JPEG / PNG / GIF / WebP）"
        if (_pendingImages.value.size >= 20) return "单次最多添加 20 张图片"
        if (_pendingImages.value.none { it.fileName == image.fileName }) {
            _pendingImages.update { it + image }
        }
        return null
    }

    fun removePendingImage(fileName: String) {
        _pendingImages.update { list -> list.filterNot { it.fileName == fileName } }
    }

    fun clearPendingImages() {
        _pendingImages.value = emptyList()
    }

    /** 读取图片字节用于界面预览 */
    fun readImageBytes(image: ChatImage): ByteArray? =
        if (projectId.isEmpty()) null else ChatImageStore.readBytes(projectId, image)

    // ───────────────────── 发送 ─────────────────────

    /**
     * 发送用户消息。
     *
     * @return 未配置 API Key 等前置条件不满足时返回 false（界面可提示）
     */
    fun sendMessage(text: String): Boolean {
        val trimmed = text.trim()
        val images = _pendingImages.value
        if (trimmed.isEmpty() && images.isEmpty()) return false
        if (prefsManager.aiApiKey.isNullOrEmpty() || _isProcessing.value) return false

        _messages.update {
            it + AiChatMessage(role = "user", content = trimmed, images = images)
        }
        _pendingImages.value = emptyList()
        beginTurn()
        return true
    }

    /** 用户点击内联选项 */
    fun onUserChoice(choiceText: String): Boolean {
        if (_isProcessing.value) return false
        if (prefsManager.aiApiKey.isNullOrEmpty()) return false
        _messages.update { it + AiChatMessage(role = "user", content = choiceText) }
        beginTurn()
        return true
    }

    /**
     * 开始一轮请求。
     * 上下文注入按「模型调用轮次」计数，在每次请求开始前结算
     * （见 [settleInjectionBeforeCall]）。
     */
    private fun beginTurn() {
        _isProcessing.value = true
        _showResume.value = false
        turnProducedOutput = false
        turnFinalized = false
        // 立即落盘，避免未完成响应/直接关闭应用时丢失用户消息
        saveCurrentChat()
        performRequest(retry = false)
    }

    /**
     * 上下文注入：在**每次模型调用之前**结算。
     *
     * ## 计数口径：模型调用轮次
     *
     * 每发起一次模型请求算一轮 —— 包括**工具循环里的每一轮**。
     * 不是按用户发言次数，也不是按「助手完成回复」次数：
     * 一次用户发言若触发 3 轮工具调用，就累计 3 轮。
     *
     * 触发时机放在请求开始前，而不是等模型回复完：
     * 这样阈值一到，**本次请求**就带上注入内容，不必等到下一轮才生效。
     *
     * ## 注入位置：上下文末尾
     *
     * 注入作为一条 user 消息追加到历史末尾。
     * 若它前面紧跟用户发言，净化器会合并为 `用户发言\n注入内容` ——
     * 用户发言在前、注入在后，既落在上下文最后，
     * 又不会让模型把注入误当成用户指令。
     */
    private fun settleInjectionBeforeCall() {
        val settings = _effectiveSettings.value.context
        if (!settings.isUsable || projectId.isEmpty()) return

        val tabId = _tabState.value.activeTabId
        val rounds = prefsManager.getInjectionStepCounter(projectId, tabId)
        if (rounds < settings.everyNSteps) return

        val text = settings.text.trim()
        // 与末尾已有注入完全相同则不重复插入
        val last = _messages.value.lastOrNull()
        if (last != null && last.isInjection && last.content == text) {
            prefsManager.setInjectionStepCounter(projectId, tabId, 0)
            return
        }

        _messages.update {
            it + AiChatMessage(role = "user", content = text, isInjection = true)
        }
        prefsManager.setInjectionStepCounter(projectId, tabId, 0)
        AppLogger.d(TAG, "已注入上下文（每 ${settings.everyNSteps} 轮模型调用）：${text.take(40)}")
    }

    /** 累计一次模型调用轮次 */
    private fun countCallRound() {
        if (projectId.isEmpty()) return
        val tabId = _tabState.value.activeTabId
        prefsManager.setInjectionStepCounter(
            projectId, tabId,
            prefsManager.getInjectionStepCounter(projectId, tabId) + 1
        )
    }

    fun stopGeneration() {
        if (turnFinalized) return
        turnFinalized = true
        _isProcessing.value = false
        _showResume.value = turnProducedOutput
        // 收尾未完成的工具调用链，避免留下悬空 tool_calls 污染后续请求
        dropDanglingToolCalls()
        saveCurrentChat()
    }

    /**
     * 续传：从上次中断处继续生成。
     * 与旧实现的区别：不再粗暴丢弃末尾消息，而是依赖净化器处理不完整链路。
     */
    fun resumeFromInterruption() {
        if (_isProcessing.value) return
        if (prefsManager.aiApiKey.isNullOrEmpty()) return
        _isProcessing.value = true
        _showResume.value = false
        turnFinalized = false
        performRequest(retry = true)
    }

    // ───────────────────── 消息编辑 ─────────────────────

    fun editMessage(index: Int, newContent: String) {
        _messages.update { msgs ->
            msgs.toMutableList().also {
                if (index in it.indices) it[index] = it[index].copy(content = newContent)
            }
        }
        saveCurrentChat()
    }

    /** 从指定索引重发：截断该消息之后的所有对话，修改后重新发送 */
    fun resendFrom(index: Int, newContent: String) {
        if (index !in _messages.value.indices) return
        if (_isProcessing.value) return
        if (prefsManager.aiApiKey.isNullOrEmpty()) return

        _messages.update { msgs ->
            val truncated = msgs.take(index).toMutableList()
            truncated.add(msgs[index].copy(content = newContent))
            truncated
        }
        // 截断可能切断了工具调用链，收尾清理一次
        dropDanglingToolCalls()
        beginTurn()
    }

    /**
     * 删除单条消息。
     *
     * 删除后必须清理工具链：删掉一条 assistant 会让它后面的 tool 结果变成孤儿，
     * 删掉一条 tool 结果会让前面的 assistant.tool_calls 配不齐 —— 两者都会导致 400。
     */
    fun deleteMessage(index: Int) {
        _messages.update { msgs ->
            msgs.toMutableList().also { if (index in it.indices) it.removeAt(index) }
        }
        dropDanglingToolCalls()
        saveCurrentChat()
    }

    /** 按 id 删除消息（流式过程中的安全定位方式） */
    private fun deleteMessageById(id: String) {
        _messages.update { msgs -> msgs.filterNot { it.id == id } }
    }

    // ───────────────────── 请求 ─────────────────────

    private fun performRequest(retry: Boolean) {
        val apiKey = prefsManager.aiApiKey
        if (apiKey.isNullOrEmpty()) {
            // 未配置 API Key：复位处理状态，避免永久卡在「AI 思考中」
            _isProcessing.value = false
            _showResume.value = false
            return
        }
        val baseUrl = prefsManager.aiBaseUrl ?: DeepSeekApi.DEFAULT_BASE_URL
        val model = DeepSeekApi.normalizeModel(prefsManager.aiModel)
        val maxRounds = prefsManager.aiMaxToolRounds
        val fTools = AiFileTools(
            projectName, projectId, _currentMode.value,
            onFileChanged = { _fileChangeVersion.update { it + 1 } },
            fetchPlainText = prefsManager.aiFetchPlainText
        )
        val tools = fTools.getToolDefinitions()
        val apiClient = AiApiClient(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            reasoningEffort = prefsManager.aiReasoningEffort
        )
        lastApiClient = apiClient; lastFTools = fTools; lastTools = tools; lastMaxToolRounds = maxRounds
        if (!retry) lastCurrentRound = 0
        sendWithToolLoop(
            apiClient, fTools, tools, maxRounds,
            startRound = if (retry) lastCurrentRound else 0,
            // 续传时沿用上一轮已存在的 assistant 占位消息，避免产生重复气泡
            placeholderId = accumulatedAssistantId
        )
    }

    private fun sendWithToolLoop(
        apiClient: AiApiClient,
        fTools: AiFileTools,
        tools: JSONArray,
        maxRounds: Int,
        startRound: Int,
        placeholderId: String?
    ) {
        if (!_isProcessing.value) return

        // 每次模型调用记一轮，并在达到间隔时先结算上下文注入，
        // 使注入内容进入**本次**请求（而不是等下一轮）。
        countCallRound()
        settleInjectionBeforeCall()

        val (prefixMessages, cacheableCount) = buildPrefixMessages()
        val messagesJson = ApiMessageSanitizer.build(
            prefixMessages = prefixMessages,
            history = _messages.value,
            projectId = projectId,
            // 携带 tools 的请求必须回传历史 reasoning_content，否则 API 返回 400
            includeReasoning = tools.length() > 0,
            cacheablePrefixCount = cacheableCount,
            // 图像理解仅 deepseek-flash 支持；历史里的图片发给不支持的模型会返回 400
            allowImages = DeepSeekApi.supportsVision(prefsManager.aiModel ?: DeepSeekApi.MODEL_FLASH)
        )

        if (messagesJson.length() == 0) {
            // 没有任何可发送内容，放弃本次请求而不是发空请求
            _isProcessing.value = false
            return
        }

        // Token 统计：系统提示词部分
        if (projectId.isNotEmpty()) {
            val promptChars = prefixMessages.sumOf { it.content.length }
            tokenUsageManager.recordSystemPrompt(projectId, promptChars)
        }

        // 复用或新建 assistant 占位消息：工具循环的多轮都写进同一条，
        // 界面上表现为一个气泡 + 折叠的多条工具调用（与 DeepSeek 的行为一致）
        val assistantId = placeholderId ?: java.util.UUID.randomUUID().toString()
        accumulatedAssistantId = assistantId
        activeAssistantId = assistantId
        if (placeholderId == null) {
            _messages.update {
                it + AiChatMessage(id = assistantId, role = "assistant", content = "", reasoningContent = "")
            }
        } else {
            // 新一轮工具循环：清空正文，准备接收本轮回复
            _messages.update { msgs ->
                msgs.toMutableList().also { list ->
                    val idx = list.indexOfFirst { it.id == assistantId }
                    if (idx >= 0) list[idx] = list[idx].copy(content = "", reasoningContent = "")
                }
            }
        }
        accumulatedContent = ""
        accumulatedReasoning = ""

        var toolCallsHandled = false

        apiClient.sendChatRequestStream(
            messagesJson = messagesJson,
            tools = tools,
            onToken = { content, reasoning ->
                if (!_isProcessing.value) return@sendChatRequestStream
                if (content.isEmpty() && reasoning.isEmpty()) return@sendChatRequestStream
                accumulatedContent += content
                accumulatedReasoning += reasoning
                turnProducedOutput = true
                val snapshotContent = accumulatedContent
                val snapshotReasoning = accumulatedReasoning
                _messages.update { msgs ->
                    msgs.toMutableList().also { list ->
                        val idx = list.indexOfFirst { it.id == assistantId }
                        if (idx >= 0) {
                            list[idx] = list[idx].copy(
                                content = snapshotContent,
                                reasoningContent = snapshotReasoning
                            )
                        }
                    }
                }
            },
            onToolCalls = { tcs ->
                toolCallsHandled = true
                handleToolCalls(apiClient, fTools, tools, maxRounds, startRound, assistantId, tcs)
            },
            onComplete = { success, error ->
                if (toolCallsHandled) return@sendChatRequestStream
                // 已被 stopGeneration 结算过则不再重复处理（否则步数会重复累加）
                if (turnFinalized) return@sendChatRequestStream
                turnFinalized = true
                _isProcessing.value = false

                if (!success) {
                    _showResume.value = turnProducedOutput
                    if (error != null) appendErrorToAssistant(assistantId, error)
                    // 失败同样要清理残缺的工具链，否则下一次请求可能被 400 拒绝
                    dropDanglingToolCalls()
                } else {
                    // 调用轮次已在请求开始前计数（见 sendWithToolLoop）
                    finishTurn(assistantId) // 无工具调用的纯文本回复：本轮结束
                }
                saveCurrentChat()
            },
            onUsage = { usage ->
                if (projectId.isNotEmpty()) tokenUsageManager.recordFromUsage(projectId, usage)
            }
        )
    }

    /** 生成结束后收尾：丢弃空 assistant 占位、清理悬空工具链 */
    private fun finishTurn(assistantId: String) {
        val msg = _messages.value.firstOrNull { it.id == assistantId }
        if (msg != null && msg.content.isEmpty() && !msg.hasToolCalls() && !msg.hasImages()) {
            // 完全空的回复（例如模型只输出了思考）：保留思考内容供用户查看，否则删除
            if (msg.reasoningContent.isEmpty()) deleteMessageById(assistantId)
        }
        dropDanglingToolCalls()
        if (activeAssistantId == assistantId) activeAssistantId = null
        accumulatedAssistantId = null
    }

    /**
     * 给失败的消息追加错误提示。
     * 若该消息还是空的，就用错误信息占位，避免留下空 assistant 污染上下文。
     */
    private fun appendErrorToAssistant(assistantId: String, error: String) {
        _messages.update { msgs ->
            msgs.toMutableList().also { list ->
                val idx = list.indexOfFirst { it.id == assistantId }
                if (idx >= 0) {
                    val cur = list[idx]
                    list[idx] = if (cur.content.isEmpty() && !cur.hasToolCalls()) {
                        cur.copy(content = error)
                    } else {
                        cur.copy(content = cur.content + "\n\n" + error)
                    }
                } else {
                    list.add(AiChatMessage(role = "assistant", content = error, localOnly = true))
                }
            }
        }
    }

    /**
     * 组装固定前缀消息。
     *
     * 拼装逻辑全部交给 [SystemPromptComposer]（唯一真源），
     * 这里只负责把结果转成消息并取出缓存断点。
     *
     * 缓存断点由 composer 计算：基础提示词 / 引用文件 / 已定稿章节 进缓存前缀，
     * 用户追加、平台规则、当前编辑内容 都排在断点之外 ——
     * 所以用户改「系统提示词注入」或切换模式不会打碎定稿章节的缓存。
     */
    private fun buildPrefixMessages(): Pair<List<AiChatMessage>, Int> {
        val composed = SystemPromptComposer.compose(
            SystemPromptComposer.Input(
                projectId = projectId,
                projectName = projectName,
                mode = _currentMode.value,
                injection = _effectiveSettings.value.systemPrompt,
                currentContent = getCurrentContent?.invoke() ?: "",
                currentTitle = getCurrentTitle?.invoke() ?: ""
            )
        )

        val messages = mutableListOf<AiChatMessage>()
        var cacheableCount = 0
        composed.parts.forEachIndexed { index, part ->
            if (!part.isUsable) return@forEachIndexed
            messages.add(AiChatMessage(role = part.role, content = part.content))
            if (index < composed.cacheableCount) cacheableCount++
        }
        return messages to cacheableCount
    }

    /**
     * 最终发给模型的系统提示词（只含 system 部分，供设置界面预览）。
     * 与实际请求走同一套拼装，因此「看到的 = 实际发的」。
     */
    fun buildPromptPreview(): String = SystemPromptComposer.preview(
        SystemPromptComposer.Input(
            projectId = projectId,
            projectName = projectName,
            mode = _currentMode.value,
            injection = _effectiveSettings.value.systemPrompt
        )
    )

    // ───────────────────── 模式切换 ─────────────────────

    fun switchToAgentMode() {
        _currentMode.value = AiMode.AGENT
        if (projectId.isNotEmpty()) prefsManager.saveAiMode(projectId, 1)
        // 把大纲目录下的设定文件登记为「引用文件」。
        //
        // 旧实现是把文件内容**快照**追加进 additions：每次切模式都再灌一遍，
        // 同一份大纲会在提示词里重复出现；而且大纲更新后提示词不会跟着变。
        // 现在只登记路径，内容在每次拼装时现读（见 SystemPromptComposer 第 2 层）。
        val added = registerOutlineAsContextFiles()
        _messages.update {
            it + AiChatMessage(
                role = "system",
                content = buildString {
                    append("已切换到 Agent 写作模式，现在可以写入小说主体了。")
                    if (added > 0) {
                        append("已把大纲目录的 $added 个设定文件加入系统提示词引用文件。")
                    }
                }
            )
        }
    }

    fun switchToPlanMode() {
        _currentMode.value = AiMode.PLAN
        if (projectId.isNotEmpty()) prefsManager.saveAiMode(projectId, 0)
        _messages.update {
            it + AiChatMessage(
                role = "system",
                content = "已切换到 Plan 计划模式，只能修改大纲目录。如需调整计划请继续。"
            )
        }
    }

    /**
     * 把大纲目录下的 .txt 设定文件登记为引用文件（幂等）。
     * @return 本次新登记的文件数
     */
    private fun registerOutlineAsContextFiles(): Int {
        if (projectId.isEmpty() || projectName.isEmpty()) return 0
        return try {
            val outlineDir = ProjectStorageManager.getProjectSubDir(projectName, "大纲")
            if (!outlineDir.exists()) return 0
            val txtFiles = outlineDir.listFiles()
                ?.filter { it.extension.equals("txt", true) && it.isFile }
                ?: return 0
            var added = 0
            txtFiles.sortedBy { it.name }.forEach { file ->
                // addContextFile 自带去重，重复切换模式不会累积
                if (systemPromptManager.addContextFile(projectId, "大纲/${file.name}")) added++
            }
            if (added > 0) _promptConfigVersion.update { it + 1 }
            added
        } catch (e: Exception) {
            AppLogger.w(TAG, "登记大纲引用文件失败: ${e.message}")
            0
        }
    }

    // ───────────────────── 工具调用 ─────────────────────

    private fun handleToolCalls(
        apiClient: AiApiClient,
        fTools: AiFileTools,
        tools: JSONArray,
        maxRounds: Int,
        currentRound: Int,
        assistantIdx: String,
        toolCalls: List<ToolCall>
    ) {
        // 把工具调用挂到 assistant 消息上（按 id 定位，避免索引失效）
        _messages.update { msgs ->
            msgs.toMutableList().also { list ->
                val i = list.indexOfFirst { it.id == assistantIdx }
                if (i >= 0) list[i] = list[i].copy(toolCalls = toolCalls)
            }
        }
        if (!_isProcessing.value) return

        // 顺序执行工具并把结果追加为 tool 消息
        val results = toolCalls.map { tc ->
            val r = try {
                fTools.executeToolCall(tc.function.name, tc.function.arguments)
            } catch (e: Exception) {
                "工具调用错误: ${e.message}"
            }
            if (projectId.isNotEmpty()) tokenUsageManager.recordToolCall(projectId, r.length)
            AiChatMessage(role = "tool", content = r, toolCallId = tc.id)
        }
        _messages.update { it + results }
        turnProducedOutput = true

        if (!_isProcessing.value) return
        val nextRound = currentRound + 1
        if (maxRounds < 0 || nextRound < maxRounds) {
            lastCurrentRound = nextRound
            viewModelScope.launch(Dispatchers.IO) {
                sendWithToolLoop(
                    apiClient, fTools, tools, maxRounds, nextRound,
                    placeholderId = assistantIdx // 复用同一条 assistant 消息
                )
            }
        } else {
            _messages.update {
                it + AiChatMessage(
                    role = "system",
                    content = "已达到最大工具调用轮次（$maxRounds）"
                )
            }
            // 调用轮次已在每次请求开始前计数（见 sendWithToolLoop），此处无需再补
            _isProcessing.value = false
            dropDanglingToolCalls()
            saveCurrentChat()
        }
    }

    /**
     * 清理悬空的工具调用链。
     *
     * 场景：模型已发起 tool_calls，但生成被中断 / 工具结果未能写入。
     * 这类消息留在历史里会导致后续请求 400 —— 净化器虽能兜底，
     * 但这里主动清掉可以让历史更干净、也让 UI 不显示无意义的空工具轮次。
     */
    private fun dropDanglingToolCalls() {
        _messages.update { msgs ->
            val result = mutableListOf<AiChatMessage>()
            var i = 0
            while (i < msgs.size) {
                val msg = msgs[i]
                val pending = msg.toolCalls
                if (msg.isAssistant() && !pending.isNullOrEmpty()) {
                    val needed = pending.map { it.id }.toSet()
                    val available = mutableSetOf<String>()
                    var j = i + 1
                    while (j < msgs.size && msgs[j].isTool()) {
                        msgs[j].toolCallId?.let { available.add(it) }
                        j++
                    }
                    if (available.containsAll(needed)) {
                        // 链路完整：整段原样保留
                        for (k in i until j) result.add(msgs[k])
                    } else {
                        // 链路残缺：丢弃配不上的 tool 结果，保留 assistant 文本
                        if (msg.content.isNotEmpty()) {
                            result.add(msg.copy(toolCalls = null))
                        }
                    }
                    i = j
                } else {
                    result.add(msg)
                    i++
                }
            }
            result
        }
    }

    // ───────────────────── 持久化 ─────────────────────

    private fun clearAccumulators() {
        accumulatedContent = ""
        accumulatedReasoning = ""
        activeAssistantId = null
        accumulatedAssistantId = null
    }

    private fun saveCurrentChat() {
        if (_messages.value.isNotEmpty() && projectId.isNotEmpty()) {
            chatHistoryManager.saveChatHistory(projectId, _tabState.value.activeTabId, _messages.value)
        }
    }

    /** 供 UI 在离开编辑器/关闭窗口时强制保存当前对话 */
    fun saveNow() {
        saveCurrentChat()
    }

    override fun onCleared() {
        super.onCleared()
        saveCurrentChat()
    }
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
