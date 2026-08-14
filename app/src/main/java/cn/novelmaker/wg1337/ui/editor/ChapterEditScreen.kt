package cn.novelmaker.wg1337.ui.editor

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.novelmaker.wg1337.ui.ai.AiChatUi
import cn.novelmaker.wg1337.ui.ai.AiChatViewModel
import cn.novelmaker.wg1337.ui.ai.FinalizedManager
import cn.novelmaker.wg1337.ui.home.AppContextHolder
import cn.novelmaker.wg1337.ui.tutorial.EditorTutorialOverlay
import cn.novelmaker.wg1337.ui.tutorial.TutorialStep
import cn.novelmaker.wg1337.utils.PreferencesManager
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditScreen(
    projectName: String,
    projectId: String,
    chapterId: String,
    filePath: String?,
    onBack: () -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val aiViewModel = remember { AiChatViewModel() }

    val content by viewModel.content.collectAsState()
    val title by viewModel.title.collectAsState()
    val wordCount by viewModel.wordCount.collectAsState()
    val isModified by viewModel.isModified.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val lineSpacing by viewModel.lineSpacing.collectAsState()
    val showFileTree by viewModel.showFileTree.collectAsState()
    val fileTreeItems by viewModel.fileTreeItems.collectAsState()
    val fileChangeVersion by aiViewModel.fileChangeVersion.collectAsState()

    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }
    var showAiPanel by remember { mutableStateOf(false) }
    var projectChapterCount by remember { mutableIntStateOf(0) }
    var projectWordCount by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf<File?>(null) }
    var showRenameDialog by remember { mutableStateOf<File?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<File?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderParent by remember { mutableStateOf<File?>(null) }
    var showFileMenu by remember { mutableStateOf<File?>(null) }
    var showUnmarkConfirm by remember { mutableStateOf<Pair<File, String>?>(null) }
    var showQuickSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ── 新手教程（编辑器部分） ──
    val editorPrefsManager = remember { PreferencesManager(context) }
    var showEditorTutorial by remember { mutableStateOf(false) }
    var editorTutorialStep by remember { mutableIntStateOf(0) }
    val finalizedManager = aiViewModel.finalizedManager  // 与 AI 共享同一实例
    var finalizedFiles by remember { mutableStateOf(finalizedManager.getFinalizedFiles(projectId)) }

    // AI 定稿后（面板关闭时）刷新定稿状态
    LaunchedEffect(showAiPanel) {
        if (!showAiPanel) finalizedFiles = finalizedManager.getFinalizedFiles(projectId)
    }

    // AI 每次成功写/删/标记文件后刷新文件树与定稿列表
    LaunchedEffect(fileChangeVersion) {
        if (fileChangeVersion > 0) {
            viewModel.refreshFileTree()
            finalizedFiles = finalizedManager.getFinalizedFiles(projectId)
        }
    }

    // 编辑器教程步骤定义
    val editorTutorialSteps = remember {
        listOf(
            TutorialStep(
                title = "📁 文件管理",
                description = "点击顶部工具栏的 ☰ 图标，打开文件树侧边栏。" +
                        "\n\n在这里可以：\n" +
                        "• 浏览项目的目录结构\n" +
                        "• 新建/重命名/删除文件和文件夹\n" +
                        "• 标记定稿章节（📑 图标），优化 AI 对话效率"
            ),
            TutorialStep(
                title = "↩️ 撤销与重做",
                description = "工具栏的 ↩ 和 ↪ 按钮支持撤销和重做操作。" +
                        "\n\n最多可回溯 30 步编辑历史，写错了随时回退。"
            ),
            TutorialStep(
                title = "🤖 AI 写作助手",
                description = "点击 ✨ 按钮打开 AI 助手面板。" +
                        "\n\n• Plan 模式：让 AI 帮你规划大纲和情节\n" +
                        "• Agent 模式：AI 直接撰写章节正文\n" +
                        "• AI 还能读取和修改你的项目文件"
            ),
            TutorialStep(
                title = "💾 保存与设置",
                description = "工具栏右侧还有两个实用按钮：\n\n" +
                        "• 💾 保存：手动保存当前编辑内容\n" +
                        "• ⚙️ 快捷设置：快速调整字体大小和行间距\n\n" +
                        "提示：系统也支持自动保存，可以在设置中开启。"
            ),
            TutorialStep(
                title = "✍️ 开始写作",
                description = "在最中央的编辑区域直接输入文字，开始你的创作。" +
                        "\n\n编辑器特点：\n" +
                        "• 左侧显示行号，方便定位\n" +
                        "• 双指捏合可缩放字体大小\n" +
                        "• 顶部实时显示字数统计"
            )
        )
    }

    LaunchedEffect(projectName) {
        viewModel.init(projectName, projectId, chapterId, filePath)
        aiViewModel.init(projectName, projectId, { content }, { title.ifEmpty { projectName } })
        // 加载项目统计信息
        val (chapters, words) = ProjectStorageManager.getProjectStats(projectName)
        projectChapterCount = chapters
        projectWordCount = words
        // 检查是否需要显示编辑器教程
        if (!editorPrefsManager.isEditorTutorialCompleted) {
            showEditorTutorial = true
        }
    }

    // 同步 content → textFieldValue（撤销/重做或加载新文件时），光标放在开头
    LaunchedEffect(content) {
        if (textFieldValue.text != content) {
            textFieldValue = TextFieldValue(content, selection = androidx.compose.ui.text.TextRange(0))
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // 同步 ViewModel 状态 ↔ DrawerState
    LaunchedEffect(showFileTree) {
        if (showFileTree) drawerState.open() else drawerState.close()
    }
    // 用户手动关闭 drawer 时同步回 ViewModel
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && showFileTree) viewModel.toggleFileTree()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                FileTreePanel(
                    items = fileTreeItems,
                    onItemClick = { file -> viewModel.openFile(file) },
                    onItemLongClick = { file -> showFileMenu = file },
                    onRefresh = { viewModel.refreshFileTree() },
                    onNewFolder = { newFolderParent = null; showNewFolderDialog = true },
                    onNewFile = { showCreateDialog = null; showCreateDialog = ProjectStorageManager.getProjectDir(projectName) },
                    projectName = projectName,
                    finalizedFiles = finalizedFiles,
                    chapterCount = projectChapterCount,
                    wordCount = projectWordCount
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title.ifEmpty { projectName }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(wordCount, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { if (isModified) viewModel.saveContent(); onBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                            }
                        },
                        actions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.toggleFileTree() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Menu, "文件列表", Modifier.size(20.dp)) }
                                IconButton(onClick = { viewModel.undo() }, enabled = canUndo, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, "撤销", Modifier.size(20.dp)) }
                                IconButton(onClick = { viewModel.redo() }, enabled = canRedo, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.Redo, "重做", Modifier.size(20.dp)) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showAiPanel = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.AutoAwesome, "AI助手", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                                IconButton(onClick = { showQuickSettings = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Settings, "快捷设置", Modifier.size(20.dp)) }
                            }
                        }
                    )
                }
            ) { padding ->
                EditorWithLineNumbers(
                    value = textFieldValue,
                    onValueChange = { v -> textFieldValue = v; viewModel.onContentChanged(v.text) },
                    fontSize = fontSize, lineSpacing = lineSpacing,
                    onFontSizeChange = { viewModel.setFontSize(it) },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }

            // 遮罩（面板可见时显示，在面板下方——先添加 = 下层）
            if (showAiPanel) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showAiPanel = false }
                )
            }

            // 右侧 AI 面板（后添加 = 上层，可交互）
            AnimatedVisibility(
                visible = showAiPanel,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 320.dp)
                        .fillMaxWidth(0.78f)
                        .navigationBarsPadding()
                        .statusBarsPadding(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    AiChatUi(viewModel = aiViewModel, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    // 对话框
    showCreateDialog?.let { dir ->
        CreateFileDialog(dir = dir, onDismiss = { showCreateDialog = null }, onCreate = { n -> viewModel.createFile(dir, n); showCreateDialog = null })
    }
    showRenameDialog?.let { file ->
        RenameFileDialog(file = file, onDismiss = { showRenameDialog = null }, onRename = { n -> viewModel.renameFile(file, n); showRenameDialog = null }, onDelete = { showDeleteConfirm = file; showRenameDialog = null })
    }
    showDeleteConfirm?.let { file ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${file.name}」吗？此操作不可撤销。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteFile(file); showDeleteConfirm = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") } }
        )
    }

    // 取消定稿确认
    showUnmarkConfirm?.let { (file, relPath) ->
        AlertDialog(
            onDismissRequest = { showUnmarkConfirm = null },
            title = { Text("确认取消定稿") },
            text = { Text("确定要取消「${file.name}」的定稿标记吗？\n取消后该章节将不再纳入缓存前缀，后续请求可能增加 Token 消耗。") },
            confirmButton = {
                TextButton(onClick = {
                    finalizedManager.unmarkFinalized(projectId, relPath)
                    finalizedFiles = finalizedFiles - relPath
                    showUnmarkConfirm = null
                }) { Text("确认取消", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showUnmarkConfirm = null }) { Text("保留定稿") } }
        )
    }

    // 文件树长按菜单
    showFileMenu?.let { file ->
        AlertDialog(
            onDismissRequest = { showFileMenu = null },
            title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (file.isDirectory) {
                        TextButton(onClick = { showCreateDialog = file; showFileMenu = null }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Filled.NoteAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("新建文件")
                        }
                    }
                    TextButton(onClick = { showRenameDialog = file; showFileMenu = null }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("重命名")
                    }
                    if (!file.isDirectory) {
                        val relPath = file.absolutePath.removePrefix(ProjectStorageManager.getProjectDir(projectName).absolutePath + "/")
                        val isFinalized = finalizedFiles.contains(relPath)
                        TextButton(onClick = {
                            if (isFinalized) {
                                // 取消定稿需要确认
                                showUnmarkConfirm = Pair(file, relPath)
                                showFileMenu = null
                            } else {
                                finalizedManager.markFinalized(projectId, relPath)
                                finalizedFiles = finalizedFiles + relPath
                                showFileMenu = null
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Bookmark, null, Modifier.size(18.dp), tint = if (isFinalized) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isFinalized) "取消定稿" else "标记定稿", color = if (isFinalized) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                    }
                    TextButton(onClick = { showDeleteConfirm = file; showFileMenu = null }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp)); Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFileMenu = null }) { Text("关闭") } }
        )
    }

    // 新建文件夹对话框
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("新建文件夹") }
        val parentDir = ProjectStorageManager.getProjectDir(projectName)
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(value = folderName, onValueChange = { folderName = it }, label = { Text("文件夹名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { Button(onClick = { if (folderName.isNotBlank()) { File(parentDir, folderName.trim()).mkdirs(); viewModel.refreshFileTree(); showNewFolderDialog = false } }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("取消") } }
        )
    }

    // ── 快捷设置面板 ──
    if (showQuickSettings) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showQuickSettings = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("编辑器设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                // 字体大小
                Text("字体大小：${fontSize.toInt()}", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = fontSize,
                    onValueChange = { viewModel.setFontSize(it) },
                    valueRange = 10f..32f,
                    steps = 21
                )

                Spacer(Modifier.height(16.dp))

                // 行间距
                Text("行间距：${"%.1f".format(lineSpacing)}", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = lineSpacing,
                    onValueChange = { viewModel.setLineSpacing(it) },
                    valueRange = 1f..5f
                )
            }
        }
    }

    // ── 编辑器新手教程遮罩 ──
    if (showEditorTutorial) {
        EditorTutorialOverlay(
            steps = editorTutorialSteps,
            currentStep = editorTutorialStep,
            onNext = { editorTutorialStep++ },
            onSkip = {
                editorPrefsManager.isEditorTutorialCompleted = true
                showEditorTutorial = false
            },
            onFinish = {
                editorPrefsManager.isEditorTutorialCompleted = true
                showEditorTutorial = false
            }
        )
    }
}

