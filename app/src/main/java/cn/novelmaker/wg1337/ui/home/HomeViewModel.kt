package cn.novelmaker.wg1337.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.novelmaker.wg1337.data.model.Project
import cn.novelmaker.wg1337.data.repository.ProjectRepository
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val projectRepository = ProjectRepository(AppContextHolder.context)
    private val storageManager = ProjectStorageManager

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            _isLoading.value = true
            val allProjects = projectRepository.getAllProjects()
            // 更新字数统计
            val updated = allProjects.map { project ->
                val (chapters, words) = storageManager.getProjectStats(project.name)
                if (project.chapterCount != chapters || project.wordCount != words) {
                    project.copy(chapterCount = chapters, wordCount = words).also {
                        projectRepository.saveProject(it)
                    }
                } else project
            }
            _projects.value = updated
            _isLoading.value = false
        }
    }

    fun createProject(name: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val existing = projectRepository.getAllProjects()
            if (existing.any { it.name.equals(name, ignoreCase = true) }) {
                onResult(false, "该项目名称已存在")
                return@launch
            }
            val success = storageManager.createProjectDirectories(name)
            if (success) {
                val project = Project(name = name)
                projectRepository.saveProject(project)
                loadProjects()
                onResult(true, "项目「$name」创建成功")
            } else {
                onResult(false, "创建项目目录失败，请检查存储权限")
            }
        }
    }

    fun renameProject(project: Project, newName: String) {
        viewModelScope.launch {
            projectRepository.saveProject(project.copy(name = newName))
            loadProjects()
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            ProjectStorageManager.deleteProjectDirectories(project.name)
            projectRepository.deleteProject(project.id)
            loadProjects()
        }
    }
}
