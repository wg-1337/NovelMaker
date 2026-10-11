package cn.novelmaker.wg1337.data.repository

import cn.novelmaker.wg1337.Platform
import cn.novelmaker.wg1337.data.model.Project
import cn.novelmaker.wg1337.ui.ai.AiChatViewModel
import cn.novelmaker.wg1337.ui.ai.ChatImageStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 项目数据仓库 - 负责项目的CRUD操作
 * 使用 JSON 文件存储项目列表，每个项目在独立目录中存储
 */
class ProjectRepository {

    private val projectsFile: File
        get() = File(Platform.internalDataDir(), "projects.json")

    /**
     * 获取所有项目列表（按更新时间倒序）
     */
    fun getAllProjects(): List<Project> {
        return loadProjects().sortedByDescending { it.updatedAt }
    }

    /**
     * 根据 id 获取项目
     */
    fun getProjectById(id: String): Project? {
        return loadProjects().find { it.id == id }
    }

    /**
     * 保存项目（新增或更新）
     */
    fun saveProject(project: Project) {
        val projects = loadProjects().toMutableList()
        val index = projects.indexOfFirst { it.id == project.id }
        if (index >= 0) {
            projects[index] = project.copy(updatedAt = System.currentTimeMillis())
        } else {
            projects.add(project)
        }
        saveProjects(projects)
    }

    /**
     * 删除项目
     */
    fun deleteProject(projectId: String) {
        val projects = loadProjects().toMutableList()
        projects.removeAll { it.id == projectId }
        saveProjects(projects)

        // 删除项目本地目录
        val projectDir = File(Platform.internalDataDir(), "project_$projectId")
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }

        // 清理 AI 相关数据（聊天记录、系统提示词、定稿列表、Token 统计、注入设置）
        val aiDir = File(Platform.internalDataDir(), "ai_chats")
        if (aiDir.exists()) {
            aiDir.listFiles()?.forEach { file ->
                if (file.name.contains(projectId)) file.delete()
            }
        }

        // 清理对话图片目录
        ChatImageStore.deleteProjectImages(projectId)

        // 清理偏好项里的项目级数据（无法按项目一次性枚举，这里清理已知的键）
        val prefs = Platform.keyValueStore()
        prefs.remove("ai_mode_$projectId")
        prefs.remove("ai_active_tab_$projectId")
        // 每个对话一个步数计数器，最多 5 个标签页
        for (tabId in 1..AiChatViewModel.MAX_TABS) {
            prefs.remove("injection_steps_${projectId}_$tabId")
        }
    }

    /**
     * 从文件加载项目列表
     */
    private fun loadProjects(): List<Project> {
        if (!projectsFile.exists()) return emptyList()
        return try {
            val json = projectsFile.readText()
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Project(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    coverPath = obj.optString("coverPath", ""),
                    createdAt = obj.getLong("createdAt"),
                    updatedAt = obj.getLong("updatedAt"),
                    wordCount = obj.optInt("wordCount", 0),
                    chapterCount = obj.optInt("chapterCount", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存项目列表到文件
     */
    private fun saveProjects(projects: List<Project>) {
        val array = JSONArray()
        projects.forEach { project ->
            val obj = JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
                put("coverPath", project.coverPath ?: JSONObject.NULL)
                put("createdAt", project.createdAt)
                put("updatedAt", project.updatedAt)
                put("wordCount", project.wordCount)
                put("chapterCount", project.chapterCount)
            }
            array.put(obj)
        }
        projectsFile.writeText(array.toString(2))
    }
}
