package cn.novelmaker.wg1337.ui.settings

import androidx.compose.runtime.Composable
import java.io.File

/**
 * 备份文件选择器。
 * Android：系统文件选择器（SAF）
 * Windows：AWT FileDialog
 */
@Composable
expect fun rememberBackupFilePicker(onPicked: (File?) -> Unit): () -> Unit
