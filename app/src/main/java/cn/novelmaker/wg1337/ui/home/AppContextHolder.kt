package cn.novelmaker.wg1337.ui.home

import android.content.Context
import cn.novelmaker.wg1337.NovelMakerApp

/**
 * 为 ViewModel 提供 Application Context（绕过依赖注入）
 */
object AppContextHolder {
    lateinit var context: Context
        private set

    fun init(app: NovelMakerApp) {
        context = app.applicationContext
    }
}
