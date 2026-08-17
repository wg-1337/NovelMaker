package cn.novelmaker.wg1337

import cn.novelmaker.wg1337.utils.UpdateChecker.ReleaseInfo
import java.io.File

/**
 * 跨平台键值存储抽象。
 * Android 实现：SharedPreferences
 * Windows 实现：java.util.prefs（注册表 HKCU）
 */
interface KeyValueStore {
    fun getString(key: String, default: String? = null): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String, default: Int = 0): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun getFloat(key: String, default: Float = 0f): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

/**
 * 敏感字符串安全存储抽象。
 * Android 实现：Android Keystore AES-256-GCM
 * Windows 实现：DPAPI（CryptProtectData，绑定当前 Windows 用户）
 */
interface SecureSecretStore {
    fun encrypt(plainText: String): String
    fun decrypt(stored: String): String?
}

/**
 * 平台服务：共享代码获取路径与存储的统一入口。
 */
interface PlatformServices {
    /** 小说项目根目录（Android: /storage/emulated/0/NovelMaker；Windows: 文档\NovelMaker） */
    fun projectsRootDir(): File
    /** 应用内部数据目录（项目元数据、聊天记录、Token 统计等） */
    fun internalDataDir(): File
    fun keyValueStore(): KeyValueStore
    fun secureSecretStore(): SecureSecretStore
    /** 在系统浏览器中打开网址 */
    fun openUrl(url: String)
    /** 分享/导出文件（Android: 系统分享；Windows: 打开所在文件夹并选中） */
    fun shareFile(file: File)
    /** 应用夜间模式：0=跟随系统，1=浅色，2=深色 */
    fun applyThemeNightMode(mode: Int)
    /** 当前应用版本名（用于更新检测） */
    fun currentVersionName(): String
    /** 是否 Windows 桌面端（用于更新包/UI 文案等平台差异） */
    fun isWindows(): Boolean
    /** 下载并启动安装更新包，返回是否成功触发安装 */
    fun installUpdate(info: ReleaseInfo, onProgress: (Int) -> Unit): Boolean
}

/**
 * 平台服务单例，由各平台入口启动时初始化。
 */
object Platform {
    @Volatile
    private var services: PlatformServices? = null

    fun init(services: PlatformServices) {
        this.services = services
    }

    fun projectsRootDir(): File = get().projectsRootDir()
    fun internalDataDir(): File = get().internalDataDir()
    fun keyValueStore(): KeyValueStore = get().keyValueStore()
    fun secureSecretStore(): SecureSecretStore = get().secureSecretStore()
    fun openUrl(url: String) = get().openUrl(url)
    fun shareFile(file: File) = get().shareFile(file)
    fun applyThemeNightMode(mode: Int) = get().applyThemeNightMode(mode)
    fun currentVersionName(): String = get().currentVersionName()
    fun isWindows(): Boolean = get().isWindows()
    fun installUpdate(info: ReleaseInfo, onProgress: (Int) -> Unit): Boolean =
        get().installUpdate(info, onProgress)

    private fun get(): PlatformServices =
        services ?: error("Platform 未初始化：请在各平台入口调用 initAndroidPlatform() / initDesktopPlatform()")
}

/**
 * 共享代码的日志出口。
 * 由平台入口注入实现（Android → android.util.Log；Windows → stdout）。
 */
object AppLogger {
    @Volatile
    var logImpl: ((tag: String, message: String) -> Unit)? = null

    @Volatile
    var errorImpl: ((tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun d(tag: String, message: String) {
        logImpl?.invoke(tag, message) ?: println("D/$tag: $message")
    }

    fun w(tag: String, message: String) {
        logImpl?.invoke(tag, message) ?: println("W/$tag: $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (errorImpl != null) {
            errorImpl!!.invoke(tag, message, throwable)
        } else {
            println("E/$tag: $message")
            throwable?.printStackTrace()
        }
    }
}
