package cn.novelmaker.wg1337

import java.io.File
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.rememberNavController
import cn.novelmaker.wg1337.ui.navigation.NovelMakerNavGraph
import cn.novelmaker.wg1337.ui.navigation.Routes
import cn.novelmaker.wg1337.ui.theme.NovelMakerTheme
import cn.novelmaker.wg1337.utils.PreferencesManager
import cn.novelmaker.wg1337.utils.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Log.d(TAG, "onCreate start")
            super.onCreate(savedInstanceState)
            handleIncomingFile(intent)

            val prefsManager = PreferencesManager(this)
            val themeMode = prefsManager.themeMode
            val isOnboardingCompleted = prefsManager.isOnboardingCompleted

            // 设置夜间模式
            when (themeMode) {
                1 -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
                2 -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                else -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }

            enableEdgeToEdge()

            setContent {
                val context = LocalContext.current
                var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        UpdateChecker.checkForUpdate(context)
                    }?.let { release ->
                        if (!UpdateChecker.isIgnored(context, release.version)) {
                            updateInfo = release
                        }
                    }
                }

                NovelMakerTheme(
                    darkTheme = when (themeMode) {
                        1 -> false
                        2 -> true
                        else -> isSystemInDarkTheme()
                    },
                    dynamicColor = themeMode == 0
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        val startDest = if (isOnboardingCompleted) Routes.HOME else Routes.ONBOARDING
                        NovelMakerNavGraph(
                            navController = navController,
                            startDestination = startDest
                        )
                    }

                    // 更新提示弹窗
                    updateInfo?.let { info ->
                        var downloading by remember { mutableStateOf(false) }
                        var progress by remember { mutableIntStateOf(0) }
                        val dark = isSystemInDarkTheme()
                        AlertDialog(
                            onDismissRequest = { updateInfo = null },
                            title = { Text("发现新版本 ${info.version}") },
                            text = {
                                Column(Modifier.heightIn(max = 400.dp)) {
                                    if (info.body.isNotEmpty()) {
                                        AndroidView(
                                            factory = { ctx ->
                                                android.webkit.WebView(ctx).apply {
                                                    settings.apply { javaScriptEnabled = false; setSupportZoom(false) }
                                                    loadDataWithBaseURL(null, markdownToHtml(info.body, dark), "text/html", "UTF-8", null)
                                                    setBackgroundColor(0)
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (downloading) {
                                        Spacer(Modifier.height(6.dp))
                                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                                        Text("下载中 $progress%", fontSize = 12.sp)
                                    }
                                }
                            },
                            confirmButton = {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.url))
                                        startActivity(intent)
                                    }, modifier = Modifier.weight(1f)) { Text("浏览器下载") }
                                    TextButton(onClick = {
                                        downloading = true
                                        kotlinx.coroutines.MainScope().launch {
                                            val ok = UpdateChecker.downloadAndInstall(context, info) { progress = it }
                                            if (!ok) downloading = false
                                            updateInfo = null
                                        }
                                    }, enabled = !downloading, modifier = Modifier.weight(1f)) {
                                        Text(if (downloading) "下载中…" else "下载并安装")
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    UpdateChecker.ignoreVersion(context, info.version)
                                    updateInfo = null
                                }) { Text("忽略") }
                            }
                        )
                    }
                }
            }

            Log.d(TAG, "onCreate end")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crashed", e)
            throw e
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingFile(intent)
    }

    private fun handleIncomingFile(intent: Intent) {
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        try {
            val dest = java.io.File(
                java.io.File(cn.novelmaker.wg1337.utils.ProjectStorageManager.getRootDir(), "backups").also { it.mkdirs() },
                "received_${System.currentTimeMillis()}.nmbak"
            )
            contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            if (dest.exists()) {
                android.widget.Toast.makeText(this, "备份文件已保存到 NovelMaker/backups/，请前往设置 > 备份与恢复 > 恢复中手动恢复", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }
}

/** 简单 Markdown 转 HTML，适配明暗主题 */
private fun markdownToHtml(md: String, dark: Boolean): String {
    val bg = if (dark) "#1C1B1E" else "#FFFBFF"
    val fg = if (dark) "#E6E1E5" else "#1C1B1E"
    val hc = if (dark) "#E7B8DA" else "#7B3E6F"
    val codeBg = if (dark) "#2D2D2D" else "#F0F0F0"
    val body = md
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("`([^`]+)`"), "<code style=\"background:$codeBg;padding:1px 4px;border-radius:3px\">$1</code>")
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace("\n", "<br>")
    return """
<!DOCTYPE html><html><head><meta charset="UTF-8"><style>
body{margin:4px 8px;font-size:13px;font-family:sans-serif;color:$fg;background:$bg;line-height:1.5}
h2,h3,h4{color:$hc;margin:8px 0 4px}
li{margin:2px 0 2px 16px}
br{display:block;margin:2px 0}
</style></head><body>$body</body></html>
""".trimIndent()
}