// ── 文件树面板 ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreePanel(
    items: List<FileTreeItem>,
    onItemClick: (File) -> Unit,
    onItemLongClick: (File) -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    projectName: String,
    finalizedFiles: Set<String>,
    chapterCount: Int = 0,
    wordCount: Int = 0
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("项目文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onNewFolder, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, "新建文件夹", Modifier.size(20.dp))
                }
                IconButton(onClick = onNewFile, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, "新建文件", Modifier.size(20.dp))
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, "刷新", Modifier.size(20.dp))
                }
            }
        }
        // 项目统计信息
        if (chapterCount > 0 || wordCount > 0) {
            Text(
                "${chapterCount} 章 · ${cn.novelmaker.wg1337.ui.home.formatWordCount(wordCount)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.file.absolutePath }) { item ->
                val relPath = item.file.absolutePath.removePrefix(ProjectStorageManager.getProjectDir(projectName).absolutePath + "/")
                FileTreeItemRow(
                    item = item,
                    onClick = { onItemClick(item.file) },
                    onLongClick = { onItemLongClick(item.file) },
                    isFinalized = finalizedFiles.contains(relPath)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeItemRow(
    item: FileTreeItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isFinalized: Boolean = false
) {
    val indent = (item.level * 16).dp
    val isFolder = item is FileTreeItem.Folder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp + indent, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isFolder) {
            Icon(
                if (item.expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        // 定稿文件显示书签图标，非定稿显示文档图标
        Icon(
            when {
                isFolder -> Icons.Default.Folder
                isFinalized -> Icons.Default.Bookmark
                else -> Icons.Default.Description
            },
            null, Modifier.size(20.dp),
            tint = when {
                isFolder -> MaterialTheme.colorScheme.primary
                isFinalized -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(12.dp))
        Text(
            item.file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── 对话框 ──
@Composable
private fun CreateFileDialog(dir: File, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("新建文件") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 .txt 文件") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("文件名（不含 .txt）") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ── 编辑器（verticalScroll + 行号Canvas + 全展开BasicTextField） ──
@Composable
private fun EditorWithLineNumbers(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    fontSize: Float,
    lineSpacing: Float,
    onFontSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    val gutterWidth = 48.dp
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    var initialFontSize by remember { mutableFloatStateOf(fontSize) }
    LaunchedEffect(fontSize) { initialFontSize = fontSize }

    val textMeasurer = rememberTextMeasurer()
    val textStyle = TextStyle(
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = (fontSize + lineSpacing).sp,
        color = MaterialTheme.colorScheme.onSurface
    )
    val screenW = ctx.resources.displayMetrics.widthPixels
    val maxTextWidth = (screenW - with(density) { 56.dp.toPx() }).toInt().coerceAtLeast(100)
    val measuredHeight = textMeasurer.measure(
        text = value.text.ifEmpty { "\n" },
        style = textStyle,
        maxLines = Int.MAX_VALUE,
        constraints = Constraints(maxWidth = maxTextWidth)
    ).size.height

    val contentHeightDp = with(density) { measuredHeight.toDp() + 16.dp }

    Column(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    onFontSizeChange((initialFontSize * zoom).coerceIn(10f, 32f))
                }
            }
            .verticalScroll(scrollState)
    ) {
        Row(modifier = Modifier.height(contentHeightDp)) {
            // 左侧行号
            Canvas(
                modifier = Modifier
                    .width(gutterWidth)
                    .fillMaxHeight()
            ) {
                val layoutResult = textMeasurer.measure(
                    text = value.text.ifEmpty { "\n" },
                    style = textStyle,
                    maxLines = Int.MAX_VALUE,
                    constraints = Constraints(maxWidth = (size.width - with(density) { 8.dp.toPx() }).toInt().coerceAtLeast(50))
                )
                // 分隔线
                drawLine(dividerColor, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 1f)

                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = with(density) { fontSize.sp.toPx() }
                    typeface = android.graphics.Typeface.MONOSPACE
                    textAlign = android.graphics.Paint.Align.RIGHT
                    color = android.graphics.Color.argb(
                        (gutterColor.alpha * 255).toInt(), (gutterColor.red * 255).toInt(),
                        (gutterColor.green * 255).toInt(), (gutterColor.blue * 255).toInt()
                    )
                }
                for (i in 0 until layoutResult.lineCount) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "${i + 1}",
                        size.width - 8f,
                        layoutResult.getLineBottom(i) - layoutResult.getLineBottom(0).coerceAtMost(0f) + paint.textSize * 0.3f,
                        paint
                    )
                }
            }

            // 右侧编辑器（完全展开，不内部滚动）
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun RenameFileDialog(file: File, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    val originalName = file.name
    var newName by remember { mutableStateOf(originalName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(originalName) },
        text = {
            Column {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("新名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (newName.isNotBlank() && newName != originalName) onRename(newName.trim()) }) { Text("重命名") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
