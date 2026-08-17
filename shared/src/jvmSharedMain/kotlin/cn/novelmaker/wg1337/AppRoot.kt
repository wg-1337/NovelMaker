package cn.novelmaker.wg1337

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import cn.novelmaker.wg1337.ui.common.MarkdownContent
import cn.novelmaker.wg1337.ui.editor.ChapterEditScreen
import cn.novelmaker.wg1337.ui.home.HomeScreen
import cn.novelmaker.wg1337.ui.onboarding.PlatformOnboardingScreen
import cn.novelmaker.wg1337.ui.settings.SettingsScreen
import cn.novelmaker.wg1337.ui.theme.NovelMakerTheme
import cn.novelmaker.wg1337.utils.PreferencesManager
import cn.novelmaker.wg1337.utils.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用内导航目标（用状态导航替换 Navigation Compose，双端行为一致） */
sealed interface AppScreen {
    data object Onboarding : AppScreen
    data object Home : AppScreen
    data object Settings : AppScreen
    data class Editor(
        val projectName: String,
        val projectId: String,
        val chapterId: String = "",
        val filePath: String? = null
    ) : AppScreen
}

/**
 * 双端共用的应用根组件。
 * Android 与 Windows 都从这里启动；横屏三栏布局将在后续迭代中接入。
 */
@Composable
fun AppRoot() {
    val prefs = remember { PreferencesManager() }
    var themeMode by remember { mutableIntStateOf(prefs.themeMode) }
    val darkTheme = when (themeMode) {
        2 -> true
        else -> false // 0=淡色模式(默认)，1 也兼容旧浅色设置
    }

    LaunchedEffect(themeMode) {
        Platform.applyThemeNightMode(themeMode)
    }

    var screen by remember {
        mutableStateOf<AppScreen>(
            if (prefs.isOnboardingCompleted) AppScreen.Home else AppScreen.Onboarding
        )
    }
    var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    // 返回键：编辑器/设置返回首页；首页和引导页保留系统默认行为（退出）
    val canGoBack = screen is AppScreen.Editor || screen == AppScreen.Settings
    PlatformBackHandler(enabled = canGoBack) {
        screen = when (screen) {
            is AppScreen.Editor -> AppScreen.Home
            AppScreen.Settings -> AppScreen.Home
            else -> AppScreen.Home
        }
    }

    // 启动时检查更新
    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }
        if (info != null && !UpdateChecker.isIgnored(info.version)) {
            updateInfo = info
        }
    }

    NovelMakerTheme(darkTheme = darkTheme, dynamicColor = false) {
        // Windows 没有默认的 ViewModelStoreOwner，由根组件统一提供；Android 同样适用
        val viewModelStoreOwner = remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val isLandscape =
                    with(density) { LocalWindowInfo.current.containerSize.width.toDp() } >= 840.dp
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    label = "screen"
                ) { target ->
                    val current = target
                    if (!isLandscape || current == AppScreen.Onboarding || current is AppScreen.Editor) {
                        when (current) {
                            AppScreen.Onboarding -> PlatformOnboardingScreen(
                                onFinish = {
                                    prefs.isOnboardingCompleted = true
                                    screen = AppScreen.Home
                                }
                            )
                            AppScreen.Home -> HomeScreen(
                                onProjectClick = { name, id -> screen = AppScreen.Editor(name, id) },
                                onSettingsClick = { screen = AppScreen.Settings }
                            )
                            AppScreen.Settings -> SettingsScreen(
                                onBack = { screen = AppScreen.Home }
                            )
                            is AppScreen.Editor -> ChapterEditScreen(
                                projectName = current.projectName,
                                projectId = current.projectId,
                                chapterId = current.chapterId,
                                filePath = current.filePath,
                                onBack = { screen = AppScreen.Home }
                            )
                        }
                    } else {
                        // 横屏（平板 / Windows）：首页与设置使用左侧导航栏
                        Row(Modifier.fillMaxSize()) {
                            NavigationRail(
                                modifier = Modifier.fillMaxHeight(),
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Spacer(Modifier.height(16.dp))
                                NavigationRailItem(
                                    selected = current == AppScreen.Home,
                                    onClick = { screen = AppScreen.Home },
                                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                                    label = { Text("项目") }
                                )
                                NavigationRailItem(
                                    selected = current == AppScreen.Settings,
                                    onClick = { screen = AppScreen.Settings },
                                    icon = { Icon(Icons.Default.Settings, null) },
                                    label = { Text("设置") }
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                when (current) {
                                    AppScreen.Home -> HomeScreen(
                                        onProjectClick = { name, id -> screen = AppScreen.Editor(name, id) },
                                        onSettingsClick = { screen = AppScreen.Settings }
                                    )
                                    AppScreen.Settings -> SettingsScreen(
                                        onBack = { screen = AppScreen.Home }
                                    )
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }

        updateInfo?.let { info ->
            UpdateDialog(
                info = info,
                onDismiss = { updateInfo = null },
                onIgnore = {
                    UpdateChecker.ignoreVersion(info.version)
                    updateInfo = null
                }
            )
        }
    }
}

@Composable
private fun UpdateDialog(
    info: UpdateChecker.ReleaseInfo,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit
) {
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("发现新版本 ${info.version}") },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                if (info.body.isNotEmpty()) {
                    MarkdownContent(info.body, textStyle = MaterialTheme.typography.bodySmall)
                }
                if (downloading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("下载中 $progress%", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { Platform.openUrl(info.url) },
                    modifier = Modifier.weight(1f)
                ) { Text("网页下载") }
                TextButton(
                    onClick = {
                        downloading = true
                        scope.launch {
                            val ok = UpdateChecker.downloadAndInstall(info) { progress = it }
                            if (ok) onDismiss() else downloading = false
                        }
                    },
                    enabled = !downloading,
                    modifier = Modifier.weight(1f)
                ) { Text(if (downloading) "下载中…" else "下载安装") }
            }
        },
        dismissButton = {
            TextButton(onClick = onIgnore, enabled = !downloading) { Text("忽略") }
        }
    )
}
