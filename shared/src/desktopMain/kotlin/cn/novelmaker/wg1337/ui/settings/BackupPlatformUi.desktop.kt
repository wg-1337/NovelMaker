package cn.novelmaker.wg1337.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberBackupFilePicker(onPicked: (File?) -> Unit): () -> Unit = remember {
    {
        val dialog = FileDialog(null as Frame?, "选择备份文件（.nmbak）", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".nmbak") }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        onPicked(if (dir != null && name != null) File(dir, name) else null)
    }
}
