package cn.novelmaker.wg1337

import android.app.Application
import android.os.Process
import java.io.File
import java.util.Date

class NovelMakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initAndroidPlatform(this)

        // 全局捕获未处理异常，写入日志方便排查
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = throwable.stackTraceToString()
            android.util.Log.e("NovelMakerCrash", "Uncaught exception on thread: ${thread.name}\n$stackTrace")
            try {
                val file = File(filesDir, "crash_log.txt")
                file.writeText("${Date()}\n$stackTrace")
            } catch (_: Exception) {}
            throwable.printStackTrace()
            Process.killProcess(Process.myPid())
        }
    }
}
