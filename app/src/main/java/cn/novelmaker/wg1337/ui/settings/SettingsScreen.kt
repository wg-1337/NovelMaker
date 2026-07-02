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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.novelmaker.wg1337.ui.ai.AiChatHistoryManager
import cn.novelmaker.wg1337.ui.ai.SystemPromptManager
import cn.novelmaker.wg1337.ui.ai.TokenUsageManager
import cn.novelmaker.wg1337.data.repository.ProjectRepository
import cn.novelmaker.wg1337.utils.PreferencesManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    var themeMode by remember { mutableIntStateOf(prefsManager.themeMode) }
    var fontSize by remember { mutableIntStateOf(prefsManager.editorFontSize.toInt()) }
    var lineSpacing by remember { mutableFloatStateOf(prefsManager.editorLineSpacing) }
    var autoSave by remember { mutableStateOf(prefsManager.isAutoSaveEnabled) }
    var aiBaseUrl by remember { mutableStateOf(prefsManager.aiBaseUrl ?: "https://api.deepseek.com") }
    var aiModel by remember { mutableStateOf(prefsManager.aiModel ?: "deepseek-chat") }
    var aiApiKey by remember { mutableStateOf(prefsManager.aiApiKey ?: "") }
    var aiStream by remember { mutableStateOf(prefsManager.aiStreamEnabled) }
    var showChatHistoryDialog by remember { mutableStateOf(false) }
    var showTokenStatsDialog by remember { mutableStateOf(false) }
    var showPromptViewerDialog by remember { mutableStateOf(false) }
    var showBackupScreen by remember { mutableStateOf(false) }

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
            ThemeCard(label = "跟随壁纸", desc = "根据壁纸自动适配主题颜色", selected = themeMode == 0, onClick = {
                themeMode = 0; prefsManager.themeMode = 0
            })
            ThemeCard(label = "浅色模式", desc = "清爽明亮的界面", selected = themeMode == 1, onClick = {
                themeMode = 1; prefsManager.themeMode = 1
            })
            ThemeCard(label = "深色模式", desc = "护眼省电的暗色界面", selected = themeMode == 2, onClick = {
                themeMode = 2; prefsManager.themeMode = 2
            })

            Spacer(Modifier.height(8.dp))
            SectionTitle("关于")
            Card(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wg-1337/NovelMaker"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("NovelMaker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("版本 1.4.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("点击访问项目仓库 →", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("自动保存", style = MaterialTheme.typography.bodyLarge)
                            Text("编辑内容自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoSave, onCheckedChange = { autoSave = it; prefsManager.isAutoSaveEnabled = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    var maxFinalized by remember { mutableStateOf(prefsManager.maxFinalizedChapters.toString()) }
                    var bulkEvict by remember { mutableStateOf(prefsManager.bulkEvictChapters.toString()) }
                    OutlinedTextField(
                        value = maxFinalized,
                        onValueChange = {
                            maxFinalized = it.filter { c -> c.isDigit() }
                            prefsManager.maxFinalizedChapters = maxFinalized.toIntOrNull() ?: 0
                        },
                        label = { Text("定稿上限") }, placeholder = { Text("0 = 无限制") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bulkEvict,
                        onValueChange = {
                            bulkEvict = it.filter { c -> c.isDigit() }
                            val v = bulkEvict.toIntOrNull() ?: 1
                            prefsManager.bulkEvictChapters = v.coerceAtLeast(1)
                        },
                        label = { Text("批量淘汰") }, placeholder = { Text("超限时一次移除最早 N 章") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── AI 设置 ──
            Spacer(Modifier.height(8.dp))
            SectionTitle("AI 写作设置")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = aiBaseUrl, onValueChange = { aiBaseUrl = it; prefsManager.aiBaseUrl = it },
                        label = { Text("API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = aiModel, onValueChange = { aiModel = it; prefsManager.aiModel = it },
                        label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = aiApiKey, onValueChange = { aiApiKey = it; prefsManager.aiApiKey = it.ifEmpty { null } },
                        label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("流式回答", style = MaterialTheme.typography.bodyLarge)
                            Text("逐字显示AI回复内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = aiStream, onCheckedChange = { aiStream = it; prefsManager.aiStreamEnabled = it })
                    }
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
                    // 系统提示词浏览
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("系统提示词", style = MaterialTheme.typography.bodyLarge)
                            Text("查看当前项目的系统提示词（只读）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showPromptViewerDialog = true }) { Text("浏览") }
                    }
                }
            }
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
    val ctx = LocalContext.current
    val manager = remember { cn.novelmaker.wg1337.ui.ai.AiChatHistoryManager(ctx) }
    val projectRepo = remember { ProjectRepository(ctx) }
    val projects = remember { manager.getProjectsWithChatHistory() }
    // 构建 projectId → projectName 的映射
    val nameMap = remember(projects) {
        val allProjects = projectRepo.getAllProjects()
        projects.associateWith { pid -> allProjects.find { it.id == pid }?.name ?: pid }
    }
    var confirm by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("聊天记录管理") },
        text = {
            if (projects.isEmpty()) Text("暂无对话记录")
            else Column {
                projects.forEach { pid ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nameMap[pid] ?: pid, style = MaterialTheme.typography.bodyMedium)
                            Text("ID: $pid", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirm = pid }, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
    confirm?.let { pid ->
        val projectName = nameMap[pid] ?: pid
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「$projectName」的对话记录吗？") },
            confirmButton = { TextButton(onClick = { manager.deleteChatHistory(pid); confirm = null; onDismiss() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }
        )
    }
}

// ── Token 用量统计对话框 ──
@Composable
private fun TokenStatsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val tokenManager = remember { TokenUsageManager(ctx) }
    val projects = remember { tokenManager.getProjectsWithStats() }
    var selectedProject by remember { mutableStateOf(if (projects.isNotEmpty()) projects.first() else "") }

    val stats = if (selectedProject.isNotEmpty()) remember(selectedProject) { tokenManager.getStats(selectedProject) }
    else TokenUsageManager.TokenStats()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token 用量统计") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (projects.isEmpty()) {
                    Text("暂无统计数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // 项目选择器
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedProject.ifEmpty { "选择项目" })
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            projects.forEach { pid ->
                                DropdownMenuItem(text = { Text(pid) }, onClick = { selectedProject = pid; expanded = false })
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
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ── 系统提示词浏览对话框 ──
@Composable
private fun PromptViewerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val promptManager = remember { SystemPromptManager(ctx) }
    val chatManager = remember { AiChatHistoryManager(ctx) }
    val projects = remember { chatManager.getProjectsWithChatHistory() }
    var selectedProject by remember { mutableStateOf(if (projects.isNotEmpty()) projects.first() else "") }

    val promptText = if (selectedProject.isNotEmpty()) remember(selectedProject) { promptManager.getForDisplay(selectedProject) } else ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统提示词（只读）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (projects.isEmpty()) {
                    Text("暂无项目数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedProject.ifEmpty { "选择项目" })
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            projects.forEach { pid ->
                                DropdownMenuItem(text = { Text(pid) }, onClick = { selectedProject = pid; expanded = false })
                            }
                        }
                    }
                    if (promptText.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            androidx.compose.foundation.rememberScrollState().let { scroll ->
                                Text(
                                    promptText,
                                    modifier = Modifier.verticalScroll(scroll).padding(8.dp),
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    } else {
                        Text("该项目暂无系统提示词", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun formatNumber(n: Long): String {
    return when {
        n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
        n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}K"
        else -> n.toString()
    }
}
