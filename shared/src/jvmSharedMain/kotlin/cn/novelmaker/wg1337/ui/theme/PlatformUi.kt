package cn.novelmaker.wg1337.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** 平台动态取色；不支持时返回 null，由调用方回退到内置配色 */
@Composable
expect fun platformDynamicColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme?

/** 平台系统栏样式定制 */
@Composable
expect fun PlatformSystemBarsEffect(darkTheme: Boolean)
