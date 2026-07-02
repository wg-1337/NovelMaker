package cn.novelmaker.wg1337.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AiChatUi(
    viewModel: AiChatViewModel,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit = { Text("AI 写作助手", style = MaterialTheme.typography.titleMedium) }
) {
    val messages by viewModel.messages.collectAsState()
    val showResume by viewModel.showResume.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    var isInitScroll by remember { mutableStateOf(true) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (isInitScroll && messages.size > 1) {
                // 首次加载：直接定位到底部，不播放动画
                listState.scrollToItem(messages.size - 1)
                isInitScroll = false
            } else if (!isInitScroll) {
                // 新消息到达：平滑滚动
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            title()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 模式切换按钮
                TextButton(onClick = {
                    if (currentMode == AiChatViewModel.AiMode.PLAN) {
                        viewModel.switchToAgentMode()
                        viewModel.onUserChoice("切换到 Agent 写作模式")
                    } else {
                        viewModel.switchToPlanMode()
                        viewModel.onUserChoice("切换回 Plan 计划模式，继续完善大纲")
                    }
                }) {
                    val (icon, label) = if (currentMode == AiChatViewModel.AiMode.PLAN) "🗺️" to "Plan" else "✍️" to "Agent"
                    Text("$icon $label", fontSize = 12.sp)
                }
                if (isProcessing) {
                    IconButton(onClick = { viewModel.stopGeneration() }) {
                        Icon(Icons.Default.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                    }
                }
                if (showResume && !isProcessing) {
                    IconButton(onClick = { viewModel.resumeFromInterruption() }) {
                        Icon(Icons.Default.Refresh, "续传", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onClick = { viewModel.clearChat() }) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Text("清空", fontSize = 12.sp)
                }
            }
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                when {
                    msg.isUser() -> UserBubble(msg, onLongClick = { editingIndex = index; editText = msg.content })
                    msg.isAssistant() -> AiBubble(
                        msg,
                        onChoice = { viewModel.onUserChoice(it) },
                        onSwitchToAgent = { viewModel.switchToAgentMode(); viewModel.onUserChoice("已确认计划，开始 Agent 模式写作") }
                    )
                    msg.role == "tool" -> ToolBubble(msg)
                    msg.isSystem() -> SystemBubble(msg, onDelete = if (msg.content.contains("已达到最大")) ({ viewModel.deleteMessage(index) }) else null)
                }
            }
            if (isProcessing) {
                item {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 思考中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText, onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入你的创作需求…", fontSize = 14.sp) },
                    maxLines = 3, textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) { viewModel.sendMessage(inputText.trim()); inputText = "" }
                    },
                    enabled = !isProcessing && inputText.isNotBlank()
                ) { Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    // 编辑/重发对话框
    if (editingIndex >= 0) {
        val isLastUserMsg = editingIndex == messages.size - 1 || (editingIndex < messages.size - 1 && messages.getOrNull(editingIndex + 1)?.isAssistant() == true)
        AlertDialog(
            onDismissRequest = { editingIndex = -1 },
            title = { Text("编辑消息") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText, onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 10
                    )
                    if (editingIndex < messages.size - 1) {
                        Spacer(Modifier.height(8.dp))
                        Text("⚠️ 重发将删除此消息之后的所有对话", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.resendFrom(editingIndex, editText)
                    editingIndex = -1
                }) { Text("重发") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.editMessage(editingIndex, editText); editingIndex = -1 }) { Text("仅保存") }
                    TextButton(onClick = { editingIndex = -1 }) { Text("取消") }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: AiChatMessage, onLongClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 280.dp).then(
                if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                SelectionContainer { Text(msg.content, style = MaterialTheme.typography.bodyMedium) }
                Text(fmt(msg.timestamp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AiBubble(msg: AiChatMessage, onChoice: (String) -> Unit = {}, onSwitchToAgent: () -> Unit = {}) {
    var showReasoning by remember { mutableStateOf(false) }
    // 解析内容中的特殊标记
    val cleanContent = remember(msg.content) {
        msg.content
            .replace(Regex("\\[DIRECTION_CHOICES:.+?\\]"), "")
            .replace("[PLAN_COMPLETE]", "")
            .trim()
    }
    val hasPlanComplete = remember(msg.content) { msg.content.contains("[PLAN_COMPLETE]") }
    val directionRegex = remember(msg.content) { Regex("\\[DIRECTION_CHOICES:(.+?)\\]").find(msg.content) }
    val directionOptions = directionRegex?.groupValues?.get(1)?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val bubbleShape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (msg.hasReasoning()) {
                    Row(
                        modifier = Modifier.clickable { showReasoning = !showReasoning }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (showReasoning) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("思考过程", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    AnimatedVisibility(showReasoning) {
                        Text(msg.reasoningContent, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(bubbleShape).background(MaterialTheme.colorScheme.surface).padding(8.dp))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                if (cleanContent.isNotEmpty()) SelectionContainer { Text(cleanContent, style = MaterialTheme.typography.bodyMedium) }
                if (msg.hasToolCalls()) {
                    msg.toolCalls?.forEach { tc ->
                        Text("🔧 ${tc.function.name}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                }

                // 内联 DIRECTION_CHOICES 选项按钮（PLAN_COMPLETE 优先，两者不共存）
                if (directionOptions.isNotEmpty() && !hasPlanComplete) {
                    Spacer(Modifier.height(8.dp))
                    var showCustomInput by remember { mutableStateOf(false) }
                    var customText by remember { mutableStateOf("") }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        directionOptions.forEach { opt ->
                            OutlinedButton(
                                onClick = { onChoice("我选择：$opt") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(opt, fontSize = 13.sp)
                            }
                        }
                        if (!showCustomInput) {
                            OutlinedButton(
                                onClick = { showCustomInput = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("✏️ 自定义", fontSize = 13.sp)
                            }
                        } else {
                            OutlinedTextField(
                                value = customText, onValueChange = { customText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("输入你的想法…", fontSize = 12.sp) },
                                maxLines = 2, textStyle = MaterialTheme.typography.bodySmall
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showCustomInput = false; customText = "" }) { Text("取消", fontSize = 11.sp) }
                                Spacer(Modifier.width(4.dp))
                                Button(
                                    onClick = { if (customText.isNotBlank()) { onChoice(customText.trim()); showCustomInput = false; customText = "" } },
                                    enabled = customText.isNotBlank(),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("发送", fontSize = 11.sp) }
                            }
                        }
                    }
                }

                // 内联 PLAN_COMPLETE 按钮
                if (hasPlanComplete) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSwitchToAgent,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("✅ 开始写作", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { onChoice("继续完善计划，暂不开始写作") },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("🔄 继续完善", fontSize = 13.sp) }
                    }
                }

                Text(fmt(msg.timestamp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToolBubble(msg: AiChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(0.9f), tonalElevation = 1.dp) {
            Text(msg.content, modifier = Modifier.padding(10.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SystemBubble(msg: AiChatMessage, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(msg.content, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, "删除", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun fmt(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
