package cn.novelmaker.wg1337.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cn.novelmaker.wg1337.AppLogger
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Windows 图片选择器（AWT FileDialog，与备份文件选择器保持一致的交互）。
 */
@Composable
actual fun rememberImagePicker(onPicked: (Pair<ByteArray, String>?) -> Unit): () -> Unit = remember {
    {
        try {
            val dialog = FileDialog(null as Frame?, "选择图片", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                ext in DeepSeekApi.SUPPORTED_IMAGE_EXTENSIONS
            }
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir == null || name == null) {
                onPicked(null)
            } else {
                val file = File(dir, name)
                val bytes = if (file.exists()) file.readBytes() else null
                if (bytes == null || bytes.isEmpty()) onPicked(null)
                else onPicked(bytes to file.name)
            }
        } catch (e: Exception) {
            AppLogger.e("ImagePicker", "选择图片失败", e)
            onPicked(null)
        }
    }
}
