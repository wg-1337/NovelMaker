package cn.novelmaker.wg1337.utils

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检测器（Android / Windows 共用）
 * 通过 GitHub Releases API 检测新版本；下载与安装由各平台实现。
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
    suspend fun checkForUpdate(): ReleaseInfo? {
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
                val current = parseVersionCode(Platform.currentVersionName())
                if (code > current) {
                    val assets = latest.optJSONArray("assets")
                    var dlUrl = ""
                    if (assets != null) {
                        val wantApk = !Platform.isWindows()
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            val name = a.optString("name", "")
                            val ok = if (wantApk) name.endsWith(".apk")
                                     else name.endsWith(".msi") || name.endsWith(".exe")
                            if (ok) {
                                dlUrl = a.optString("browser_download_url", "")
                                // Windows 优先 MSI，避免误选 EXE；两者任一即可
                                if (wantApk || name.endsWith(".msi")) break
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
            } catch (e: Exception) {
                AppLogger.w("UpdateChecker", "检查更新失败: ${e.message}")
                null
            }
        }
    }

    /** 下载并安装更新（APK 或 MSI/EXE，由平台实现） */
    suspend fun downloadAndInstall(info: ReleaseInfo, onProgress: (Int) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            Platform.installUpdate(info, onProgress)
        }
    }

    fun ignoreVersion(version: String) {
        Platform.keyValueStore().putString(PREFS_KEY_IGNORED, version)
    }

    fun isIgnored(version: String): Boolean {
        return Platform.keyValueStore().getString(PREFS_KEY_IGNORED, null) == version
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
