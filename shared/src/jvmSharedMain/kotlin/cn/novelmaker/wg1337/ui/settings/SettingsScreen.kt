package cn.novelmaker.wg1337.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.novelmaker.wg1337.Platform
import cn.novelmaker.wg1337.ui.ai.AiChatHistoryManager
import cn.novelmaker.wg1337.ui.ai.AiChatViewModel
import cn.novelmaker.wg1337.ui.ai.ApiProfileManager
import cn.novelmaker.wg1337.ui.ai.ConversationSettingsManager
import cn.novelmaker.wg1337.ui.ai.DeepSeekApi
import cn.novelmaker.wg1337.ui.ai.DefaultPrompt
import cn.novelmaker.wg1337.ui.ai.SystemPromptComposer
import cn.novelmaker.wg1337.ui.ai.SystemPromptManager
import cn.novelmaker.wg1337.ui.ai.TokenUsageManager
import cn.novelmaker.wg1337.ui.ai.listProjectTextFiles
import cn.novelmaker.wg1337.data.repository.ProjectRepository
import cn.novelmaker.wg1337.utils.PreferencesManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val prefsManager = remember { PreferencesManager() }
    var themeMode by remember { mutableIntStateOf(prefsManager.themeMode) }
    var fontSize by remember { mutableIntStateOf(prefsManager.editorFontSize.toInt()) }
    var lineSpacing by remember { mutableFloatStateOf(prefsManager.editorLineSpacing) }
    var aiStream by remember { mutableStateOf(prefsManager.aiStreamEnabled) }
    // API 配置列表变化后刷新「当前配置」卡片
    var profileRefreshKey by remember { mutableIntStateOf(0) }
    var showApiProfileDialog by remember { mutableStateOf(false) }
    var aiReasoningEffort by remember { mutableStateOf(prefsManager.aiReasoningEffort) }
    var aiFetchPlainText by remember { mutableStateOf(prefsManager.aiFetchPlainText) }
    var aiMaxRounds by remember { mutableStateOf(prefsManager.aiMaxToolRounds.toString()) }
    // 注入设置：全局默认值
    var injCtxEnabled by remember { mutableStateOf(prefsManager.injectionContextEnabled) }
    var injCtxSteps by remember {
        mutableStateOf(prefsManager.injectionContextSteps.toString())
    }
    var injCtxText by remember { mutableStateOf(prefsManager.injectionContextText.orEmpty()) }
    var injSysEnabled by remember { mutableStateOf(prefsManager.injectionSystemPromptEnabled) }
    var injSysText by remember { mutableStateOf(prefsManager.injectionSystemPromptText.orEmpty()) }
    var showChatHistoryDialog by remember { mutableStateOf(false) }
    var showTokenStatsDialog by remember { mutableStateOf(false) }
    var showPromptViewerDialog by remember { mutableStateOf(false) }
    var showBackupScreen by remember { mutableStateOf(false) }
    var aiApiKeyVisible by remember { mutableStateOf(false) }

    if (showBackupScreen) {
        BackupScreen(onBack = { showBackupScreen = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 主题 ──
            SectionTitle("界面主题")
            ThemeCard(label = "淡色模式", desc = "清爽明亮的淡色界面", selected = themeMode == 0, onClick = {
                themeMode = 0; prefsManager.themeMode = 0
                Platform.applyThemeNightMode(0)
            })
            ThemeCard(label = "深色模式", desc = "护眼省电的暗色界面", selected = themeMode == 2, onClick = {
                themeMode = 2; prefsManager.themeMode = 2
                Platform.applyThemeNightMode(2)
            })

            Spacer(Modifier.height(8.dp))
            SectionTitle("关于")
            Card(
                onClick = {
                    Platform.openUrl("https://github.com/wg-1337/NovelMaker")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("NovelMaker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("版本 ${Platform.currentVersionName()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("点击访问项目仓库", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("备份与恢复")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("备份项目文件、AI 对话记录和 Token 统计，AES-256 加密保护。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { showBackupScreen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("进入备份管理") }
                }
            }

            // ── 编辑器设置 ──
            Spacer(Modifier.height(8.dp))
            SectionTitle("编辑器设置")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("字体大小：$fontSize", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { fontSize = it.toInt(); prefsManager.editorFontSize = it },
                        valueRange = 10f..32f,
                        steps = 21
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("行间距：${"%.1f".format(lineSpacing)}", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = lineSpacing,
                        onValueChange = { lineSpacing = it; prefsManager.editorLineSpacing = it },
                        valueRange = 1f..5f
                    )
                    HorizontalDivider()
                    var maxFinalized by remember { mutableStateOf(prefsManager.maxFinalizedChapters.toString()) }
                    var bulkEvict by remember { mutableStateOf(prefsManager.bulkEvictChapters.toString()) }
                    OutlinedTextField(
                        value = maxFinalized,
                        onValueChange = {
                            maxFinalized = it.filter { c -> c.isDigit() }
                            prefsManager.maxFinalizedChapters = maxFinalized.toIntOrNull() ?: 0
                        },
                        label = { Text("定稿上限") }, placeholder = { Text("默认 50，0 = 无限制") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bulkEvict,
                        onValueChange = {
                            bulkEvict = it.filter { c -> c.isDigit() }
                            val v = bulkEvict.toIntOrNull() ?: 20
                            prefsManager.bulkEvictChapters = v.coerceAtLeast(1)
                        },
                        label = { Text("批量淘汰") }, placeholder = { Text("默认 20，超限时一次移除最早 N 章") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── AI 设置 ──
            Spacer(Modifier.height(8.dp))
            SectionTitle("AI 写作设置")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // ── 当前 API 配置（多配置切换） ──
                    val activeProfile = remember(profileRefreshKey) {
                        ApiProfileManager.getActiveOrDefault()
                    }
                    val profileCount = remember(profileRefreshKey) {
                        ApiProfileManager.loadProfiles().size
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("当前 API 配置", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        activeProfile.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        activeProfile.summary,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (activeProfile.apiKey.isBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Warning, null, Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                "该配置尚未填写 API Key",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                                TextButton(onClick = { showApiProfileDialog = true }) {
                                    Text("管理（$profileCount）", fontSize = 12.sp)
                                }
                            }
                            Text(
                                if (DeepSeekApi.supportsVision(activeProfile.model)) {
                                    "${DeepSeekApi.MODEL_FLASH} 支持图像理解，可在编辑器中发送图片。"
                                } else {
                                    "当前模型不支持图像理解；需要看图请切到 ${DeepSeekApi.MODEL_FLASH}。"
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("流式回答", style = MaterialTheme.typography.bodyLarge)
                            Text("逐字显示AI回复内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = aiStream, onCheckedChange = { aiStream = it; prefsManager.aiStreamEnabled = it })
                    }
                    // 思考强度（全局设置，编辑器快捷设置中同步调整）
                    Column {
                        Text("思考强度", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "控制思考模式开关与强度，全局生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeepSeekApi.EFFORT_OPTIONS.forEach { (value, label) ->
                                FilterChip(
                                    selected = aiReasoningEffort == value,
                                    onClick = {
                                        aiReasoningEffort = value
                                        prefsManager.aiReasoningEffort = value
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Text(
                            "「不思考」响应更快、更省 Token；思考模式下 temperature 等采样参数不生效。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 页面纯文本模式
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("页面纯文本模式", style = MaterialTheme.typography.bodyLarge)
                            Text("AI 打开网页时提取正文纯文本，关闭则返回原始 HTML", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = aiFetchPlainText, onCheckedChange = { aiFetchPlainText = it; prefsManager.aiFetchPlainText = it })
                    }
                    // 工具调用轮次
                    OutlinedTextField(
                        value = aiMaxRounds,
                        onValueChange = {
                            val filtered = it.filter { c -> c.isDigit() || c == '-' }
                            // 仅允许 "-" 开头或纯数字
                            if (filtered == "-" || filtered.isEmpty() || filtered.all { c -> c.isDigit() } || (filtered.startsWith("-") && filtered.drop(1).all { c -> c.isDigit() })) {
                                aiMaxRounds = filtered
                                prefsManager.aiMaxToolRounds = filtered.toIntOrNull() ?: 5
                            }
                        },
                        label = { Text("工具调用轮次") },
                        placeholder = { Text("-1 = 无限制，默认 5") },
                        supportingText = { Text("AI 一轮对话中调用工具的最大次数，如搜索、读写文件") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    // 聊天记录管理
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("聊天记录管理", style = MaterialTheme.typography.bodyLarge)
                            Text("查看、删除各项目的对话记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showChatHistoryDialog = true }) { Text("管理") }
                    }
                    // Token 用量统计
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Token 用量统计", style = MaterialTheme.typography.bodyLarge)
                            Text("按项目查看 Token 使用情况", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showTokenStatsDialog = true }) { Text("查看") }
                    }
                    // 系统提示词管理
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("系统提示词", style = MaterialTheme.typography.bodyLarge)
                            Text("按项目查看并修改系统提示词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showPromptViewerDialog = true }) { Text("管理") }
                    }
                }
            }

            // ── 上下文注入 / 系统提示词注入（全局默认值） ──
            Spacer(Modifier.height(8.dp))
            SectionTitle("上下文注入与提示词注入")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "这里设置的是全局默认值，新项目会沿用。单个项目/对话可在编辑器右上角的「注入设置」中单独覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    // 系统提示词注入
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("系统提示词注入", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "追加到系统提示词末尾，随时可改、立即生效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = injSysEnabled,
                            onCheckedChange = {
                                injSysEnabled = it
                                prefsManager.injectionSystemPromptEnabled = it
                            }
                        )
                    }
                    OutlinedTextField(
                        value = injSysText,
                        onValueChange = { injSysText = it; prefsManager.injectionSystemPromptText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                        enabled = injSysEnabled,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("自定义系统提示词内容") },
                        placeholder = { Text("例如：本作采用第一人称叙述，避免使用现代网络用语") }
                    )

                    HorizontalDivider()

                    // 上下文注入
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("上下文注入", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "每 N 步模型操作后，自动把预设内容注入到上下文末尾",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = injCtxEnabled,
                            onCheckedChange = {
                                injCtxEnabled = it
                                prefsManager.injectionContextEnabled = it
                            }
                        )
                    }
                    OutlinedTextField(
                        value = injCtxSteps,
                        onValueChange = { v ->
                            val filtered = v.filter { it.isDigit() }.take(3)
                            injCtxSteps = filtered
                            prefsManager.injectionContextSteps =
                                filtered.toIntOrNull() ?: ConversationSettingsManager.DEFAULT_INJECTION_STEPS
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = injCtxEnabled,
                        singleLine = true,
                        label = { Text("注入间隔（步）") },
                        placeholder = { Text("默认 ${ConversationSettingsManager.DEFAULT_INJECTION_STEPS}") },
                        supportingText = { Text("每一步 = 模型的一次回复（含一次工具调用轮次）", fontSize = 10.sp) }
                    )
                    OutlinedTextField(
                        value = injCtxText,
                        onValueChange = { injCtxText = it; prefsManager.injectionContextText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        enabled = injCtxEnabled,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("注入内容") },
                        placeholder = { Text("例如：提醒：主角目前在第 3 卷，已获得断剑，尚未与女主相遇。") },
                        supportingText = {
                            Text(
                                "注入内容会排在你的发言之前，避免模型把你的指令与注入内容混淆",
                                fontSize = 10.sp
                            )
                        }
                    )
                }
            }
        }

        // API 配置管理对话框
        if (showApiProfileDialog) {
            ApiProfileDialog(onDismiss = {
                showApiProfileDialog = false
                profileRefreshKey++
            })
        }
        // 聊天记录管理对话框
        if (showChatHistoryDialog) {
            ChatHistoryDialog(onDismiss = { showChatHistoryDialog = false })
        }
        // Token 用量统计对话框
        if (showTokenStatsDialog) {
            TokenStatsDialog(onDismiss = { showTokenStatsDialog = false })
        }
        // 系统提示词浏览对话框
        if (showPromptViewerDialog) {
            PromptViewerDialog(onDismiss = { showPromptViewerDialog = false })
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ThemeCard(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) CardDefaults.outlinedCardBorder() else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── 聊天记录管理对话框 ──
@Composable
private fun ChatHistoryDialog(onDismiss: () -> Unit) {
    val manager = remember { cn.novelmaker.wg1337.ui.ai.AiChatHistoryManager() }
    val projectRepo = remember { ProjectRepository() }
    val projects = remember { manager.getProjectsWithChatHistory() }
    // 构建 projectId → projectName 的映射
    val nameMap = remember(projects) {
        val allProjects = projectRepo.getAllProjects()
        projects.associateWith { pid -> allProjects.find { it.id == pid }?.name ?: pid }
    }
    // 每个项目的对话数量
    val tabCountMap = remember(projects) {
        projects.associateWith { pid -> manager.getUsedTabIds(pid).size }
    }
    var expandedProject by remember { mutableStateOf<String?>(null) }
    var confirmProject by remember { mutableStateOf<String?>(null) }
    var confirmTab by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // 展开项目的各对话信息（对话N + 消息数），仅展开时计算
    val tabInfo = remember(expandedProject) {
        expandedProject?.let { pid ->
            manager.getUsedTabIds(pid).map { tabId -> Triple(tabId, "对话$tabId", manager.getChatHistoryCount(pid, tabId)) }
        } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("聊天记录管理") },
        text = {
            if (projects.isEmpty()) {
                Text("暂无对话记录")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("每个项目最多 5 个对话，点项目名展开可查看并逐个删除。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    projects.forEach { pid ->
                        val isExpanded = expandedProject == pid
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedProject = if (isExpanded) null else pid }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(nameMap[pid] ?: pid, style = MaterialTheme.typography.bodyMedium)
                                    Text("${tabCountMap[pid] ?: 1} 个对话", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { confirmProject = pid }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, "删除全部对话", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (isExpanded) {
                                tabInfo.forEach { (tabId, tabName, count) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("$tabName（$count 条）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { confirmTab = pid to tabId }, Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Close, "删除此对话", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    // 删除整个项目（全部对话）
    confirmProject?.let { pid ->
        val projectName = nameMap[pid] ?: pid
        AlertDialog(
            onDismissRequest = { confirmProject = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「$projectName」的全部对话记录吗？\n将删除该项目所有标签页的对话，此操作不可恢复。") },
            confirmButton = { TextButton(onClick = { manager.deleteChatHistory(pid); confirmProject = null; onDismiss() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmProject = null }) { Text("取消") } }
        )
    }
    // 删除单个对话
    confirmTab?.let { (pid, tabId) ->
        val projectName = nameMap[pid] ?: pid
        AlertDialog(
            onDismissRequest = { confirmTab = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「$projectName」的「对话$tabId」吗？此操作不可恢复。") },
            confirmButton = { TextButton(onClick = { manager.deleteChatHistory(pid, tabId); confirmTab = null; onDismiss() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmTab = null }) { Text("取消") } }
        )
    }
}

// ── Token 用量统计对话框 ──
@Composable
private fun TokenStatsDialog(onDismiss: () -> Unit) {
    val tokenManager = remember { TokenUsageManager() }
    val projectRepo = remember { ProjectRepository() }
    val projectIds = remember { tokenManager.getProjectsWithStats() }
    val nameMap = remember(projectIds) {
        val all = projectRepo.getAllProjects()
        projectIds.associateWith { pid -> all.find { it.id == pid }?.name ?: pid }
    }
    var selectedProject by remember {
        mutableStateOf(if (projectIds.isNotEmpty()) projectIds.first() else "")
    }

    val stats = if (selectedProject.isNotEmpty()) {
        remember(selectedProject) { tokenManager.getStats(selectedProject) }
    } else TokenUsageManager.TokenStats()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token 用量统计") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (projectIds.isEmpty()) {
                    Text("暂无统计数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // 项目选择器
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(nameMap[selectedProject] ?: selectedProject.ifEmpty { "选择项目" })
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            projectIds.forEach { pid ->
                                DropdownMenuItem(
                                    text = { Text(nameMap[pid] ?: pid) },
                                    onClick = { selectedProject = pid; expanded = false }
                                )
                            }
                        }
                    }

                    // 进度条
                    val maxTokens = stats.maxContext
                    val sysPct = if (maxTokens > 0) stats.systemPromptTokens.toFloat() / maxTokens else 0f
                    val chatPct = if (maxTokens > 0) stats.chatTokens.toFloat() / maxTokens else 0f
                    val toolPct = if (maxTokens > 0) stats.toolCallTokens.toFloat() / maxTokens else 0f

                    Text("最大上下文使用量 (DeepSeek 1M)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Segmented progress bar
                    Box(
                        modifier = Modifier.fillMaxWidth().height(24.dp)
                    ) {
                        // Background
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(color = Color(0xFFE0E0E0))
                            val w = size.width
                            var offset = 0f
                            if (sysPct > 0) {
                                drawRect(color = Color(0xFF7B3E6F), topLeft = Offset(offset, 0f), size = androidx.compose.ui.geometry.Size(w * sysPct, size.height))
                                offset += w * sysPct
                            }
                            if (chatPct > 0) {
                                drawRect(color = Color(0xFF42A5F5), topLeft = Offset(offset, 0f), size = androidx.compose.ui.geometry.Size(w * chatPct, size.height))
                                offset += w * chatPct
                            }
                            if (toolPct > 0) {
                                drawRect(color = Color(0xFF9E9E9E), topLeft = Offset(offset, 0f), size = androidx.compose.ui.geometry.Size(w * toolPct, size.height))
                            }
                        }
                    }

                    // 百分比标注
                    val totalPct = stats.usagePercent
                    Text("${"%.1f".format(totalPct)}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End))

                    // 分段标注
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(Color(0xFF7B3E6F)) }
                                Spacer(Modifier.width(6.dp))
                                Text("系统提示词", fontSize = 12.sp)
                            }
                            Text("${formatNumber(stats.systemPromptTokens)} tokens (${"%.1f".format(sysPct * 100)}%)", fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(Color(0xFF42A5F5)) }
                                Spacer(Modifier.width(6.dp))
                                Text("AI 对话", fontSize = 12.sp)
                            }
                            Text("${formatNumber(stats.chatTokens)} tokens (${"%.1f".format(chatPct * 100)}%)", fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(Color(0xFF9E9E9E)) }
                                Spacer(Modifier.width(6.dp))
                                Text("工具调用", fontSize = 12.sp)
                            }
                            Text("${formatNumber(stats.toolCallTokens)} tokens (${"%.1f".format(toolPct * 100)}%)", fontSize = 12.sp)
                        }
                    }

                    // 缓存命中率（来自服务端 usage 的真实统计）
                    val cacheTotal = stats.cacheHitTokens + stats.cacheMissTokens
                    if (cacheTotal > 0) {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("缓存命中率", fontSize = 12.sp)
                            Text(
                                "${"%.1f".format(stats.cacheHitRate * 100)}%" +
                                    "（命中 ${formatNumber(stats.cacheHitTokens)} / 未命中 ${formatNumber(stats.cacheMissTokens)}）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ── 系统提示词管理器（定义层 + 真实预览） ──
@Composable
private fun PromptViewerDialog(onDismiss: () -> Unit) {
    val promptManager = remember { SystemPromptManager() }
    val chatManager = remember { AiChatHistoryManager() }
    val projectRepo = remember { ProjectRepository() }
    val prefs = remember { PreferencesManager() }
    val projectIds = remember { chatManager.getProjectsWithChatHistory() }
    var selectedProject by remember {
        mutableStateOf(if (projectIds.isNotEmpty()) projectIds.first() else "")
    }

    // 显示名：项目名（找不到时回落到 id）
    val nameMap = remember(projectIds) {
        val all = projectRepo.getAllProjects()
        projectIds.associateWith { pid -> all.find { it.id == pid }?.name ?: pid }
    }
    val projectName = nameMap[selectedProject] ?: selectedProject

    var tab by remember { mutableIntStateOf(0) }
    var baseText by remember(selectedProject) {
        mutableStateOf(
            if (selectedProject.isNotEmpty()) promptManager.getBasePrompt(selectedProject) else ""
        )
    }
    var savedHint by remember { mutableStateOf<String?>(null) }

    // 引用文件（路径引用，内容每次拼装时现读）
    var contextFiles by remember(selectedProject) {
        mutableStateOf(
            if (selectedProject.isNotEmpty()) promptManager.getContextFiles(selectedProject)
            else emptyList()
        )
    }
    // 可加入提示词的项目文本文件
    val availableFiles = remember(selectedProject, projectName) {
        if (selectedProject.isEmpty() || projectName.isEmpty()) emptyList()
        else projectTextFiles(projectName)
    }

    // 真实拼装结果（与实际请求同一套逻辑）
    val composed = if (selectedProject.isNotEmpty()) {
        remember(selectedProject, projectName, contextFiles, savedHint, tab) {
            SystemPromptComposer.compose(
                SystemPromptComposer.Input(
                    projectId = selectedProject,
                    projectName = projectName,
                    mode = if (prefs.getAiMode(selectedProject) == 1) {
                        AiChatViewModel.AiMode.AGENT
                    } else {
                        AiChatViewModel.AiMode.PLAN
                    }
                ),
                includeDynamic = false
            )
        }
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统提示词", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (projectIds.isEmpty()) {
                    Text("暂无项目数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // ── 项目选择 ──
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(projectName.ifEmpty { "选择项目" })
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            projectIds.forEach { pid ->
                                DropdownMenuItem(
                                    text = { Text(nameMap[pid] ?: pid) },
                                    onClick = {
                                        selectedProject = pid
                                        expanded = false
                                        savedHint = null
                                        tab = 0
                                    }
                                )
                            }
                        }
                    }

                    // ── 层级说明：这几层共同决定实际发给模型的提示词 ──
                    Text(
                        "提示词由下列各层按顺序拼装而成，改哪层就动哪层，不会互相覆盖。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("正文", fontSize = 12.sp) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("引用文件", fontSize = 12.sp) })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("最终效果", fontSize = 12.sp) })
                    }

                    when (tab) {
                        // ── 第 1 层：基础提示词 ──
                        0 -> {
                            Text(
                                if (promptManager.isUsingDefaultBasePrompt(selectedProject)) {
                                    "当前为基础提示词：内置默认模板"
                                } else {
                                    "当前为基础提示词：项目自定义"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedTextField(
                                value = baseText,
                                onValueChange = { baseText = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                                textStyle = MaterialTheme.typography.bodySmall,
                                label = { Text("基础提示词正文") },
                                supportingText = {
                                    Text(
                                        "这一层会进入缓存前缀，改动越少越省 Token。留空保存 = 恢复内置模板。",
                                        fontSize = 10.sp
                                    )
                                }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    baseText = DefaultPrompt.getDefaultPrompt()
                                }) { Text("载入内置模板", fontSize = 12.sp) }
                                OutlinedButton(onClick = {
                                    baseText = ""
                                }) { Text("清空（用内置）", fontSize = 12.sp) }
                            }
                        }

                        // ── 第 2 层：引用文件 ──
                        1 -> {
                            Text(
                                "勾选的项目文件会被读入系统提示词（每次请求现读，文件改了自动更新）。" +
                                    "单文件上限 ${SystemPromptManager.MAX_CONTEXT_FILE_CHARS} 字符，" +
                                    "最多 ${SystemPromptManager.MAX_CONTEXT_FILES} 个。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (availableFiles.isEmpty()) {
                                Text(
                                    "项目里还没有可引用的文本文件。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                availableFiles.forEach { rel ->
                                    val checked = contextFiles.contains(rel)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                // 先落盘再更新 UI 状态，保证预览与磁盘一致
                                                if (checked) {
                                                    promptManager.removeContextFile(selectedProject, rel)
                                                } else {
                                                    if (!promptManager.addContextFile(selectedProject, rel)) {
                                                        savedHint = "最多引用 ${SystemPromptManager.MAX_CONTEXT_FILES} 个文件"
                                                        return@clickable
                                                    }
                                                }
                                                contextFiles = promptManager.getContextFiles(selectedProject)
                                                savedHint = "引用文件已更新"
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { v ->
                                                if (v) promptManager.addContextFile(selectedProject, rel)
                                                else promptManager.removeContextFile(selectedProject, rel)
                                                contextFiles = promptManager.getContextFiles(selectedProject)
                                                savedHint = "引用文件已更新"
                                            }
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(rel, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    }
                                }
                                if (contextFiles.isNotEmpty()) {
                                    TextButton(onClick = {
                                        promptManager.clearContextFiles(selectedProject)
                                        contextFiles = emptyList()
                                        savedHint = "已清空引用文件"
                                    }) { Text("全部取消", fontSize = 12.sp) }
                                }
                            }
                        }

                        // ── 最终效果：真实拼装结果 ──
                        else -> {
                            Text(
                                "以下是**实际会发给模型**的 system 消息（不含对话历史）。" +
                                    "标注了每段的来源与是否进入缓存前缀。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            composed?.let { c ->
                                Text(
                                    "合计 ${c.totalChars} 字符 · 缓存前缀 ${c.cacheableCount} 段",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                c.parts.forEach { part ->
                                    if (part.content.isBlank()) return@forEach
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    if (part.cacheable) {
                                                        Icons.Default.Bolt
                                                    } else {
                                                        Icons.Default.Edit
                                                    },
                                                    null,
                                                    Modifier.size(13.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    part.label +
                                                        if (part.cacheable) "（进缓存）" else "（不进缓存）",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(
                                                part.content,
                                                fontSize = 10.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                modifier = Modifier
                                                    .heightIn(max = 160.dp)
                                                    .verticalScroll(rememberScrollState())
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    savedHint?.let { hint ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle, null, Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (tab == 0 && selectedProject.isNotEmpty()) {
                Button(onClick = {
                    promptManager.setBasePrompt(selectedProject, baseText.trim())
                    savedHint = if (baseText.isBlank()) "已恢复内置模板" else "基础提示词已保存"
                }) { Text("保存") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (tab == 0) {
                TextButton(onClick = { savedHint = null; onDismiss() }) { Text("取消") }
            } else {
                TextButton(onClick = { savedHint = null }) { Text("完成") }
            }
        }
    )
}

/**
 * 列出项目下可引用的文本文件（相对项目目录路径）。
 * 实现已收敛到 [listProjectTextFiles]，此处仅作本文件内的调用别名。
 */
private fun projectTextFiles(projectName: String): List<String> =
    listProjectTextFiles(projectName)


private fun formatNumber(n: Long): String {
    return when {
        n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
        n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}K"
        else -> n.toString()
    }
}
