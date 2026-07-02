package cn.novelmaker.wg1337.data.repository

import android.content.Context
import cn.novelmaker.wg1337.data.model.Chapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 章节数据仓库
 * 每部小说的章节存储在对应的项目目录下的 小说主体/ 文件夹中
 */
class ChapterRepository(private val context: Context) {

    /**
     * 获取项目的所有章节（按排序顺序）
     */
    fun getChapters(projectId: String): List<Chapter> {
        return loadChapters(projectId).sortedBy { it.sortOrder }
    }

    /**
     * 获取单个章节
     */
    fun getChapter(projectId: String, chapterId: String): Chapter? {
        return loadChapters(projectId).find { it.id == chapterId }
    }

    /**
     * 保存章节（新增或更新）
     */
    fun saveChapter(chapter: Chapter) {
        val chapters = loadChapters(chapter.projectId).toMutableList()
        val index = chapters.indexOfFirst { it.id == chapter.id }
        if (index >= 0) {
            chapters[index] = chapter.copy(updatedAt = System.currentTimeMillis())
        } else {
            chapters.add(chapter)
        }
        saveChapters(chapter.projectId, chapters)
    }

    /**
     * 删除章节
     */
    fun deleteChapter(projectId: String, chapterId: String) {
        val chapters = loadChapters(projectId).toMutableList()
        chapters.removeAll { it.id == chapterId }
        saveChapters(projectId, chapters)
    }

    /**
     * 获取章节数
     */
    fun getChapterCount(projectId: String): Int {
        return loadChapters(projectId).size
    }

    /**
     * 获取项目总字数
     */
    fun getTotalWordCount(projectId: String): Int {
        return loadChapters(projectId).sumOf { it.wordCount }
    }

    /**
     * 创建新章节（自动设置序号）
     */
    fun createChapter(projectId: String, title: String): Chapter {
        val chapters = loadChapters(projectId)
        val maxOrder = chapters.maxOfOrNull { it.sortOrder } ?: -1
        val chapter = Chapter(
            title = title,
            projectId = projectId,
            sortOrder = maxOrder + 1
        )
        saveChapter(chapter)
        return chapter
    }

    private fun getChaptersFile(projectId: String): File {
        return File(context.filesDir, "chapters_${projectId}.json")
    }

    private fun loadChapters(projectId: String): List<Chapter> {
        val file = getChaptersFile(projectId)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Chapter(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    projectId = obj.getString("projectId"),
                    content = obj.optString("content", ""),
                    wordCount = obj.optInt("wordCount", 0),
                    createdAt = obj.getLong("createdAt"),
                    updatedAt = obj.getLong("updatedAt"),
                    sortOrder = obj.getInt("sortOrder")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveChapters(projectId: String, chapters: List<Chapter>) {
        val array = JSONArray()
        chapters.forEach { chapter ->
            val obj = JSONObject().apply {
                put("id", chapter.id)
                put("title", chapter.title)
                put("projectId", chapter.projectId)
                put("content", chapter.content)
                put("wordCount", chapter.wordCount)
                put("createdAt", chapter.createdAt)
                put("updatedAt", chapter.updatedAt)
                put("sortOrder", chapter.sortOrder)
            }
            array.put(obj)
        }
        getChaptersFile(projectId).writeText(array.toString(2))
    }
}
