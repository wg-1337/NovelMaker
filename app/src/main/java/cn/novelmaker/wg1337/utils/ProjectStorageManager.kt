package cn.novelmaker.wg1337.utils

import android.os.Environment
import cn.novelmaker.wg1337.ui.ai.DefaultPrompt
import java.io.File

/**
 * 项目文件存储管理器
 * 负责在外部存储中管理项目目录结构
 *
 * 目录结构：
 * /存储根目录/NovelMaker/
 *     └── {项目名称}/
 *         ├── 大纲/          # 故事大纲相关文件
 *         ├── 提示词/        # AI提示词相关文件
 *         └── 小说主体/      # 小说章节正文文件
 */
object ProjectStorageManager {

    /** NovelMaker 根目录名称 */
    private const val ROOT_DIR_NAME = "NovelMaker"

    /** 子目录名称 */
    val SUB_DIRS = listOf("大纲", "提示词", "小说主体")

    /**
     * 获取 NovelMaker 根目录路径
     * /storage/emulated/0/NovelMaker/
     */
    fun getRootDir(): File {
        val externalStorage = Environment.getExternalStorageDirectory()
        return File(externalStorage, ROOT_DIR_NAME)
    }

    /**
     * 获取项目的根目录路径
     * /storage/emulated/0/NovelMaker/{projectName}/
     */
    fun getProjectDir(projectName: String): File {
        return File(getRootDir(), sanitizeFileName(projectName))
    }

    /**
     * 获取项目下的子目录路径
     * /storage/emulated/0/NovelMaker/{projectName}/{subDir}/
     */
    fun getProjectSubDir(projectName: String, subDir: String): File {
        return File(getProjectDir(projectName), subDir)
    }

    /**
     * 创建项目目录结构
     * @return 创建成功返回 true
     */
    fun createProjectDirectories(projectName: String): Boolean {
        return try {
            val projectDir = getProjectDir(projectName)
            // 创建项目根目录
            if (projectDir.exists()) {
                // 目录已存在，删除重建
                projectDir.deleteRecursively()
            }
            projectDir.mkdirs()

            // 创建三个子目录
            SUB_DIRS.forEach { subDir ->
                val dir = File(projectDir, subDir)
                dir.mkdirs()
            }
            // 生成默认AI提示词文件
            val promptFile = File(File(projectDir, "提示词"), "提示词.txt")
            if (!promptFile.exists()) {
                promptFile.writeText(DefaultPrompt.getDefaultPrompt())
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 删除项目目录结构
     */
    fun deleteProjectDirectories(projectName: String): Boolean {
        return try {
            val projectDir = getProjectDir(projectName)
            if (projectDir.exists()) {
                projectDir.deleteRecursively()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 重命名项目目录
     */
    fun renameProjectDirectory(oldName: String, newName: String): Boolean {
        return try {
            val oldDir = getProjectDir(oldName)
            val newDir = getProjectDir(newName)
            if (oldDir.exists()) {
                oldDir.renameTo(newDir)
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isRootDirExists(): Boolean = getRootDir().exists()

    /** 获取项目的章节数 = 小说主体/ 下的 .txt 文件数（不含子目录） */
    fun getChapterCount(projectName: String): Int {
        val dir = getProjectSubDir(projectName, "小说主体")
        if (!dir.exists()) return 0
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", ignoreCase = true) }?.size ?: 0
    }

    /** 获取项目的总字数 = 小说主体/ 下所有 .txt 字符数 */
    fun getWordCount(projectName: String): Int {
        val dir = getProjectSubDir(projectName, "小说主体")
        if (!dir.exists()) return 0
        return countChars(dir)
    }

    /** 获取两个统计并返回 Pair(章节数, 总字数) */
    fun getProjectStats(projectName: String): Pair<Int, Int> {
        val dir = getProjectSubDir(projectName, "小说主体")
        if (!dir.exists()) return Pair(0, 0)
        val txtCount = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", ignoreCase = true) }?.size ?: 0
        val chars = countChars(dir)
        return Pair(txtCount, chars)
    }

    private fun countChars(dir: File): Int {
        var total = 0
        (dir.listFiles() ?: return 0).forEach { f ->
            if (f.isDirectory) total += countChars(f)
            else if (f.name.endsWith(".txt", true)) try { total += f.readText().length } catch (_: Exception) {}
        }
        return total
    }

    /** 从文件名提取章节号（"第1章_xxx.txt" → 1，非章节格式返回 Int.MAX_VALUE 排到末尾） */
    fun extractChapterNumber(fileName: String): Int {
        val match = Regex("^第(\\d+)章").find(fileName)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
