package cn.novelmaker.wg1337.ui.ai

/**
 * DeepSeek API 集中配置（对齐官方文档）
 *
 * - Chat Completions：https://api-docs.deepseek.com/zh-cn/api/create-chat-completion
 * - 思考模式：https://api-docs.deepseek.com/zh-cn/guides/thinking_mode
 * - 图像理解：https://api-docs.deepseek.com/zh-cn/guides/vision
 *
 * 参数约定（与旧版 deepseek-chat 的差异，改动此处务必先核对文档）：
 * 1. 模型名仅 deepseek-flash / deepseek-v4-pro 为当前有效值；
 *    旧名 deepseek-v4-flash、deepseek-v4-flash-vision-exp 仍可调用但模型已下线。
 * 2. 思考模式开关为 {"thinking":{"type":"enabled|disabled"}}；强度为 reasoning_effort。
 *    合法强度：none / low / high / max（minimal→low，medium/xhigh→high 由服务端映射）。
 *    none 等价于关闭思考。
 * 3. 思考模式下不支持 temperature / presence_penalty / frequency_penalty；
 *    前两者已废弃，temperature 传入也不生效。top_p 仅思考模式生效（0.95–1.0）。
 * 4. 携带 tools 的请求，历史轮次的 reasoning_content 必须完整回传，否则返回 400。
 *    未携带 tools 时 reasoning_content 会被忽略，无需回传。
 * 5. 思考模式下不支持 tool_choice=required 或指定具体工具（返回 400）。
 * 6. 图像理解仅 deepseek-flash 支持，且图片只允许出现在 user 消息中
 *    （system / assistant 消息携带图片返回 400）。
 */
object DeepSeekApi {

    const val DEFAULT_BASE_URL = "https://api.deepseek.com"

    /** 文本与图像理解模型（默认，支持 vision） */
    const val MODEL_FLASH = "deepseek-flash"

    /** 更强的推理模型（不支持图像理解） */
    const val MODEL_V4_PRO = "deepseek-v4-pro"

    val SUPPORTED_MODELS = listOf(MODEL_FLASH, MODEL_V4_PRO)

    /** 旧模型名 → 当前模型名（旧名仍可调用，但对应模型已下线） */
    private val LEGACY_MODEL_ALIASES = mapOf(
        "deepseek-chat" to MODEL_FLASH,
        "deepseek-v4-flash" to MODEL_FLASH,
        "deepseek-v4-flash-vision-exp" to MODEL_FLASH,
        "deepseek-reasoner" to MODEL_V4_PRO
    )

    // ── 思考强度 ──
    const val EFFORT_NONE = "none"
    const val EFFORT_LOW = "low"
    const val EFFORT_HIGH = "high"
    const val EFFORT_MAX = "max"

    const val DEFAULT_EFFORT = EFFORT_HIGH

    /** 界面展示顺序：值 → 中文标签 */
    val EFFORT_OPTIONS = listOf(
        EFFORT_NONE to "不思考",
        EFFORT_LOW to "低",
        EFFORT_HIGH to "高",
        EFFORT_MAX to "最大"
    )

    /**
     * 归一化思考强度。兼容旧版设置值（off / minimal / medium / xhigh / ultra）。
     * @return 合法强度值之一
     */
    fun normalizeEffort(raw: String?): String = when (raw?.trim()?.lowercase()) {
        null, "" -> DEFAULT_EFFORT
        "off", "disabled", "none" -> EFFORT_NONE
        "minimal", "low" -> EFFORT_LOW
        "medium", "high", "xhigh", "auto" -> EFFORT_HIGH
        "max", "ultra" -> EFFORT_MAX
        else -> DEFAULT_EFFORT
    }

    /** 该强度是否开启思考模式 */
    fun isThinkingEnabled(effort: String): Boolean = normalizeEffort(effort) != EFFORT_NONE

    /**
     * 归一化模型名：把旧模型名映射到当前有效模型名。
     * 用户如果填了自定义模型名（中转站等），原样保留。
     */
    fun normalizeModel(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) return MODEL_FLASH
        return LEGACY_MODEL_ALIASES[name.lowercase()] ?: name
    }

    /** 该模型是否支持图像理解 */
    fun supportsVision(model: String): Boolean = normalizeModel(model) == MODEL_FLASH

    /** 上下文长度 1M（deepseek-flash 与 deepseek-v4-pro 相同） */
    const val CONTEXT_WINDOW_TOKENS = 1_048_576L

    /** 单次请求最大输出 token（上限 384K） */
    const val MAX_OUTPUT_TOKENS = 393_216

    // ── 图像理解限制（见 Vision 指南「限制」） ──
    /** 单张图片最大大小：base64 / 外部 URL */
    const val MAX_IMAGE_BYTES_INLINE = 32L * 1024 * 1024
    /** 单个请求最大图片数 */
    const val MAX_IMAGES_PER_REQUEST = 600
    /** 不含 file_id 图片时，单请求图片总大小上限 */
    const val MAX_IMAGES_TOTAL_BYTES = 64L * 1024 * 1024
    /** 外部 URL 长度上限 */
    const val MAX_IMAGE_URL_LENGTH = 8192
    /** 请求体大小上限 */
    const val MAX_REQUEST_BODY_BYTES = 48L * 1024 * 1024
    /** 支持的图片格式 */
    val SUPPORTED_IMAGE_MIME = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    /** 支持的图片扩展名（用于文件选择器过滤） */
    val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

    /** 图片细节级别 */
    const val DETAIL_LOW = "low"
    const val DETAIL_HIGH = "high"
    const val DETAIL_ORIGINAL = "original"
    const val DETAIL_AUTO = "auto"

    /** 由扩展名推断 MIME（格式最终由服务端按文件内容判断） */
    fun mimeTypeForFileName(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }
}
