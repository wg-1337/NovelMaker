package cn.novelmaker.wg1337.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberBackupFilePicker(onPicked: (File?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                val tmpFile = File(context.cacheDir, "picked_${System.currentTimeMillis()}.nmbak")
                context.contentResolver.openInputStream(it)?.use { input ->
                    tmpFile.outputStream().use { out -> input.copyTo(out) }
                }
                onPicked(if (tmpFile.exists() && tmpFile.length() > 0) tmpFile else null)
            } catch (_: Exception) {
                onPicked(null)
            }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}
