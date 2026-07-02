package cn.novelmaker.wg1337.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.novelmaker.wg1337.data.model.Project
import cn.novelmaker.wg1337.utils.BackupManager
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onProjectClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val projects by viewModel.projects.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<Project?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Project?>(null) }
    var showBackupDialog by remember { mutableStateOf<Project?>(null) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadProjects() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NOVELMAKER", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Add, "创建项目")
            }
        }
    ) { padding ->
        if (projects.isEmpty()) {
            // 空状态
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook, null,
                    Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(16.dp))
                Text("还没有项目", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击右下角的 + 号创建你的第一个小说项目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onProjectClick(project.name) },
                        onRename = { showRenameDialog = project },
                        onDelete = { showDeleteConfirm = project },
                        onBackup = { showBackupDialog = project }
                    )
                }
            }
        }
    }

    // 创建项目对话框
    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            viewModel = viewModel
        )
    }

    // 重命名对话框
    showRenameDialog?.let { project ->
        RenameProjectDialog(
            project = project,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                viewModel.renameProject(project, newName)
                showRenameDialog = null
            }
        )
    }

    // 删除确认对话框
    showDeleteConfirm?.let { project ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除项目") },
            text = { Text("确定要删除「${project.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(project)
                    showDeleteConfirm = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            }
        )
    }

    // 备份密码对话框
    showBackupDialog?.let { project ->
        var pwd by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showBackupDialog = null },
            title = { Text("备份项目「${project.name}」") },
            text = {
                Column {
                    Text("设置备份密码（请牢记）：", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pwd, onValueChange = { pwd = it },
                        label = { Text("密码") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    msg?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pwd.isBlank()) { msg = "密码不能为空"; return@TextButton }
                    val backupDir = File(ProjectStorageManager.getRootDir(), "backups")
                    backupDir.mkdirs()
                    val file = File(backupDir, "${project.name}_${System.currentTimeMillis()}.nmbak")
                    val ok = BackupManager.backup(context, project, pwd, project.name, file)
                    if (ok) {
                        showBackupDialog = null
                        backupMsg = "✅ 备份已保存到 NovelMaker/backups/"
                    } else {
                        msg = "❌ 备份失败"
                    }
                }) { Text("开始备份") }
            },
            dismissButton = { TextButton(onClick = { showBackupDialog = null }) { Text("取消") } }
        )
    }

    // 操作结果提示
    backupMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { backupMsg = null },
            title = { Text("操作结果") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { backupMsg = null }) { Text("确定") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBackup: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook, null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${project.chapterCount}章节 · ${formatWordCount(project.wordCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { showMenu = false; onRename() })
                    DropdownMenuItem(text = { Text("备份项目") }, onClick = { showMenu = false; onBackup() })
                    DropdownMenuItem(
                        text = { Text("删除项目", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    viewModel: HomeViewModel
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建新项目") },
        text = {
            Column {
                Text(
                    "输入小说名称，系统将自动创建项目目录结构",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("小说名称") },
                    placeholder = { Text("我的小说") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (name.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            "📁 即将创建的目录结构：\n/NovelMaker/\n  └── $name/\n      ├── 大纲/\n      ├── 提示词/\n      └── 小说主体/",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    name.isBlank() -> error = "请输入小说名称"
                    name.length > 50 -> error = "名称长度应在1-50个字符之间"
                    else -> viewModel.createProject(name.trim()) { success, msg ->
                        if (success) onDismiss() else error = msg
                    }
                }
            }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RenameProjectDialog(
    project: Project,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(project.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名项目") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("新项目名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (newName.isNotBlank()) onConfirm(newName.trim()) },
                enabled = newName.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

internal fun formatWordCount(count: Int): String = when {
    count >= 10000 -> "${count / 10000}.${(count % 10000) / 1000}万"
    count >= 1000 -> "${count / 1000}.${(count % 1000) / 100}千"
    else -> "${count}字"
}
