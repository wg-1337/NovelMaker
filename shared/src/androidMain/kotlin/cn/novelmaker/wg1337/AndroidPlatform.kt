package cn.novelmaker.wg1337

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import cn.novelmaker.wg1337.utils.UpdateChecker.ReleaseInfo
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 在 Android 端初始化共享模块的平台服务。
 * 由 androidApp 的 Application.onCreate() 调用。
 */
fun initAndroidPlatform(context: Context) {
    Platform.init(AndroidPlatformServices(context.applicationContext))
    AppLogger.logImpl = { tag, message -> Log.d(tag, message) }
    AppLogger.errorImpl = { tag, message, throwable ->
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}

private class AndroidPlatformServices(private val appContext: Context) : PlatformServices {
    override fun projectsRootDir(): File =
        File(Environment.getExternalStorageDirectory(), "NovelMaker")

    override fun internalDataDir(): File = appContext.filesDir

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("novelmaker_prefs", Context.MODE_PRIVATE)

    override fun keyValueStore(): KeyValueStore = AndroidKeyValueStore(prefs)

    override fun secureSecretStore(): SecureSecretStore = AndroidSecureSecretStore()

    override fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e("AndroidPlatform", "打开网址失败: $url", e)
        }
    }

    override fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 从 Application Context 启动必须给 chooser 加 NEW_TASK，否则点击无反应
            val chooser = Intent.createChooser(intent, "分享备份文件")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)
        } catch (e: Exception) {
            AppLogger.e("AndroidPlatform", "分享文件失败: ${file.name}", e)
        }
    }

    override fun isWindows(): Boolean = false

    override fun applyThemeNightMode(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_NO // 0/1 都按淡色处理
            }
        )
    }

    override fun currentVersionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    override fun installUpdate(info: ReleaseInfo, onProgress: (Int) -> Unit): Boolean {
        if (info.downloadUrl.isEmpty()) return false
        return try {
            val conn = URL(info.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000; conn.readTimeout = 120000
            if (conn.responseCode != 200) return false
            val total = conn.contentLength
            val apkFile = File(appContext.cacheDir, "update_${info.version}.apk")
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
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.e("AndroidPlatform", "下载更新失败", e)
            false
        }
    }
}

private class AndroidKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

/**
 * Android Keystore AES-256-GCM 加密的 API Key 存储。
 * 密文格式：base64(IV):base64(cipherText)
 */
class AndroidSecureSecretStore(
    private val alias: String = "novelmaker_api_key"
) : SecureSecretStore {

    companion object {
        private const val KEYSTORE_NAME = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String? {
        return try {
            val parts = stored.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.w("AndroidSecureSecretStore", "解密失败（密文损坏或密钥已失效）: ${e.message}")
            null
        }
    }
}
