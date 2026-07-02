package cn.novelmaker.wg1337.ui.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 系统提示词管理器
 * 管理每个项目的系统提示词：基础提示词 + Plan阶段追加 + 写作限定
 * 存储：ai_chats/system_prompt_{projectId}.json
 */
class SystemPromptManager(private val context: Context) {

    data class WritingConstraints(
        val articleType: String = "",
        val style: String = "",
        val language: String = "",
        val restrictions: String = ""
    )

    private fun getFile(projectId: String): File {
        val dir = File(context.filesDir, "ai_chats")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "system_prompt_${projectId}.json")
    }

    fun getFullPrompt(projectId: String): String {
        val data = loadData(projectId)
        val base = data.optString("basePrompt", DefaultPrompt.getDefaultPrompt())
        val additions = data.optJSONArray("additions") ?: JSONArray()
        val constraints = data.optJSONObject("writingConstraints") ?: JSONObject()

        val sb = StringBuilder(base)
        for (i in 0 until additions.length()) {
            sb.append("\n\n").append(additions.getString(i))
        }
        val constraintText = buildConstraintsText(constraints)
        if (constraintText.isNotEmpty()) {
            sb.append("\n\n【写作限定】\n").append(constraintText)
        }
        return sb.toString()
    }

    fun addToPrompt(projectId: String, text: String) {
        val data = loadData(projectId)
        if (data.optString("basePrompt", "").isEmpty()) {
            data.put("basePrompt", DefaultPrompt.getDefaultPrompt())
        }
        val additions = data.optJSONArray("additions") ?: JSONArray()
        additions.put(text)
        data.put("additions", additions)
        saveData(projectId, data)
    }

    fun setConstraints(projectId: String, constraints: WritingConstraints) {
        val data = loadData(projectId)
        if (data.optString("basePrompt", "").isEmpty()) {
            data.put("basePrompt", DefaultPrompt.getDefaultPrompt())
        }
        data.put("writingConstraints", JSONObject().apply {
            put("articleType", constraints.articleType)
            put("style", constraints.style)
            put("language", constraints.language)
            put("restrictions", constraints.restrictions)
        })
        saveData(projectId, data)
    }

    fun getForDisplay(projectId: String): String = getFullPrompt(projectId)

    /**
     * 导出系统提示词到项目目录下的 提示词/系统提示词.txt
     */
    fun exportToProjectFile(projectName: String, projectId: String) {
        try {
            val prompt = getFullPrompt(projectId)
            val dir = cn.novelmaker.wg1337.utils.ProjectStorageManager.getProjectSubDir(projectName, "提示词")
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                File(dir, "系统提示词.txt").writeText(prompt)
            }
        } catch (_: Exception) {}
    }

    private fun loadData(projectId: String): JSONObject {
        val file = getFile(projectId)
        return if (file.exists()) {
            try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
        } else JSONObject()
    }

    private fun saveData(projectId: String, data: JSONObject) {
        val file = getFile(projectId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(data.toString(2))
        tmp.renameTo(file)
    }

    private fun buildConstraintsText(c: JSONObject): String {
        val sb = StringBuilder()
        c.optString("articleType", "").takeIf { it.isNotEmpty() }?.let { sb.appendLine("文章类型：$it") }
        c.optString("style", "").takeIf { it.isNotEmpty() }?.let { sb.appendLine("文体风格：$it") }
        c.optString("language", "").takeIf { it.isNotEmpty() }?.let { sb.appendLine("语言：$it") }
        c.optString("restrictions", "").takeIf { it.isNotEmpty() }?.let { sb.appendLine("写作限制：$it") }
        return sb.toString().trim()
    }
}
