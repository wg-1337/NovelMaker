package cn.novelmaker.wg1337.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检测器
 * 通过 GitHub Releases API 检测新版本并下载安装
 */
object UpdateChecker {

    private const val GITHUB_API = "https://api.github.com/repos/wg-1337/NovelMaker/releases"
    private const val PREFS_KEY_IGNORED = "update_ignored_version"

    data class ReleaseInfo(
        val version: String,
        val versionCode: Int,
        val name: String,
        val body: String,
        val url: String,
        val downloadUrl: String
    )

    /** 异步检查是否有新版本 */
    suspend fun checkForUpdate(context: Context): ReleaseInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@withContext null
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val latest = JSONArray(json).getJSONObject(0)
                val tag = latest.getString("tag_name")
                val code = parseVersionCode(tag)
                val current = parseVersionCode(
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
                )
                if (code > current) {
                    val assets = latest.optJSONArray("assets")
                    var dlUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk")) {
                                dlUrl = a.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                    ReleaseInfo(
                        version = tag,
                        versionCode = code,
                        name = latest.optString("name", tag),
                        body = latest.optString("body", "").take(2000),
                        url = latest.optString("html_url", "https://github.com/wg-1337/NovelMaker/releases"),
                        downloadUrl = dlUrl
                    )
                } else null
            } catch (_: Exception) { null }
        }
    }

    /** 下载 APK 并调用系统安装器 */
    suspend fun downloadAndInstall(context: Context, info: ReleaseInfo, onProgress: (Int) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (info.downloadUrl.isEmpty()) return@withContext false
                val url = URL(info.downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30000; conn.readTimeout = 120000
                if (conn.responseCode != 200) return@withContext false
                val total = conn.contentLength
                val apkFile = File(context.cacheDir, "update_${info.version}.apk")
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { out ->
                        val buf = ByteArray(8192)
                        var read: Int; var downloaded = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) onProgress((downloaded * 100 / total).toInt())
                        }
                    }
                }
                conn.disconnect()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) { false }
        }
    }

    fun ignoreVersion(context: Context, version: String) {
        context.getSharedPreferences("novelmaker_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY_IGNORED, version).apply()
    }

    fun isIgnored(context: Context, version: String): Boolean {
        return context.getSharedPreferences("novelmaker_prefs", Context.MODE_PRIVATE)
            .getString(PREFS_KEY_IGNORED, null) == version
    }

    private fun parseVersionCode(tag: String): Int {
        val nums = tag.removePrefix("v").split(".")
        return try {
            (nums.getOrElse(0) { "0" }.toInt() * 10000) +
            (nums.getOrElse(1) { "0" }.toInt() * 100) +
            (nums.getOrElse(2) { "0" }.toInt())
        } catch (_: Exception) { 0 }
    }
}
