package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform
import cn.novelmaker.wg1337.utils.ProjectStorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 系统提示词**定义**（只负责存取，不负责拼装）。
 *
 * 收纳两样东西：
 * 1. **基础提示词**：项目的系统提示词正文。留空 = 使用内置模板（[DefaultPrompt]）。
 * 2. **引用文件**：被引入系统提示词的文本文件（相对项目目录的路径）。
 *    存的是**路径引用而非文本快照** —— 文件改了提示词自动跟着更新，
 *    也不会像旧实现那样每次切换模式都把同一份大纲重复灌一遍。
 *
 * 拼装成最终提示词请用 [SystemPromptComposer]，两者刻意分开：
 * 定义可编辑、可持久化；拼装是纯函数、可预览、带缓存边界计算。
 *
 * 存储：ai_chats/system_prompt_{projectId}.json
 *
 * JSON 结构：
 * ```json
 * {
 *   "basePrompt": "自定义正文，缺省则用内置模板",
 *   "contextFiles": ["大纲/故事大纲.txt", "大纲/角色设定.txt"]
 * }
 * ```
 */
class SystemPromptManager {

    companion object {
        private const val TAG = "SystemPromptManager"

        /** 单个引用文件的读取上限（字符），避免超大文件把提示词撑爆 */
        const val MAX_CONTEXT_FILE_CHARS = 20_000

        /** 引用文件数量上限 */
        const val MAX_CONTEXT_FILES = 20

        private const val KEY_BASE_PROMPT = "basePrompt"
        private const val KEY_CONTEXT_FILES = "contextFiles"
    }

    private fun getFile(projectId: String): File {
        val dir = File(Platform.internalDataDir(), "ai_chats")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "system_prompt_${projectId}.json")
    }

    // ───────────────────── 基础提示词 ─────────────────────

    /**
     * 生效的基础提示词。
     * 优先级：项目自定义 → 内置模板。
     */
    fun getBasePrompt(projectId: String): String {
        val custom = loadData(projectId).optString(KEY_BASE_PROMPT, "")
        return custom.ifBlank { DefaultPrompt.getDefaultPrompt() }
    }

    /** 是否正在使用内置模板（界面上据此提示「当前为内置默认」） */
    fun isUsingDefaultBasePrompt(projectId: String): Boolean =
        loadData(projectId).optString(KEY_BASE_PROMPT, "").isBlank()

    /** 用户自定义的基础提示词原文；未自定义时返回空串 */
    fun getCustomBasePromptOrEmpty(projectId: String): String =
        loadData(projectId).optString(KEY_BASE_PROMPT, "")

    /**
     * 更新基础提示词。
     * 传入空白表示**清除项目自定义**，回落到内置模板（不是清成空提示词）。
     */
    fun setBasePrompt(projectId: String, text: String) {
        if (projectId.isEmpty()) return
        val data = loadData(projectId)
        if (text.isBlank()) data.remove(KEY_BASE_PROMPT) else data.put(KEY_BASE_PROMPT, text)
        saveData(projectId, data)
    }

    // ───────────────────── 引用文件 ─────────────────────

    /** 当前引用的文件（相对项目目录的路径，已排序） */
    fun getContextFiles(projectId: String): List<String> {
        val arr = loadData(projectId).optJSONArray(KEY_CONTEXT_FILES) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    /** 追加一个引用文件；重复或超限时返回 false */
    fun addContextFile(projectId: String, relativePath: String): Boolean {
        if (projectId.isEmpty() || relativePath.isBlank()) return false
        val files = getContextFiles(projectId).toMutableList()
        if (files.any { it.equals(relativePath, ignoreCase = true) }) return false
        if (files.size >= MAX_CONTEXT_FILES) return false
        files.add(relativePath)
        writeContextFiles(projectId, files)
        return true
    }

    fun removeContextFile(projectId: String, relativePath: String) {
        val files = getContextFiles(projectId).filterNot { it == relativePath }
        writeContextFiles(projectId, files)
    }

    fun setContextFiles(projectId: String, files: List<String>) {
        writeContextFiles(projectId, files.distinct().take(MAX_CONTEXT_FILES))
    }

    fun clearContextFiles(projectId: String) = writeContextFiles(projectId, emptyList())

    private fun writeContextFiles(projectId: String, files: List<String>) {
        if (projectId.isEmpty()) return
        val data = loadData(projectId)
        data.put(KEY_CONTEXT_FILES, JSONArray(files))
        saveData(projectId, data)
    }

    // ───────────────────── 存储 ─────────────────────

    private fun loadData(projectId: String): JSONObject {
        if (projectId.isEmpty()) return JSONObject()
        val file = getFile(projectId)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            AppLogger.w(TAG, "系统提示词配置解析失败，按空配置处理: ${file.name} - ${e.message}")
            JSONObject()
        }
    }

    private fun saveData(projectId: String, data: JSONObject) {
        if (projectId.isEmpty()) return
        try {
            val file = getFile(projectId)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(data.toString(2))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存系统提示词配置失败: projectId=$projectId", e)
        }
    }

    /** 随项目删除一并清理（ProjectRepository 已按文件名包含 projectId 清理，此处保留语义入口） */
    fun deleteProjectConfig(projectId: String) {
        try {
            val f = getFile(projectId)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            AppLogger.w(TAG, "删除系统提示词配置失败: ${e.message}")
        }
    }
}

/**
 * 列出项目下可引用的文本文件（相对项目目录的路径，已排序）。
 *
 * 供「引用文件」选择界面共用 —— 设置页与编辑器注入对话框都走这里，
 * 避免两处各写一份目录遍历逻辑而行为不一致。
 */
fun listProjectTextFiles(projectName: String): List<String> {
    if (projectName.isBlank()) return emptyList()
    val root = ProjectStorageManager.getProjectDir(projectName)
    if (!root.exists()) return emptyList()
    val result = mutableListOf<String>()
    root.walkTopDown()
        .maxDepth(3)
        .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
        .forEach { file ->
            val rel = try {
                file.relativeTo(root).path.replace('\\', '/')
            } catch (_: Exception) {
                return@forEach
            }
            result.add(rel)
        }
    return result.sorted()
}
