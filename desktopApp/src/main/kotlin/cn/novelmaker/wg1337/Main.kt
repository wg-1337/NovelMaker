package cn.novelmaker.wg1337

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image

fun main() {
    initDesktopPlatform()
    application {
        // 加载窗口标题栏图标（与 EXE 图标一致）
        val icon = remember {
            try {
                val bytes = checkNotNull(
                    Thread.currentThread().contextClassLoader?.getResourceAsStream("novelmaker.png")
                ).use { it.readBytes() }
                BitmapPainter(Image.makeFromEncoded(bytes)!!.toComposeImageBitmap())
            } catch (_: Exception) {
                null
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "NovelMaker",
            icon = icon,
            state = rememberWindowState(width = 1280.dp, height = 800.dp)
        ) {
            AppRoot()
        }
    }
}
