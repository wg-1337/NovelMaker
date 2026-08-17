package cn.novelmaker.wg1337

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingFile(intent)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingFile(intent)
    }

    /** 打开 .nmbak 备份文件：保存到 NovelMaker/backups/，由用户在备份管理中恢复 */
    private fun handleIncomingFile(intent: Intent) {
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        try {
            val backupsDir = File(ProjectStorageManager.getRootDir(), "backups").also { it.mkdirs() }
            val dest = File(backupsDir, "received_${System.currentTimeMillis()}.nmbak")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            if (dest.exists()) {
                Toast.makeText(
                    this,
                    "备份文件已保存到 NovelMaker/backups/，请前往 设置 → 备份与恢复 → 恢复 中手动恢复",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Exception) {}
    }
}
