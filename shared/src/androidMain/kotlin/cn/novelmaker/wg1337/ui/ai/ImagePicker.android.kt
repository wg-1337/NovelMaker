package cn.novelmaker.wg1337.ui.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.novelmaker.wg1337.AppLogger

/**
 * Android 图片选择器。
 *
 * 使用 GetContent 而不是 OpenDocument：GetContent 返回的 URI 可直接读取，
 * 且能拿到文件名用于推断格式。图片格式最终仍由文件内容判定（Vision 指南）。
 */
@Composable
actual fun rememberImagePicker(onPicked: (Pair<ByteArray, String>?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                onPicked(null)
            } else {
                onPicked(bytes to (queryDisplayName(context, uri) ?: "image"))
            }
        } catch (e: Exception) {
            AppLogger.e("ImagePicker", "读取所选图片失败", e)
            onPicked(null)
        }
    }
    return remember(launcher) { { launcher.launch("image/*") } }
}

/** 查询 content URI 的显示名（用于推断扩展名） */
private fun queryDisplayName(
    context: android.content.Context,
    uri: Uri
): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (_: Exception) {
    null
}
