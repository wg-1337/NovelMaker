package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * 对话图片存储。
 *
 * 图片保存在 `{internalDataDir}/ai_images/{projectId}/` 下，
 * 文件名 = 内容哈希（前 16 位 hex）+ 扩展名，因此同一张图重复发送不会产生副本。
 *
 * 发送给 API 时以 base64 data URL 内联（Vision 指南中「本地文件最简单的方式」）。
 * 本类无状态：所有方法都需要 projectId，图片不随消息一起序列化，只存文件名。
 */
object ChatImageStore {

    private const val TAG = "ChatImageStore"

    /** 单张图片内联上限（超出应改用 Files API；本应用直接拒绝并提示） */
    private const val MAX_INLINE_BYTES = DeepSeekApi.MAX_IMAGE_BYTES_INLINE

    fun getProjectImageDir(projectId: String): File {
        val dir = File(Platform.internalDataDir(), "ai_images/${sanitize(projectId)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun resolve(projectId: String, image: ChatImage): File =
        File(getProjectImageDir(projectId), image.fileName)

    /**
     * 保存一张图片到项目图片目录。
     *
     * @param sourceName 原文件名，仅用于推断 MIME 与生成可读后缀
     * @return 保存后的 [ChatImage]；格式不支持或过大时返回 null
     */
    fun save(projectId: String, bytes: ByteArray, sourceName: String): ChatImage? {
        if (bytes.isEmpty()) return null
        if (bytes.size.toLong() > MAX_INLINE_BYTES) {
            AppLogger.w(TAG, "图片过大，拒绝保存：${bytes.size} bytes")
            return null
        }
        // 格式以文件实际内容为准（Vision 指南：由内容判断，而非文件名）
        val mime = detectMime(bytes) ?: DeepSeekApi.mimeTypeForFileName(sourceName)
        if (mime == null || mime !in DeepSeekApi.SUPPORTED_IMAGE_MIME) {
            AppLogger.w(TAG, "不支持的图片格式：$sourceName")
            return null
        }
        return try {
            val dir = getProjectImageDir(projectId)
            val fileName = "${sha256Hex(bytes).take(16)}.${extensionFor(mime)}"
            val target = File(dir, fileName)
            if (!target.exists()) target.writeBytes(bytes)
            ChatImage(
                fileName = fileName,
                mimeType = mime,
                sizeBytes = bytes.size.toLong()
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存图片失败", e)
            null
        }
    }

    /** 读取图片字节；文件不存在返回 null */
    fun readBytes(projectId: String, image: ChatImage): ByteArray? = try {
        val f = resolve(projectId, image)
        if (f.exists() && f.length() > 0) f.readBytes() else null
    } catch (e: Exception) {
        AppLogger.w(TAG, "读取图片失败: ${image.fileName} - ${e.message}")
        null
    }

    /** 转为 base64 data URL；读取失败返回 null（调用方应跳过该图，避免发出空 URL） */
    fun toDataUrl(projectId: String, image: ChatImage): String? {
        val bytes = readBytes(projectId, image) ?: return null
        return "data:${image.mimeType};base64," + Base64.getEncoder().encodeToString(bytes)
    }

    /** 删除项目的全部图片（删除项目时调用） */
    fun deleteProjectImages(projectId: String) {
        try {
            val dir = File(Platform.internalDataDir(), "ai_images/${sanitize(projectId)}")
            if (dir.exists()) dir.deleteRecursively()
        } catch (e: Exception) {
            AppLogger.w(TAG, "删除项目图片失败: ${e.message}")
        }
    }

    // ───────────────────── 内部工具 ─────────────────────

    /** 由文件头判断真实格式 */
    private fun detectMime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "image/jpeg"
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "image/png"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> "image/gif"
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
                b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> "image/webp"
            else -> null
        }
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "bin"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "unknown" }
}
