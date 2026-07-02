package cn.novelmaker.wg1337.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import cn.novelmaker.wg1337.data.repository.ProjectRepository
import cn.novelmaker.wg1337.utils.BackupManager
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { ProjectRepository(context) }
    val projects = remember { repo.getAllProjects() }
    var selected by remember { mutableStateOf(projects.associate { it.id to false }.toMutableMap()) }
    var signature by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 弹窗状态
    var backupResult by remember { mutableStateOf<File?>(null) }
    var restoreTarget by remember { mutableStateOf<File?>(null) }
    var restorePwd by remember { mutableStateOf("") }
    var restoreResult by remember { mutableStateOf<String?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var restoreBusy by remember { mutableStateOf(false) }

    val backupDir = remember { File(ProjectStorageManager.getRootDir(), "backups").also { it.mkdirs() } }
    val backups = remember { mutableStateListOf<BackupManager.BackupMeta>().also { it.addAll(BackupManager.listBackups()) } }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                val tmpFile = File(context.cacheDir, "picked_${System.currentTimeMillis()}.nmbak")
                context.contentResolver.openInputStream(it)?.use { input ->
                    tmpFile.outputStream().use { out -> input.copyTo(out) }
                }
                if (tmpFile.exists() && tmpFile.length() > 0) {
                    restoreTarget = tmpFile
                }
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 备份 ──
            SectionTitle("备份")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (projects.isEmpty()) {
                        Text("还没有项目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        projects.forEach { p ->
                            val checked = selected[p.id] ?: false
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = selected.toMutableMap().also { it[p.id] = !checked } }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { v -> selected = selected.toMutableMap().also { it[p.id] = v } })
                                Spacer(Modifier.width(4.dp))
                                Text(p.name, Modifier.weight(1f), fontSize = 14.sp)
                                Text(cn.novelmaker.wg1337.ui.home.formatWordCount(p.wordCount), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row { TextButton(onClick = { selected = selected.mapValues { true }.toMutableMap() }) { Text("全选") }; TextButton(onClick = { selected = selected.mapValues { false }.toMutableMap() }) { Text("取消") } }
                    }

                    HorizontalDivider()

                    OutlinedTextField(value = signature, onValueChange = { signature = it.take(30) }, label = { Text("署名") }, placeholder = { Text("留空则使用项目名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    Button(
                        onClick = {
                            if (password.isBlank()) return@Button
                            val sel = projects.filter { selected[it.id] == true }
                            if (sel.isEmpty()) return@Button
                            busy = true
                            try {
                                val sig = signature.ifBlank { sel.joinToString("_") { it.name } }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                val out = File(backupDir, "${sig}_${System.currentTimeMillis()}.nmbak")
                                BackupManager.backupProjects(context, sel, password, sig, out)
                                backupResult = out
                            } catch (_: Exception) {}
                            busy = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && selected.values.any { it } && password.isNotBlank()
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("备份 (${selected.values.count { it }})")
                    }
                }
            }

            // ── 恢复 ──
            SectionTitle("恢复")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (backups.isEmpty()) {
                        Text("没有备份文件", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    } else {
                        backups.forEach { meta ->
                            Surface(
                                onClick = { restoreTarget = meta.file; restorePwd = ""; restoreResult = null; restoreError = null },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(meta.signature, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text(fmt.format(Date(meta.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Text("${meta.projectNames.size} 个项目：${meta.projectNames.take(3).joinToString("、")}${if (meta.projectNames.size > 3) "…" else ""}",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择备份文件")
                    }
                }
            }
        }
    }

    // ── 备份完成弹窗 ──
    backupResult?.let { file ->
        AlertDialog(
            onDismissRequest = { backupResult = null },
            icon = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("备份完成") },
            text = { Column { Text(file.name, fontSize = 13.sp); Text("NovelMaker/backups/", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) } },
            confirmButton = {
                Button(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_SUBJECT, "NovelMaker 备份 - ${file.name}")
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                }) { Text("分享") }
            },
            dismissButton = { TextButton(onClick = { backupResult = null; backups.clear(); backups.addAll(BackupManager.listBackups()) }) { Text("完成") } }
        )
    }

    // ── 恢复弹窗 ──
    restoreTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("恢复备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val meta = BackupManager.readMeta(file)
                    if (meta != null) {
                        Text(meta.signature, fontWeight = FontWeight.Bold)
                        Text("${meta.projectNames.size} 个项目 · ${fmt.format(Date(meta.timestamp))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(file.name, fontSize = 13.sp)
                    }
                    OutlinedTextField(value = restorePwd, onValueChange = { restorePwd = it }, label = { Text("密码") }, singleLine = true)
                    restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    restoreResult?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (restorePwd.isBlank()) { restoreError = "请输入密码"; return@Button }
                    restoreBusy = true; restoreError = null
                    try {
                        val names = BackupManager.restore(context, file, restorePwd)
                        restoreResult = "已恢复：${names.joinToString("、")}"
                        backups.clear(); backups.addAll(BackupManager.listBackups())
                    } catch (e: SecurityException) { restoreError = "密码错误" }
                    catch (e: Exception) { restoreError = e.message }
                    restoreBusy = false
                }, enabled = !restoreBusy) {
                    if (restoreBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("恢复")
                }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}
