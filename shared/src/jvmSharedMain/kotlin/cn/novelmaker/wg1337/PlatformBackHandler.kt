package cn.novelmaker.wg1337

import androidx.compose.runtime.Composable

/** 平台返回键处理：Android 监听返回键；Windows 空实现 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
