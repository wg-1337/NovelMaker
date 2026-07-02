package cn.novelmaker.wg1337.ui.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.novelmaker.wg1337.data.model.Chapter
import cn.novelmaker.wg1337.data.repository.ChapterRepository
import cn.novelmaker.wg1337.data.repository.ProjectRepository
import cn.novelmaker.wg1337.ui.home.AppContextHolder
import cn.novelmaker.wg1337.utils.PreferencesManager
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class EditorViewModel : ViewModel() {

    companion object {
        private const val TAG = "EditorViewModel"
        private const val MAX_UNDO_SIZE = 30
    }

    private val context = AppContextHolder.context
    private val chapterRepo = ChapterRepository(context)
    private val projectRepo = ProjectRepository(context)
    private val prefsManager = PreferencesManager(context)

    // ── 项目上下文 ──
    var projectName: String = ""
        private set
    var projectId: String = ""
        private set

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    // ── 编辑器状态 ──
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private val _wordCount = MutableStateFlow("0 字")
    val wordCount: StateFlow<String> = _wordCount.asStateFlow()

    // ── 撤销/重做 ──
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var isUndoRedo = false

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    // ── 编辑器样式 ──
    private val _fontSize = MutableStateFlow(prefsManager.editorFontSize)
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _lineSpacing = MutableStateFlow(prefsManager.editorLineSpacing)
    val lineSpacing: StateFlow<Float> = _lineSpacing.asStateFlow()

    // ── 文件树 ──
    private val _fileTreeItems = MutableStateFlow<List<FileTreeItem>>(emptyList())
    val fileTreeItems: StateFlow<List<FileTreeItem>> = _fileTreeItems.asStateFlow()

    private val _expandedFolders = MutableStateFlow<Set<String>>(emptySet())
    val expandedFolders: StateFlow<Set<String>> = _expandedFolders.asStateFlow()

    // ── AI 面板 ──
    private val _aiPanelMode = MutableStateFlow(prefsManager.aiPanelMode)
    val aiPanelMode: StateFlow<Int> = _aiPanelMode.asStateFlow()

    private val _showFileTree = MutableStateFlow(false)
    val showFileTree: StateFlow<Boolean> = _showFileTree.asStateFlow()

    // ── 初始化 ──
    fun init(projectName: String, projectId: String, chapterId: String, filePath: String?) {
        this.projectName = projectName
        this.projectId = projectId

        if (projectId.isEmpty() && projectName.isNotEmpty()) {
            projectRepo.getAllProjects().find { it.name == projectName }?.let {
                this.projectId = it.id
            }
        }

        if (!filePath.isNullOrEmpty()) {
            val file = File(filePath)
            if (file.exists()) {
                _currentFile.value = file
                loadFileContent(file)
            }
        } else if (chapterId.isNotEmpty()) {
            val chapter = chapterRepo.getChapter(this.projectId, chapterId)
            if (chapter != null) {
                _currentChapter.value = chapter
                loadChapterContent(chapter)
            }
        }

        refreshFileTree()
    }

    private fun loadFileContent(file: File) {
        _title.value = file.nameWithoutExtension
        val text = file.readText()
        _content.value = text
        undoStack.clear(); redoStack.clear(); undoStack.addLast(text)
        updateUndoRedoState()
        updateWordCount()
    }

    private fun loadChapterContent(chapter: Chapter) {
        _title.value = chapter.title
        _content.value = chapter.content
        undoStack.clear(); redoStack.clear(); undoStack.addLast(chapter.content)
        updateUndoRedoState()
        updateWordCount()
    }

    // ── 内容编辑 ──
    fun onContentChanged(newContent: String) {
        if (isUndoRedo) return
        _isModified.value = true
        redoStack.clear()
        undoStack.addLast(newContent)
        while (undoStack.size > MAX_UNDO_SIZE) undoStack.removeFirst()
        updateUndoRedoState()
        updateWordCount()
    }

    fun undo() {
        if (undoStack.size < 2) return
        redoStack.addLast(undoStack.removeLast())
        val previous = undoStack.last()
        isUndoRedo = true
        _content.value = previous
        _isModified.value = true
        isUndoRedo = false
        updateUndoRedoState()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        isUndoRedo = true
        _content.value = next
        _isModified.value = true
        isUndoRedo = false
        updateUndoRedoState()
    }

    private fun updateUndoRedoState() {
        _canUndo.value = undoStack.size > 1
        _canRedo.value = redoStack.isNotEmpty()
    }

    // ── 文件操作 ──
    fun saveContent() {
        val text = _content.value
        val wc = text.replace(Regex("\\s+"), "").length

        val file = _currentFile.value
        val chapter = _currentChapter.value

        when {
            file != null -> {
                file.writeText(text)
                Log.d(TAG, "保存文件: ${file.name}")
            }
            chapter != null -> {
                val updated = chapter.copy(content = text, wordCount = wc)
                chapterRepo.saveChapter(updated)
                _currentChapter.value = updated
                Log.d(TAG, "保存章节: ${chapter.title}")
            }
        }
        _isModified.value = false
    }

    fun openFile(file: File) {
        if (file.isDirectory) {
            val expanded = _expandedFolders.value.toMutableSet()
            if (expanded.contains(file.absolutePath)) {
                expanded.remove(file.absolutePath)
            } else {
                expanded.add(file.absolutePath)
            }
            _expandedFolders.value = expanded
            refreshFileTree()
            return
        }

        if (file.name.endsWith(".txt", ignoreCase = true)) {
            if (_isModified.value) saveContent()
            _currentFile.value = file
            _currentChapter.value = null
            loadFileContent(file)
            _showFileTree.value = false
        }
    }

    fun toggleFileTree() {
        _showFileTree.value = !_showFileTree.value
    }

    fun refreshFileTree() {
        val dir = if (projectName.isNotEmpty()) {
            ProjectStorageManager.getProjectDir(projectName)
        } else return
        if (!dir.exists()) return

        val items = mutableListOf<FileTreeItem>()
        buildTree(dir, items, 0, _expandedFolders.value)
        _fileTreeItems.value = items
    }

    private fun buildTree(dir: File, items: MutableList<FileTreeItem>, level: Int, expanded: Set<String>) {
        val files = dir.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }
                .thenBy { ProjectStorageManager.extractChapterNumber(it.name) }
                .thenBy { it.name.lowercase() }
        ) ?: return
        for (f in files) {
            if (f.isDirectory) {
                val isExpanded = expanded.contains(f.absolutePath)
                items.add(FileTreeItem.Folder(f, level, isExpanded))
                if (isExpanded) buildTree(f, items, level + 1, expanded)
            } else {
                items.add(FileTreeItem.Document(f, level))
            }
        }
    }

    // ── 文件树操作 ──
    fun createFile(parentDir: File, name: String) {
        val file = File(parentDir, "$name.txt")
        if (file.exists()) return
        file.writeText("")
        refreshFileTree()
    }

    fun deleteFile(file: File): Boolean {
        val result = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (result) refreshFileTree()
        return result
    }

    fun renameFile(file: File, newName: String): Boolean {
        val newFile = File(file.parentFile, newName)
        if (newFile.exists()) return false
        val result = file.renameTo(newFile)
        if (result) refreshFileTree()
        return result
    }

    // ── 编辑器样式 ──
    fun setFontSize(size: Float) {
        _fontSize.value = size.coerceIn(10f, 32f)
        prefsManager.editorFontSize = _fontSize.value
    }

    fun setLineSpacing(spacing: Float) {
        _lineSpacing.value = spacing
        prefsManager.editorLineSpacing = spacing
    }

    // ── 字数统计 ──
    private fun updateWordCount() {
        val text = _content.value
        val noSpace = text.replace(Regex("\\s+"), "").length
        val total = text.length
        _wordCount.value = if (noSpace != total) "$noSpace 字 / $total 字符" else "$noSpace 字"
    }

    override fun onCleared() {
        super.onCleared()
        if (_isModified.value) saveContent()
    }
}

// ── 文件树数据模型 ──
sealed class FileTreeItem {
    abstract val file: File
    abstract val level: Int

    data class Folder(override val file: File, override val level: Int, val expanded: Boolean) : FileTreeItem()
    data class Document(override val file: File, override val level: Int) : FileTreeItem()
}
