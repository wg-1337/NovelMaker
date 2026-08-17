package cn.novelmaker.wg1337.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun platformDynamicColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? = null

@Composable
actual fun PlatformSystemBarsEffect(darkTheme: Boolean) {
    // Windows 端无需处理系统状态栏
}
