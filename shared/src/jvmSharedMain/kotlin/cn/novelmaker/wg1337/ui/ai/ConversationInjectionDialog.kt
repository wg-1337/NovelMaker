package cn.novelmaker.wg1337.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.novelmaker.wg1337.utils.PreferencesManager

/**
 * 上下文注入 / 系统提示词注入 / 引用文件 / 基础提示词 的统一设置对话框。
 *
 * ## 两级作用范围
 *
 * - **工作区**：影响该项目下所有对话，作为默认值。
 * - **当前对话**：**按字段**覆盖工作区。每个字段可单独选择「继承工作区」或「本对话自定义」，
 *   因此可以只改一处而其余继续跟随工作区（之后改工作区也会同步生效）。
 *
 * 这也是旧实现的缺陷所在：那时单对话层保存的是整份快照，
 * 一旦保存就与工作区永久分叉。现在只保存与工作区不同的字段。
 */
@Composable
fun ConversationInjectionDialog(
    viewModel: AiChatViewModel,
    onDismiss: () -> Unit
) {
    val prefs = remember { PreferencesManager() }
    val tabState by viewModel.tabState.collectAsState()

    val projectId = viewModel.currentProjectId
    val tabId = tabState.activeTabId

    var editingConversation by remember { mutableStateOf(false) }
    // 保存/切换范围后强制刷新派生状态
    var refreshKey by remember { mutableIntStateOf(0) }

    // 工作区生效值（含全局默认回落），用于展示「继承的值」
    val wsEffective = remember(projectId, refreshKey) {
        ConversationSettingsManager.resolve(projectId, 0, prefs)
    }
    // 对话层原始覆盖（null 字段 = 继承工作区）
    val conversation = remember(projectId, tabId, refreshKey) {
        ConversationSettingsManager.getConversationOverride(projectId, tabId)
    }

    // ── 各字段编辑态：对话层下以工作区值为初值，便于微调 ──
    var ctxEnabled by remember(projectId, tabId, editingConversation, refreshKey) {
        mutableStateOf(conversation.context.enabled ?: wsEffective.context.enabled)
    }
    var ctxSteps by remember(projectId, tabId, editingConversation, refreshKey) {
        mutableStateOf(
            (conversation.context.everyNSteps ?: wsEffective.context.everyNSteps).toString()
        )
    }
    var ctxText by remember(projectId, tabId, editingConversation, refreshKey) {
        mutableStateOf(conversation.context.text ?: wsEffective.context.text)
    }
    var sysEnabled by remember(projectId, tabId, editingConversation, refreshKey) {
        mutableStateOf(conversation.systemPrompt.enabled ?: wsEffective.systemPrompt.enabled)
    }
    var sysText by remember(projectId, tabId, editingConversation, refreshKey) {
        mutableStateOf(conversation.systemPrompt.text ?: wsEffective.systemPrompt.text)
    }

    // 「本对话自定义」开关（仅对话层用）
    var ctxOverride by remember(projectId, tabId, refreshKey) {
        mutableStateOf(
            conversation.context.enabled != null ||
                conversation.context.everyNSteps != null ||
                conversation.context.text != null
        )
    }
    var sysOverride by remember(projectId, tabId, refreshKey) {
        mutableStateOf(
            conversation.systemPrompt.enabled != null || conversation.systemPrompt.text != null
        )
    }

    // 基础提示词与引用文件都是项目级
    var basePrompt by remember(projectId, refreshKey) {
        mutableStateOf(SystemPromptManager().getBasePrompt(projectId))
    }
    var basePromptEdited by remember(projectId, refreshKey) { mutableStateOf(false) }
    var contextFiles by remember(projectId, refreshKey) {
        mutableStateOf(SystemPromptManager().getContextFiles(projectId))
    }
    val availableFiles = remember(projectId, refreshKey) {
        if (projectId.isEmpty()) emptyList() else listProjectTextFiles(viewModel.currentProjectName)
    }
    var hint by remember { mutableStateOf<String?>(null) }

    fun buildOverride(): ConversationSettingsManager.Override =
        if (editingConversation) {
            ConversationSettingsManager.Override(
                context = ConversationSettingsManager.ContextOverride(
                    enabled = if (ctxOverride) ctxEnabled else null,
                    everyNSteps = if (ctxOverride) {
                        ctxSteps.toIntOrNull()?.coerceAtLeast(1)
                            ?: ConversationSettingsManager.DEFAULT_INJECTION_STEPS
                    } else null,
                    text = if (ctxOverride) ctxText else null
                ),
                systemPrompt = ConversationSettingsManager.SystemPromptOverride(
                    enabled = if (sysOverride) sysEnabled else null,
                    text = if (sysOverride) sysText else null
                )
            )
        } else {
            ConversationSettingsManager.Override(
                context = ConversationSettingsManager.ContextOverride(
                    enabled = ctxEnabled,
                    everyNSteps = ctxSteps.toIntOrNull()?.coerceAtLeast(1)
                        ?: ConversationSettingsManager.DEFAULT_INJECTION_STEPS,
                    text = ctxText
                ),
                systemPrompt = ConversationSettingsManager.SystemPromptOverride(
                    enabled = sysEnabled,
                    text = sysText
                )
            )
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文与提示词设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 作用范围 ──
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !editingConversation,
                        onClick = { editingConversation = false },
                        label = { Text("工作区") },
                        leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = editingConversation,
                        onClick = { editingConversation = true },
                        label = { Text("对话$tabId") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(16.dp))
                        }
                    )
                }
                Text(
                    if (editingConversation) {
                        "只保存你开启「自定义」的字段；其余字段继续跟随工作区，之后改工作区会同步生效。"
                    } else {
                        "影响本项目全部对话，作为各对话的默认值。"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // ══ 上下文注入 ══
                OverrideHeader(
                    title = "上下文注入",
                    subtitle = "每 N 步模型操作后，自动把预设内容注入到上下文末尾",
                    isConversation = editingConversation,
                    isOverridden = ctxOverride,
                    inheritedSummary = if (wsEffective.context.isUsable) {
                        "继承工作区：每 ${wsEffective.context.everyNSteps} 步"
                    } else {
                        "继承工作区：未启用"
                    },
                    onOverrideChange = { on ->
                        ctxOverride = on
                        if (on) {
                            ctxEnabled = wsEffective.context.enabled
                            ctxSteps = wsEffective.context.everyNSteps.toString()
                            ctxText = wsEffective.context.text
                        }
                    }
                )
                if (!editingConversation || ctxOverride) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = ctxEnabled, onCheckedChange = { ctxEnabled = it })
                    }
                    OutlinedTextField(
                        value = ctxSteps,
                        onValueChange = { v -> ctxSteps = v.filter { it.isDigit() }.take(3) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ctxEnabled,
                        singleLine = true,
                        label = { Text("注入间隔（步）") },
                        supportingText = {
                            Text("每一步 = 模型的一次回复（含一次工具调用轮次）", fontSize = 10.sp)
                        }
                    )
                    OutlinedTextField(
                        value = ctxText,
                        onValueChange = { ctxText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                        enabled = ctxEnabled,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("注入内容") },
                        placeholder = { Text("例如：提醒：主角目前在第 3 卷，已获得断剑。") },
                        supportingText = {
                            Text("注入内容会排在你的发言之前，不会与你的指令混在一起", fontSize = 10.sp)
                        }
                    )
                }

                HorizontalDivider()

                // ══ 系统提示词注入 ══
                OverrideHeader(
                    title = "系统提示词注入",
                    subtitle = "追加到系统提示词，排在基础提示词与引用文件之后（不进缓存前缀）",
                    isConversation = editingConversation,
                    isOverridden = sysOverride,
                    inheritedSummary = if (wsEffective.systemPrompt.isUsable) {
                        "继承工作区：已启用"
                    } else {
                        "继承工作区：未启用"
                    },
                    onOverrideChange = { on ->
                        sysOverride = on
                        if (on) {
                            sysEnabled = wsEffective.systemPrompt.enabled
                            sysText = wsEffective.systemPrompt.text
                        }
                    }
                )
                if (!editingConversation || sysOverride) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = sysEnabled, onCheckedChange = { sysEnabled = it })
                    }
                    OutlinedTextField(
                        value = sysText,
                        onValueChange = { sysText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        enabled = sysEnabled,
                        textStyle = MaterialTheme.typography.bodySmall,
                        label = { Text("自定义系统提示词内容") },
                        placeholder = { Text("例如：本作采用第一人称叙述，避免现代网络用语") }
                    )
                }

                HorizontalDivider()

                // ══ 项目级：基础提示词 ══
                Text(
                    "基础提示词（项目级）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = basePrompt,
                    onValueChange = { basePrompt = it; basePromptEdited = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("系统提示词正文（进缓存前缀）") },
                    supportingText = { Text("留空并保存 = 恢复内置模板", fontSize = 10.sp) }
                )
                OutlinedButton(onClick = {
                    basePrompt = DefaultPrompt.getDefaultPrompt()
                    basePromptEdited = true
                }) {
                    Icon(Icons.Default.Restore, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("载入内置模板", fontSize = 12.sp)
                }

                HorizontalDivider()

                // ══ 项目级：引用文件 ══
                Text(
                    "引用文件（项目级）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "勾选的文件内容会读入系统提示词，每次请求现读，文件改了自动更新。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (availableFiles.isEmpty()) {
                    Text(
                        "项目里暂无 .txt 文件",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    availableFiles.forEach { rel ->
                        val checked = contextFiles.contains(rel)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val mgr = SystemPromptManager()
                                    if (checked) mgr.removeContextFile(projectId, rel)
                                    else mgr.addContextFile(projectId, rel)
                                    contextFiles = mgr.getContextFiles(projectId)
                                    viewModel.notifyPromptConfigChanged()
                                }
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { v ->
                                    val mgr = SystemPromptManager()
                                    if (v) mgr.addContextFile(projectId, rel)
                                    else mgr.removeContextFile(projectId, rel)
                                    contextFiles = mgr.getContextFiles(projectId)
                                    viewModel.notifyPromptConfigChanged()
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(rel, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }

                hint?.let { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(h, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // 1) 项目级：基础提示词
                if (basePromptEdited) {
                    SystemPromptManager().setBasePrompt(projectId, basePrompt.trim())
                }
                // 2) 注入设置（对话层只落盘显式自定义的字段）
                val override = buildOverride()
                if (editingConversation) {
                    ConversationSettingsManager.saveConversationOverride(projectId, tabId, override)
                } else {
                    ConversationSettingsManager.saveWorkspace(projectId, override)
                }
                viewModel.refreshEffectiveSettings()
                viewModel.notifyPromptConfigChanged()
                refreshKey++
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (editingConversation && (ctxOverride || sysOverride)) {
                    TextButton(onClick = {
                        ConversationSettingsManager.clearConversationOverride(projectId, tabId)
                        viewModel.refreshEffectiveSettings()
                        viewModel.notifyPromptConfigChanged()
                        refreshKey++
                        onDismiss()
                    }) { Text("全部继承工作区") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 一层设置的标题行。
 *
 * 对话层时右侧提供「自定义 / 恢复继承」切换 —— 这是两级能分开保存的关键：
 * 只有显式开启自定义的字段才会落到对话层，其余保持继承。
 */
@Composable
private fun OverrideHeader(
    title: String,
    subtitle: String,
    isConversation: Boolean,
    isOverridden: Boolean,
    inheritedSummary: String,
    onOverrideChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isConversation && !isOverridden) {
                Text(inheritedSummary, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (isConversation) {
            TextButton(onClick = { onOverrideChange(!isOverridden) }) {
                Icon(
                    if (isOverridden) Icons.Default.Close else Icons.Default.Edit,
                    null,
                    Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isOverridden) "恢复继承" else "自定义", fontSize = 11.sp)
            }
        }
    }
}
