package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.Platform
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 定稿章节管理器
 * 用户手动标记已定稿章节，用作缓存前缀
 * 存储：ai_chats/finalized_{projectId}.json
 */
class FinalizedManager {

    /** 每次变更递增，外部可监听以刷新 UI */
    @Volatile var refreshVersion = 0
        private set

    private fun getFile(projectId: String): File {
        val dir = File(Platform.internalDataDir(), "ai_chats")
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
        val prefs = Platform.keyValueStore()
        val limit = prefs.getInt("max_finalized_chapters", 50)
        if (limit > 0 && files.size > limit) {
            val bulk = prefs.getInt("bulk_evict_chapters", 20).coerceAtLeast(1)
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
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        refreshVersion++
    }
}
