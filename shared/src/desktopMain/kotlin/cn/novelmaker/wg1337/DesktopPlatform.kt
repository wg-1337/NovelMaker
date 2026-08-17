package cn.novelmaker.wg1337

import cn.novelmaker.wg1337.utils.UpdateChecker.ReleaseInfo
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.prefs.Preferences

/**
 * 在 Windows 端初始化共享模块的平台服务。
 * 由 desktopApp 的 main() 调用。
 */
fun initDesktopPlatform() {
    Platform.init(DesktopPlatformServices())
    AppLogger.logImpl = { tag, message -> println("[$tag] $message") }
    AppLogger.errorImpl = { tag, message, throwable ->
        println("[$tag] $message")
        throwable?.printStackTrace()
    }
}

private class DesktopPlatformServices : PlatformServices {
    override fun projectsRootDir(): File {
        val home = System.getProperty("user.home") ?: "."
        return File(File(home, "Documents"), "NovelMaker")
    }

    override fun internalDataDir(): File {
        val localAppData = System.getenv("LOCALAPPDATA")
        val base = if (!localAppData.isNullOrBlank()) {
            File(localAppData)
        } else {
            File(System.getProperty("user.home") ?: ".", ".novelmaker")
        }
        return File(base, "NovelMaker").apply { mkdirs() }
    }

    override fun keyValueStore(): KeyValueStore =
        JavaPrefsKeyValueStore(Preferences.userRoot().node("novelmaker"))

    override fun secureSecretStore(): SecureSecretStore = DpapiSecureSecretStore()

    override fun openUrl(url: String) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (e: Exception) {
            AppLogger.e("DesktopPlatform", "打开网址失败: $url", e)
        }
    }

    override fun shareFile(file: File) {
        try {
            // 在资源管理器中打开并选中该文件（Windows 没有系统分享面板）
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
        } catch (e: Exception) {
            AppLogger.e("DesktopPlatform", "打开文件位置失败: ${file.name}", e)
        }
    }

    override fun isWindows(): Boolean = true

    override fun applyThemeNightMode(mode: Int) {
        // 桌面端主题直接由 Compose 依据偏好渲染，无需额外系统调用
    }

    override fun currentVersionName(): String = "1.6.0"

    override fun installUpdate(info: ReleaseInfo, onProgress: (Int) -> Unit): Boolean {
        if (info.downloadUrl.isEmpty()) return false
        return try {
            val url = URL(info.downloadUrl)
            val isMsi = url.path.endsWith(".msi", ignoreCase = true)
            val ext = if (isMsi) ".msi" else ".exe"
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000; conn.readTimeout = 120000
            if (conn.responseCode != 200) return false
            val total = conn.contentLength
            val target = File(
                System.getProperty("java.io.tmpdir"),
                "NovelMaker_update_${info.version}$ext"
            )
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
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
            if (isMsi) {
                ProcessBuilder("msiexec", "/i", target.absolutePath).start()
            } else {
                // 直接启动安装程序，避免 Desktop.open 的安全提示
                ProcessBuilder(target.absolutePath).start()
            }
            true
        } catch (e: Exception) {
            AppLogger.e("DesktopPlatform", "下载更新失败", e)
            false
        }
    }
}

private class JavaPrefsKeyValueStore(private val prefs: Preferences) : KeyValueStore {
    override fun getString(key: String, default: String?): String? = prefs.get(key, default)
    override fun putString(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
    }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.putInt(key, value)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) = prefs.putLong(key, value)
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = prefs.putFloat(key, value)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.putBoolean(key, value)
    override fun remove(key: String) = prefs.remove(key)
}

/**
 * Windows DPAPI 加密存储：绑定当前 Windows 用户，无需额外主密码。
 */
class DpapiSecureSecretStore : SecureSecretStore {
    override fun encrypt(plainText: String): String =
        Base64.getEncoder().encodeToString(
            Crypt32Util.cryptProtectData(plainText.toByteArray(Charsets.UTF_8))
        )

    override fun decrypt(stored: String): String? = try {
        String(
            Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored)),
            Charsets.UTF_8
        )
    } catch (e: Exception) {
        AppLogger.w("DpapiSecureSecretStore", "DPAPI 解密失败: ${e.message}")
        null
    }
}
