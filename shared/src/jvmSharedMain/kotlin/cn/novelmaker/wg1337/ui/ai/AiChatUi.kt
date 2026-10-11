package cn.novelmaker.wg1337.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.novelmaker.wg1337.ui.common.MarkdownContent
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
    val tabState by viewModel.tabState.collectAsState()
    val pendingImages by viewModel.pendingImages.collectAsState()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    var isInitScroll by remember { mutableStateOf(true) }
    var currentTabId by remember { mutableIntStateOf(tabState.activeTabId) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberImagePicker { picked ->
        if (picked != null) {
            attachError = viewModel.attachImage(picked.first, picked.second)
        }
    }

    // 滚动定位：切换对话/首次加载 → 直接定位到底（无动画，长对话不卡顿）；
    // 同一对话内新消息 → 平滑滚动到底
    LaunchedEffect(messages.size, tabState.activeTabId) {
        if (messages.isNotEmpty()) {
            if (isInitScroll || currentTabId != tabState.activeTabId) {
                listState.scrollToItem(messages.size - 1)
                isInitScroll = false
            } else {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
        currentTabId = tabState.activeTabId
    }

    Column(modifier = modifier) {
        // ── 顶部工具条 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            title()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 模式切换：只切换模式，**不自动发起模型请求**。
                // 用户切完可以自己决定下一步说什么；自动请求会意外消耗 token，
                // 也会在用户只是想看看另一种模式时平白产生一轮对话。
                // （AI 处理中禁用，避免请求进行中切换模式造成上下文错乱）
                TextButton(
                    enabled = !isProcessing,
                    onClick = {
                        if (currentMode == AiChatViewModel.AiMode.PLAN) {
                            viewModel.switchToAgentMode()
                        } else {
                            viewModel.switchToPlanMode()
                        }
                    }
                ) {
                    val label = if (currentMode == AiChatViewModel.AiMode.PLAN) "Plan" else "Agent"
                    val icon = if (currentMode == AiChatViewModel.AiMode.PLAN) {
                        Icons.Default.Map
                    } else {
                        Icons.Default.EditNote
                    }
                    Icon(icon, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = 12.sp)
                }
                // 当前对话的注入设置
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        "上下文与提示词注入",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
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
            }
        }
        HorizontalDivider()

        // ── 标签页栏（自定义实现，避免 tab 数量动态变化时的越界崩溃） ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabState.tabs.forEach { tab ->
                val selected = tab.id == tabState.activeTabId
                Surface(
                    onClick = { viewModel.switchTab(tab.id) },
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier.padding(end = 6.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Text(
                        tab.name,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            if (tabState.tabs.size < AiChatViewModel.MAX_TABS) {
                IconButton(
                    onClick = { viewModel.addTab() },
                    enabled = !isProcessing,
                    modifier = Modifier.size(32.dp)
                ) { Icon(Icons.Default.Add, "新建对话", Modifier.size(16.dp)) }
            }
        }
        HorizontalDivider()

        // ── 消息列表 ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                when {
                    // 上下文注入与工具调用一样默认折叠（自动插入的内容不占据视线）
                    msg.isInjection && msg.isUser() -> InjectionBubble(msg)
                    msg.isUser() -> UserBubble(
                        msg,
                        readBytes = viewModel::readImageBytes,
                        onLongClick = { editingIndex = index; editText = msg.content }
                    )
                    msg.isAssistant() -> AiBubble(
                        msg,
                        onChoice = { viewModel.onUserChoice(it) },
                        onSwitchToAgent = {
                            viewModel.switchToAgentMode()
                            viewModel.onUserChoice("已确认计划，开始 Agent 模式写作")
                        },
                        onEdit = { editingIndex = index; editText = msg.content }
                    )
                    msg.role == "tool" -> ToolBubble(msg)
                    msg.isSystem() -> SystemBubble(
                        msg,
                        // 仅本地状态提示可删除（如「已达到最大工具调用轮次」）
                        onDelete = if (msg.content.contains("已达到最大")) {
                            { viewModel.deleteMessage(index) }
                        } else null
                    )
                }
            }
            if (isProcessing) {
                item {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "AI 思考中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── 待发送图片 ──
        if (pendingImages.isNotEmpty()) {
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(pendingImages, key = { it.fileName }) { img ->
                        Box {
                            ImageThumbnail(
                                image = img,
                                projectId = "pending-${viewModel.hashCode()}",
                                readBytes = viewModel::readImageBytes,
                                modifier = Modifier.size(56.dp)
                            )
                            IconButton(
                                onClick = { viewModel.removePendingImage(img.fileName) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        RoundedCornerShape(50)
                                    )
                            ) {
                                Icon(Icons.Default.Close, "移除图片", Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }

        attachError?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        err,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { attachError = null }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            Icons.Default.Close, "关闭", Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // ── 输入区 ──
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { attachError = null; pickImage() },
                    enabled = !isProcessing,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        "添加图片",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入你的创作需求…", fontSize = 14.sp) },
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() || pendingImages.isNotEmpty()) {
                            if (viewModel.sendMessage(inputText)) inputText = ""
                        }
                    },
                    enabled = !isProcessing && (inputText.isNotBlank() || pendingImages.isNotEmpty())
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "发送",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // ── 编辑/重发对话框 ──
    if (editingIndex >= 0) {
        val editingMsg = messages.getOrNull(editingIndex)
        val isEditingAssistant = editingMsg?.isAssistant() == true
        AlertDialog(
            onDismissRequest = { editingIndex = -1 },
            title = { Text(if (isEditingAssistant) "编辑 AI 回复" else "编辑消息") },
            text = {
                Column {
                    if (editingMsg != null && editingMsg.hasImages()) {
                        Text(
                            "该消息附带 ${editingMsg.images.size} 张图片（编辑不影响图片）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        maxLines = 10
                    )
                    if (isEditingAssistant) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "仅修改本条内容，不影响后续对话。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    } else if (editingIndex < messages.size - 1) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning, null, Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "重发将删除此消息之后的所有对话",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (isEditingAssistant) {
                    Button(onClick = {
                        viewModel.editMessage(editingIndex, editText)
                        editingIndex = -1
                    }) { Text("保存修改") }
                } else {
                    Button(onClick = {
                        viewModel.resendFrom(editingIndex, editText)
                        editingIndex = -1
                    }) { Text("重发") }
                }
            },
            dismissButton = {
                Row {
                    if (isEditingAssistant) {
                        TextButton(onClick = {
                            viewModel.deleteMessage(editingIndex)
                            editingIndex = -1
                        }) { Text("删除此条", color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(onClick = {
                            viewModel.editMessage(editingIndex, editText)
                            editingIndex = -1
                        }) { Text("仅保存") }
                    }
                    TextButton(onClick = { editingIndex = -1 }) { Text("取消") }
                }
            }
        )
    }

    // ── 上下文 / 系统提示词注入设置（单对话级） ──
    if (showSettingsDialog) {
        ConversationInjectionDialog(
            viewModel = viewModel,
            onDismiss = {
                showSettingsDialog = false
                viewModel.refreshEffectiveSettings()
            }
        )
    }
}

// ───────────────────── 图片渲染 ─────────────────────

/**
 * 解码并展示一张对话图片。
 *
 * 解码结果按 (projectId, fileName) 缓存，避免重组时反复读盘与解码。
 * 图片文件缺失（如被清理）时显示占位图标而不是崩溃。
 */
@Composable
fun ImageThumbnail(
    image: ChatImage,
    projectId: String,
    readBytes: (ChatImage) -> ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val bitmap: ImageBitmap? = remember(projectId, image.fileName) {
        decodeChatImage(readBytes(image))
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = "对话图片",
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.BrokenImage,
                "图片不可用",
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 按文件字节解码为 ImageBitmap；失败返回 null */
fun decodeChatImage(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        bytes.decodeToImageBitmap()
    } catch (_: Exception) {
        null
    }
}

// ───────────────────── 气泡 ─────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    msg: AiChatMessage,
    readBytes: (ChatImage) -> ByteArray?,
    onLongClick: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 280.dp).then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                } else Modifier
            )
        ) {
            Column(Modifier.padding(10.dp)) {
                // 图片附件
                msg.images.forEach { img ->
                    ImageThumbnail(
                        image = img,
                        projectId = msg.id,
                        readBytes = readBytes,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(bottom = 4.dp)
                    )
                }
                if (msg.content.isNotEmpty()) {
                    Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    fmt(msg.timestamp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiBubble(
    msg: AiChatMessage,
    onChoice: (String) -> Unit = {},
    onSwitchToAgent: () -> Unit = {},
    onEdit: (() -> Unit)? = null
) {
    var showReasoning by remember { mutableStateOf(false) }
    var showToolCalls by remember { mutableStateOf(false) } // 工具调用默认折叠
    // 解析内容中的特殊标记
    val cleanContent = remember(msg.content) {
        msg.content
            .replace(Regex("\\[DIRECTION_CHOICES:.+?\\]"), "")
            .replace("[PLAN_COMPLETE]", "")
            .trim()
    }
    val hasPlanComplete = remember(msg.content) { msg.content.contains("[PLAN_COMPLETE]") }
    val directionRegex = remember(msg.content) {
        Regex("\\[DIRECTION_CHOICES:(.+?)\\]").find(msg.content)
    }
    val directionOptions = directionRegex?.groupValues?.get(1)
        ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val bubbleShape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 300.dp).then(
                if (onEdit != null) Modifier.combinedClickable(onClick = {}, onLongClick = onEdit) else Modifier
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                if (msg.hasReasoning()) {
                    Row(
                        modifier = Modifier.clickable { showReasoning = !showReasoning }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showReasoning) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("思考过程", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    AnimatedVisibility(showReasoning) {
                        Text(
                            msg.reasoningContent,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(bubbleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                // AI 输出支持 Markdown 渲染（仅展示有效，编辑时仍为纯文本）
                if (cleanContent.isNotEmpty()) {
                    MarkdownContent(cleanContent, textStyle = MaterialTheme.typography.bodyMedium)
                }

                // 工具调用列表：默认折叠，点击展开
                if (msg.hasToolCalls()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.clickable { showToolCalls = !showToolCalls }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showToolCalls) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Build, null, Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "工具调用（${msg.toolCalls?.size ?: 0} 次）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    AnimatedVisibility(showToolCalls) {
                        Column {
                            msg.toolCalls?.forEach { tc ->
                                Text(
                                    "· ${tc.function.name}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
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
                            ) { Text(opt, fontSize = 13.sp) }
                        }
                        if (!showCustomInput) {
                            OutlinedButton(
                                onClick = { showCustomInput = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("自定义", fontSize = 13.sp)
                            }
                        } else {
                            OutlinedTextField(
                                value = customText,
                                onValueChange = { customText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("输入你的想法…", fontSize = 12.sp) },
                                maxLines = 2,
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showCustomInput = false; customText = "" }) {
                                    Text("取消", fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(4.dp))
                                Button(
                                    onClick = {
                                        if (customText.isNotBlank()) {
                                            onChoice(customText.trim())
                                            showCustomInput = false
                                            customText = ""
                                        }
                                    },
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
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("开始写作", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { onChoice("继续完善计划，暂不开始写作") },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("继续完善", fontSize = 13.sp)
                        }
                    }
                }

                Text(
                    fmt(msg.timestamp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 上下文注入消息：与工具调用一致，**默认折叠**，点击展开。
 * 注入内容是自动插入的，平时不该占据聊天视线。
 */
@Composable
private fun InjectionBubble(msg: AiChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clickable { expanded = !expanded },
            tonalElevation = 1.dp
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Download, null, Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("上下文注入", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${msg.content.length} 字",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                AnimatedVisibility(expanded) {
                    Text(
                        msg.content,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolBubble(msg: AiChatMessage) {
    var expanded by remember { mutableStateOf(false) } // 工具结果默认折叠
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(0.9f).clickable { expanded = !expanded },
            tonalElevation = 1.dp
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Build, null, Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("工具结果", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                AnimatedVisibility(expanded) {
                    Text(
                        msg.content,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemBubble(msg: AiChatMessage, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (onDelete != null) Arrangement.SpaceBetween else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            msg.content,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = if (onDelete != null) Modifier.weight(1f) else Modifier
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.Default.Close, "删除", Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun fmt(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
