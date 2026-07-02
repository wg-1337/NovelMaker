package cn.novelmaker.wg1337.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.novelmaker.wg1337.data.repository.ProjectRepository
import cn.novelmaker.wg1337.ui.home.AppContextHolder
import cn.novelmaker.wg1337.ui.home.formatWordCount
import cn.novelmaker.wg1337.utils.ProjectStorageManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectName: String,
    onBack: () -> Unit,
    onOpenEditor: (String, String) -> Unit
) {
    val context = AppContextHolder.context
    val projectRepo = remember { ProjectRepository(context) }
    val storageManager = remember { ProjectStorageManager }

    var chapterCount by remember { mutableIntStateOf(0) }
    var wordCount by remember { mutableIntStateOf(0) }
    var projectId by remember { mutableStateOf("") }

    LaunchedEffect(projectName) {
        val (chapters, words) = storageManager.getProjectStats(projectName)
        chapterCount = chapters
        wordCount = words
        projectRepo.getAllProjects().find { it.name == projectName }?.let {
            projectId = it.id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 统计卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem("章节", chapterCount.toString())
                    StatItem("总字数", formatWordCount(wordCount))
                }
            }

            Spacer(Modifier.height(32.dp))

            // 打开编辑器按钮
            Button(
                onClick = { onOpenEditor(projectName, projectId) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.Edit, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("打开编辑器", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
