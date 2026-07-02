package cn.novelmaker.wg1337.ui.ai

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 定稿章节管理器
 * 用户手动标记已定稿章节，用作缓存前缀
 * 存储：ai_chats/finalized_{projectId}.json
 */
class FinalizedManager(private val context: Context) {

    /** 每次变更递增，外部可监听以刷新 UI */
    @Volatile var refreshVersion = 0
        private set

    private fun getFile(projectId: String): File {
        val dir = File(context.filesDir, "ai_chats")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "finalized_${projectId}.json")
    }

    fun getFinalizedFiles(projectId: String): Set<String> {
        val file = getFile(projectId)
        if (!file.exists()) return emptySet()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    fun markFinalized(projectId: String, relativePath: String) {
        val files = getFinalizedFiles(projectId).toMutableSet()
        files.add(relativePath)
        val prefs = context.getSharedPreferences("novelmaker_prefs", android.content.Context.MODE_PRIVATE)
        val limit = prefs.getInt("max_finalized_chapters", 0)
        if (limit > 0 && files.size > limit) {
            val bulk = prefs.getInt("bulk_evict_chapters", 1).coerceAtLeast(1)
            val sorted = files.sortedBy { cn.novelmaker.wg1337.utils.ProjectStorageManager.extractChapterNumber(it) }
            sorted.take(bulk.coerceAtMost(files.size - 1)).forEach { files.remove(it) }
        }
        save(projectId, files)
    }

    fun unmarkFinalized(projectId: String, relativePath: String) {
        val files = getFinalizedFiles(projectId).toMutableSet()
        files.remove(relativePath)
        save(projectId, files)
    }

    fun isFinalized(projectId: String, relativePath: String): Boolean =
        getFinalizedFiles(projectId).contains(relativePath)

    private fun save(projectId: String, files: Set<String>) {
        val arr = JSONArray()
        files.forEach { arr.put(it) }
        val file = getFile(projectId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString(2))
        tmp.renameTo(file)
        refreshVersion++
    }
}
