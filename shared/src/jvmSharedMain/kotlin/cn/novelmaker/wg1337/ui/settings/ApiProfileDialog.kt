package cn.novelmaker.wg1337.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.novelmaker.wg1337.ui.ai.ApiProfileManager
import cn.novelmaker.wg1337.ui.ai.DeepSeekApi

/**
 * 多 API 配置管理对话框。
 *
 * 支持保存多套 API 配置（名称 / 地址 / 模型 / 密钥）并切换「当前启用」项，
 * 便于在官方直连、中转站、不同模型之间来回切换。
 *
 * 密钥经平台安全存储加密（Android Keystore / Windows DPAPI），不写进配置列表 JSON。
 */
@Composable
fun ApiProfileDialog(onDismiss: () -> Unit) {
    // 每次改动后 +1 触发从磁盘重新读取
    var refreshKey by remember { mutableIntStateOf(0) }
    val profiles = remember(refreshKey) { ApiProfileManager.loadProfiles() }
    val activeId = remember(refreshKey) { ApiProfileManager.getActiveId() }

    // 正在编辑的配置；null 表示显示列表
    var editing by remember { mutableStateOf<ApiProfileManager.ApiProfile?>(null) }
    var confirmDelete by remember { mutableStateOf<ApiProfileManager.ApiProfile?>(null) }

    val current = editing
    if (current != null) {
        ApiProfileEditor(
            initial = current,
            isNew = profiles.none { it.id == current.id },
            onCancel = { editing = null },
            onSave = { updated ->
                ApiProfileManager.upsert(updated)
                refreshKey++
                editing = null
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API 配置", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "可保存多套配置，会话使用「当前启用」的那一套。密钥经系统安全存储加密保存。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (profiles.isEmpty()) {
                    Text("暂无配置", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    profiles.forEach { p ->
                        val isActive = p.id == activeId
                        Surface(
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { ApiProfileManager.setActive(p.id); refreshKey++ }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isActive,
                                    onClick = { ApiProfileManager.setActive(p.id); refreshKey++ }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            p.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (p.apiKey.isBlank()) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.Warning, null, Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                "未填密钥",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Text(
                                        p.summary,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { editing = p },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Edit, "编辑", Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { confirmDelete = p },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete, "删除", Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // 新建：预填官方默认地址与模型
                editing = ApiProfileManager.ApiProfile(
                    name = "配置 ${profiles.size + 1}",
                    baseUrl = DeepSeekApi.DEFAULT_BASE_URL,
                    model = DeepSeekApi.MODEL_FLASH,
                    apiKey = profiles.firstOrNull()?.apiKey.orEmpty()
                )
            }) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除配置") },
            text = { Text("确定要删除「${p.name}」吗？其密钥也会一并移除。") },
            confirmButton = {
                TextButton(onClick = {
                    ApiProfileManager.delete(p.id)
                    confirmDelete = null
                    refreshKey++
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 单套配置的编辑表单 */
@Composable
private fun ApiProfileEditor(
    initial: ApiProfileManager.ApiProfile,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (ApiProfileManager.ApiProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    val modelSupportsVision = DeepSeekApi.supportsVision(model)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (isNew) "新建 API 配置" else "编辑 API 配置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：官方直连 / 中转站") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            "当前有效模型：${DeepSeekApi.SUPPORTED_MODELS.joinToString(" / ")}",
                            fontSize = 10.sp
                        )
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeepSeekApi.SUPPORTED_MODELS.forEach { m ->
                        FilterChip(
                            selected = DeepSeekApi.normalizeModel(model) == m,
                            onClick = { model = m },
                            label = { Text(m) },
                            leadingIcon = {
                                Icon(
                                    if (DeepSeekApi.supportsVision(m)) {
                                        Icons.Default.Image
                                    } else {
                                        Icons.Default.Psychology
                                    },
                                    null,
                                    Modifier.size(15.dp)
                                )
                            }
                        )
                    }
                }
                Text(
                    if (modelSupportsVision) {
                        "${DeepSeekApi.MODEL_FLASH} 支持图像理解，可在编辑器中发送图片。"
                    } else {
                        "${DeepSeekApi.MODEL_V4_PRO} 不支持图像理解；需要看图请切换到 ${DeepSeekApi.MODEL_FLASH}。"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (keyVisible) "隐藏" else "显示"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { "未命名配置" },
                            baseUrl = baseUrl.ifBlank { DeepSeekApi.DEFAULT_BASE_URL },
                            model = DeepSeekApi.normalizeModel(model),
                            apiKey = apiKey.trim()
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}
