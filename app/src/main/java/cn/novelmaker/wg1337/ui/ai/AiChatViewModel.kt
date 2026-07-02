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

    companion object { private const val TAG = "AiChatVM" }

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
            _messages.value = chatHistoryManager.loadChatHistory(projectId)
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
        performRequest(retry = true)
    }

    fun clearChat() {
        _isProcessing.value = false; accumulatedContent = ""; accumulatedReasoning = ""
        _messages.value = emptyList(); _showResume.value = false
        if (projectId.isNotEmpty()) chatHistoryManager.deleteChatHistory(projectId)
    }

    fun editMessage(index: Int, newContent: String) {
        _messages.update { msgs -> msgs.toMutableList().also { if (index in it.indices) it[index] = it[index].copy(content = newContent) } }
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
    }

    private fun performRequest(retry: Boolean) {
        val apiKey = prefsManager.aiApiKey ?: return
        val baseUrl = prefsManager.aiBaseUrl ?: "https://api.deepseek.com"
        val model = prefsManager.aiModel ?: "deepseek-chat"
        val useStream = prefsManager.aiStreamEnabled
        val maxRounds = prefsManager.aiMaxToolRounds
        val fTools = AiFileTools(projectName, projectId, _currentMode.value)
        val tools = fTools.getToolDefinitions()
        val apiClient = AiApiClient(apiKey = apiKey, baseUrl = baseUrl, model = model, thinkingEnabled = true)
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

        // 4. 历史对话（user/assistant/tool，排除 choice 角色——那是 UI 专用）
        apiMessages.addAll(_messages.value.filter { it.role != "system" && it.role != "choice" })

        // 构建 JSON：仅前缀部分加 cache_control
        val msgsJson = JSONArray()
        for (i in apiMessages.indices) {
            val cache = i < 2  // 前 2 条是固定前缀
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
        }
        return sb.toString()
    }

    private fun buildFinalizedChapters(): String {
        if (projectId.isEmpty()) return ""
        val files = finalizedManager.getFinalizedFiles(projectId)
        if (files.isEmpty()) return ""
        val sb = StringBuilder("【已定稿章节】\n")
        val projectDir = ProjectStorageManager.getProjectDir(projectName)
        files.sortedBy { ProjectStorageManager.extractChapterNumber(it) }.forEach { path ->
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
        if (_messages.value.isNotEmpty() && projectId.isNotEmpty()) chatHistoryManager.saveChatHistory(projectId, _messages.value)
    }

    override fun onCleared() { super.onCleared(); saveCurrentChat() }
}
