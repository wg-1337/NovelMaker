package cn.novelmaker.wg1337.utils

import android.content.Context
import cn.novelmaker.wg1337.data.model.Project
import org.json.JSONObject
import java.io.*
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 项目备份管理器
 * 将项目全部数据（文件 + AI 数据）打包加密为 .nmbak 文件
 */
object BackupManager {

    private const val SALT_SIZE = 8
    private const val IV_SIZE = 16
    private const val KEY_SIZE = 256
    private const val PBKDF2_ITERATIONS = 100_000
    private const val AES_ALGORITHM = "AES/CBC/PKCS5Padding"

    // ═══════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════

    /** 明文元数据，无需密码即可读取 */
    data class BackupMeta(
        val signature: String,
        val projectNames: List<String>,
        val timestamp: Long,
        val file: File
    )

    /** 列出 backups 目录下所有备份文件及其元数据 */
    fun listBackups(): List<BackupMeta> {
        val dir = File(ProjectStorageManager.getRootDir(), "backups")
        if (!dir.exists()) return emptyList()
        val result = mutableListOf<BackupMeta>()
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name.lowercase()
            if (name.endsWith(".nmbak") || name.endsWith(".zip")) {
                val meta = readMeta(f)
                if (meta != null) {
                    result.add(meta.copy(file = f))
                } else {
                    result.add(BackupMeta(
                        signature = f.nameWithoutExtension.take(40),
                        projectNames = listOf("未知"),
                        timestamp = f.lastModified(),
                        file = f
                    ))
                }
            }
        }
        return result.sortedByDescending { it.timestamp }
    }

    /** 读取备份文件元数据（无需密码） */
    fun readMeta(file: File): BackupMeta? {
        return try {
            DataInputStream(file.inputStream().buffered()).use { dis ->
                val magic = ByteArray(4); dis.readFully(magic)
                if (String(magic, Charsets.UTF_8) != "NMBK") return null
                val metaLen = dis.readInt()
                val metaBytes = ByteArray(metaLen); dis.readFully(metaBytes)
                val meta = JSONObject(String(metaBytes, Charsets.UTF_8))
                val pArr = meta.optJSONArray("projects")
                val pNames = if (pArr != null) (0 until pArr.length()).map { pArr.getString(it) } else emptyList()
                BackupMeta(
                    signature = meta.optString("signature", "未命名备份"),
                    projectNames = pNames,
                    timestamp = meta.optLong("timestamp", file.lastModified()),
                    file = file
                )
            }
        } catch (_: Exception) { null }
    }

    /** 备份单个项目 */
    fun backup(context: Context, project: Project, password: String, signature: String, outputFile: File): Boolean {
        return try {
            val tmpDir = File(context.cacheDir, "backup_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            val subDir = File(tmpDir, project.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            subDir.mkdirs()
            writeProjectMeta(subDir, project)
            copyProjectFiles(subDir, project.name)
            copyAiData(context, subDir, project.id)
            val zipFile = File(tmpDir.parentFile, "${tmpDir.name}.zip")
            zipDir(tmpDir, zipFile)
            writeEncrypted(zipFile, outputFile, password, signature, listOf(project.name))
            tmpDir.deleteRecursively(); zipFile.delete()
            true
        } catch (e: Exception) {
            e.printStackTrace(); false
        }
    }

    /** 备份多个项目到单个文件 */
    fun backupProjects(context: Context, projects: List<Project>, password: String, signature: String, outputFile: File): Boolean {
        if (projects.isEmpty()) return false
        if (projects.size == 1) return backup(context, projects.first(), password, signature, outputFile)
        val tmpDir = File(context.cacheDir, "multiback_${System.currentTimeMillis()}"); tmpDir.mkdirs()
        return try {
            projects.forEach { p ->
                val subDir = File(tmpDir, p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                subDir.mkdirs()
                writeProjectMeta(subDir, p)
                copyProjectFiles(subDir, p.name)
                copyAiData(context, subDir, p.id)
            }
            val zipFile = File(tmpDir.parentFile, "${tmpDir.name}.zip")
            zipDir(tmpDir, zipFile)
            writeEncrypted(zipFile, outputFile, password, signature, projects.map { it.name })
            zipFile.delete()
            true
        } catch (e: Exception) { e.printStackTrace(); false }
        finally { tmpDir.deleteRecursively() }
    }

    /** 恢复项目 */
    @Throws(SecurityException::class, Exception::class)
    fun restore(context: Context, backupFile: File, password: String): List<String> {
        val tmpDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        try {
            val zipFile = File(tmpDir.parentFile, "${tmpDir.name}.zip")
            readAndDecrypt(backupFile, zipFile, password)
            unzipDir(zipFile, tmpDir)
            zipFile.delete()
            // 处理多项目：tmpDir 下每个子目录是一个项目的备份
            val restored = mutableListOf<String>()
            tmpDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                restoreProjectDir(context, subDir, restored)
            }
            // 兼容旧格式：project.json 直接在 ZIP 根目录
            val rootMeta = File(tmpDir, "project.json")
            if (rootMeta.exists()) {
                restoreProjectDir(context, tmpDir, restored)
            }
            tmpDir.deleteRecursively()
            return restored
        } catch (e: SecurityException) { tmpDir.deleteRecursively(); throw e }
        catch (e: Exception) { tmpDir.deleteRecursively(); throw e }
    }

    private fun restoreProjectDir(context: Context, dir: File, restored: MutableList<String>) {
        val metaFile = File(dir, "project.json")
        if (!metaFile.exists()) return
        try {
            val meta = JSONObject(metaFile.readText())
            val projectName = meta.getString("name")
            val projectId = meta.getString("id")
            val finalName = resolveConflict(projectName)
            val filesDir = File(dir, "files")
            if (filesDir.exists()) {
                ProjectStorageManager.getProjectDir(finalName).also { it.mkdirs() }
                filesDir.copyRecursively(ProjectStorageManager.getProjectDir(finalName), overwrite = true)
            }
            val aiDir = File(dir, "ai_data")
            if (aiDir.exists()) {
                val targetAiDir = File(context.filesDir, "ai_chats"); targetAiDir.mkdirs()
                aiDir.listFiles()?.forEach { f -> f.copyTo(File(targetAiDir, f.name), overwrite = true) }
            }
            val repo = cn.novelmaker.wg1337.data.repository.ProjectRepository(context)
            if (repo.getAllProjects().none { it.id == projectId }) {
                repo.saveProject(Project(name = finalName, id = projectId))
            }
            restored.add(finalName)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════
    // 内部：新文件格式读写
    // ═══════════════════════════════════════════

    /** 写入：明文头 + 加密体 */
    private fun writeEncrypted(zipInput: File, output: File, password: String, signature: String, projectNames: List<String>) {
        val meta = JSONObject().apply {
            put("signature", signature)
            put("projects", org.json.JSONArray(projectNames))
            put("timestamp", System.currentTimeMillis())
            put("version", 1)
        }
        val metaBytes = meta.toString().toByteArray(Charsets.UTF_8)
        DataOutputStream(FileOutputStream(output).buffered()).use { dos ->
            dos.write("NMBK".toByteArray())
            dos.writeInt(metaBytes.size)
            dos.write(metaBytes)
        }
        // 追加加密数据
        val salt = ByteArray(SALT_SIZE); SecureRandom().nextBytes(salt)
        val key = deriveKey(password, salt)
        val iv = ByteArray(IV_SIZE); SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        FileOutputStream(output, true).use { fos ->
            fos.write(salt); fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos -> zipInput.inputStream().use { it.copyTo(cos) } }
        }
    }

    /** 读取：跳过明文头，解密体 */
    private fun readAndDecrypt(input: File, outputZip: File, password: String) {
        DataInputStream(input.inputStream().buffered()).use { dis ->
            val magic = ByteArray(4); dis.readFully(magic)
            if (String(magic, Charsets.UTF_8) != "NMBK") throw Exception("无法识别备份文件格式")
            val metaLen = dis.readInt()
            dis.skipBytes(metaLen)
            val salt = ByteArray(SALT_SIZE); dis.readFully(salt)
            val iv = ByteArray(IV_SIZE); dis.readFully(iv)
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            try {
                FileOutputStream(outputZip).use { fos ->
                    CipherInputStream(dis, cipher).use { cis -> cis.copyTo(fos) }
                }
            } catch (e: Exception) {
                outputZip.delete()
                throw SecurityException("密码错误")
            }
        }
    }

    // ── 内部方法 ──

    private fun writeProjectMeta(tmpDir: File, project: Project) {
        val json = JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("createdAt", project.createdAt)
            put("updatedAt", project.updatedAt)
            put("wordCount", project.wordCount)
            put("chapterCount", project.chapterCount)
        }
        File(tmpDir, "project.json").writeText(json.toString(2))
    }

    private fun copyProjectFiles(tmpDir: File, projectName: String) {
        val srcDir = ProjectStorageManager.getProjectDir(projectName)
        if (!srcDir.exists()) return
        val dstDir = File(tmpDir, "files")
        dstDir.mkdirs()
        srcDir.copyRecursively(dstDir, overwrite = true)
    }

    private fun copyAiData(context: Context, tmpDir: File, projectId: String) {
        val aiDir = File(context.filesDir, "ai_chats")
        if (!aiDir.exists()) return
        val dstDir = File(tmpDir, "ai_data")
        dstDir.mkdirs()
        aiDir.listFiles()?.forEach { f ->
            if (f.name.contains(projectId)) {
                f.copyTo(File(dstDir, f.name), overwrite = true)
            }
        }
    }

    private fun resolveConflict(projectName: String): String {
        val rootDir = ProjectStorageManager.getRootDir()
        if (!File(rootDir, projectName).exists()) return projectName
        var i = 1
        var name: String
        do {
            name = "${projectName}_恢复$i"
            i++
        } while (File(rootDir, name).exists())
        return name
    }

    private fun zipDir(srcDir: File, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            srcDir.walkTopDown().forEach { file ->
                if (file == srcDir) return@forEach
                val entryName = file.relativeTo(srcDir).path.replace(File.separatorChar, '/')
                if (file.isDirectory) {
                    zos.putNextEntry(ZipEntry("$entryName/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun unzipDir(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val destFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
