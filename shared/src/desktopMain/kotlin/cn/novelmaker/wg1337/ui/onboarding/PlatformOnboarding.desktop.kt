package cn.novelmaker.wg1337.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.novelmaker.wg1337.utils.PreferencesManager

/**
 * Windows 端引导页：无需存储权限申请，仅欢迎页 + 主题选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun PlatformOnboardingScreen(onFinish: () -> Unit) {
    val prefsManager = remember { PreferencesManager() }
    var selectedTheme by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        prefsManager.themeMode = selectedTheme
                        prefsManager.isOnboardingCompleted = true
                        onFinish()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp)
                ) { Text("进入应用", fontSize = 16.sp) }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("欢迎使用 NovelMaker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("一款轻量级的小说创作工具\n支持 AI 辅助写作，数据全部保存在本机", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            ThemeOptionCard("淡色模式", "清爽明亮的浅色界面", selectedTheme == 0) { selectedTheme = 0 }
            Spacer(Modifier.height(12.dp))
            ThemeOptionCard("深色模式", "护眼省电的暗色界面", selectedTheme == 2) { selectedTheme = 2 }
        }
    }
}

@Composable
private fun ThemeOptionCard(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
