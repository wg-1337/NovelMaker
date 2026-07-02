package cn.novelmaker.wg1337

import android.app.Application
import cn.novelmaker.wg1337.ui.home.AppContextHolder

class NovelMakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        // 全局捕获未处理异常，写入日志方便排查
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = throwable.stackTraceToString()
            android.util.Log.e("NovelMakerCrash", "Uncaught exception on thread: ${thread.name}\n$stackTrace")
            // 写入文件以便查看
            try {
                val file = java.io.File(filesDir, "crash_log.txt")
                file.writeText("${java.util.Date()}\n$stackTrace")
            } catch (e: Exception) {
                android.util.Log.e("NovelMakerCrash", "Failed to write crash log", e)
            }
            // 重新抛出让系统处理
            throwable.printStackTrace()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
