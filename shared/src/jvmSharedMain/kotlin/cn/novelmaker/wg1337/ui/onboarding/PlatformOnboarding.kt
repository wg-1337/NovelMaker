package cn.novelmaker.wg1337.ui.onboarding

import androidx.compose.runtime.Composable

/** 平台引导页（Android 含权限申请，Windows 仅欢迎+主题） */
@Composable
expect fun PlatformOnboardingScreen(onFinish: () -> Unit)
