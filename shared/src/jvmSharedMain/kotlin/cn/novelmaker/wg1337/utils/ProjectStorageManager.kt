package cn.novelmaker.wg1337.utils

import cn.novelmaker.wg1337.Platform
import java.io.File

/**
 * 项目文件存储管理器
 * 负责在外部存储中管理项目目录结构
 *
 * 目录结构：
 * /存储根目录/NovelMaker/
 *     └── {项目名称}/
 *         ├── 大纲/          # 故事大纲相关文件
 *         └── 小说主体/      # 小说章节正文文件
 *
 * 注：v1.7.0 起不再创建「提示词/」目录 —— 系统提示词改为在设置中直接管理
 * （见 SystemPromptManager / ConversationSettingsManager），不必落盘为项目文件。
 * 老项目里已存在的「提示词/」目录不会被删除，仍可正常浏览与编辑。
 */
object ProjectStorageManager {

    /** NovelMaker 根目录名称 */
    private const val ROOT_DIR_NAME = "NovelMaker"

    /** 新建项目时创建的子目录 */
    val SUB_DIRS = listOf("大纲", "小说主体")

    /** 历史遗留子目录：不再创建，但老项目存在时仍需展示/可访问 */
    const val LEGACY_PROMPT_DIR = "提示词"

    /**
     * 获取 NovelMaker 根目录路径
     * Android: /storage/emulated/0/NovelMaker/
     * Windows: %USERPROFILE%\Documents\NovelMaker\
     */
    fun getRootDir(): File = Platform.projectsRootDir()

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

            // 创建子目录
            SUB_DIRS.forEach { subDir ->
                val dir = File(projectDir, subDir)
                dir.mkdirs()
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
            if (!oldDir.exists()) return false
            // 目标目录已存在时拒绝覆盖/合并，避免两个项目共享同一目录。
            // 仅当新旧名称清洗后指向同一目录（例如仅非法字符不同）时视为成功。
            if (newDir.exists()) return newDir.absolutePath == oldDir.absolutePath
            oldDir.renameTo(newDir)
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
