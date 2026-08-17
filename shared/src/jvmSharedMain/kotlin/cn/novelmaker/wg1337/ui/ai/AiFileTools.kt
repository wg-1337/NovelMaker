package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.utils.ProjectStorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * AI文件读写工具
 * 供AI通过Function Calling调用来读写项目文件
 */
class AiFileTools(
    private val projectName: String,
    private val projectId: String,
    private val mode: AiChatViewModel.AiMode = AiChatViewModel.AiMode.AGENT,
    private val onFileChanged: (() -> Unit)? = null,
    private val fetchPlainText: Boolean = true
) {

    private val projectDir = ProjectStorageManager.getProjectDir(projectName)

    // ───────────────────── 工具定义（Function Calling Schema） ─────────────────────

    /**
     * 返回 tools 定义列表（DeepSeek API 原生 Function Calling 格式）
     */
    fun getToolDefinitions(): JSONArray {
        val toolsArray = JSONArray()

        // 1. listFiles - 列出文件
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "listFiles")
                put("description", "列出项目指定子目录下的所有文件")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("subDir", JSONObject().apply {
                            put("type", "string")
                            put("description", "子目录名，可选值：大纲/提示词/小说主体")
                            put("enum", JSONArray().apply {
                                put("大纲"); put("提示词"); put("小说主体")
                            })
                        })
                    })
                    put("required", JSONArray().put("subDir"))
                    put("additionalProperties", false)
                })
            })
        })

        // 2. readFile - 读取文件
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "readFile")
                put("description", "读取项目中的文件内容")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("subDir", JSONObject().apply {
                            put("type", "string")
                            put("description", "子目录名")
                            put("enum", JSONArray().apply {
                                put("大纲"); put("提示词"); put("小说主体")
                            })
                        })
                        put("fileName", JSONObject().apply {
                            put("type", "string")
                            put("description", "文件名，含扩展名，如：第1章_初入异世.txt")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("subDir"); put("fileName")
                    })
                    put("additionalProperties", false)
                })
            })
        })

        // 3. writeFile - 写入文件
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "writeFile")
                put("description", "创建或覆盖项目中的文件")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("subDir", JSONObject().apply {
                            put("type", "string")
                            put("description", "子目录名")
                            put("enum", JSONArray().apply {
                                put("大纲"); put("提示词"); put("小说主体")
                            })
                        })
                        put("fileName", JSONObject().apply {
                            put("type", "string")
                            put("description", "文件名，含扩展名")
                        })
                        put("content", JSONObject().apply {
                            put("type", "string")
                            put("description", "文件内容")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("subDir"); put("fileName"); put("content")
                    })
                    put("additionalProperties", false)
                })
            })
        })

        // 4. deleteFile - 删除文件
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "deleteFile")
                put("description", "删除项目中的文件")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("subDir", JSONObject().apply {
                            put("type", "string")
                            put("description", "子目录名")
                            put("enum", JSONArray().apply {
                                put("大纲"); put("提示词"); put("小说主体")
                            })
                        })
                        put("fileName", JSONObject().apply {
                            put("type", "string")
                            put("description", "文件名，含扩展名")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("subDir"); put("fileName")
                    })
                    put("additionalProperties", false)
                })
            })
        })

        // 5. getProjectStructure - 获取目录结构
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "getProjectStructure")
                put("description", "获取项目完整的目录结构概览")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                    put("additionalProperties", false)
                })
            })
        })

        // 6. markFinalized - 标记章节为定稿（纳入缓存前缀）
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "markFinalized")
                put("description", "将小说主体中的章节标记为已定稿。已定稿的章节会作为固定前缀纳入缓存。仅限小说主体目录下的文件。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("fileName", JSONObject().apply {
                            put("type", "string")
                            put("description", "文件名，含扩展名")
                        })
                    })
                    put("required", JSONArray().put("fileName"))
                    put("additionalProperties", false)
                })
            })
        })

        // 7. listFinalized - 查看已定稿章节
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "listFinalized")
                put("description", "查看当前项目已标记为定稿的章节文件列表")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                    put("additionalProperties", false)
                })
            })
        })

        // 8. webSearch - 联网搜索（优先 Bing）
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "webSearch")
                put("description", "联网搜索互联网获取最新、不确定或未知的信息。当用户提到你不了解的内容、不确定的事实、没见过的文体/概念，或需要最新资料、范文示例时使用。优先使用 bing 搜索引擎，google 作为备选。搜索关键词由你根据用户需求自行拟定。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索关键词，根据用户当前需求自行拟定，尽量简洁、具体")
                        })
                        put("engine", JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索引擎，默认 bing（优先），可选 google")
                            put("enum", JSONArray().apply {
                                put("bing"); put("google")
                            })
                        })
                    })
                    put("required", JSONArray().put("query"))
                    put("additionalProperties", false)
                })
            })
        })

        // 9. fetchUrl - 打开网页获取 HTML
        toolsArray.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "fetchUrl")
                put("description", "打开指定网址，获取网页 HTML 原文。当需要查看搜索结果中某个链接的具体内容、获取参考资料或文章原文时使用。仅支持 http/https 网址，页面过大时会截断。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().apply {
                            put("type", "string")
                            put("description", "完整的网页地址，必须以 http:// 或 https:// 开头，例如 https://example.com/article/1")
                        })
                    })
                    put("required", JSONArray().put("url"))
                    put("additionalProperties", false)
                })
            })
        })

        return toolsArray
    }

    /**
     * 执行工具调用，返回工具执行结果
     * @param toolName 函数名
     * @param arguments JSON参数字符串（可能为空）
     * @return 工具执行结果字符串
     */
    fun executeToolCall(toolName: String, arguments: String): String {
        return try {
            // 处理空参数或无效JSON
            val args = if (arguments.isBlank()) null else {
                try { JSONObject(arguments) } catch (_: Exception) { null }
            }
            when (toolName) {
                "listFiles" -> {
                    val subDir = args?.optString("subDir", "") ?: ""
                    if (subDir.isEmpty()) "请指定子目录名（大纲/小说主体）"
                    else listFiles(subDir)
                }
                "readFile" -> {
                    val subDir = args?.optString("subDir", "") ?: ""
                    val fileName = args?.optString("fileName", "") ?: ""
                    if (subDir.isEmpty() || fileName.isEmpty()) "请指定子目录和文件名"
                    else readFile(subDir, fileName)
                }
                "writeFile" -> {
                    val subDir = args?.optString("subDir", "") ?: ""
                    if (mode == AiChatViewModel.AiMode.PLAN && subDir == "小说主体") {
                        "⚠️ Plan 模式下不能写入小说主体。请先完成计划，切换到 Agent 模式后再开始写作。"
                    } else {
                        val fileName = args?.optString("fileName", "") ?: ""
                        val content = args?.optString("content", "") ?: ""
                        if (subDir.isEmpty() || fileName.isEmpty()) "请指定子目录、文件名和内容"
                        else writeFile(subDir, fileName, content)
                    }
                }
                "deleteFile" -> {
                    val subDir = args?.optString("subDir", "") ?: ""
                    val fileName = args?.optString("fileName", "") ?: ""
                    if (subDir.isEmpty() || fileName.isEmpty()) "请指定子目录和文件名"
                    else if (mode == AiChatViewModel.AiMode.PLAN && subDir == "小说主体") "⚠️ Plan 模式下不能删除小说主体文件。请先完成计划，切换到 Agent 模式后再操作。"
                    else deleteFile(subDir, fileName)
                }
                "getProjectStructure" -> getProjectStructure()
                "markFinalized" -> {
                    val fileName = args?.optString("fileName", "") ?: ""
                    if (fileName.isEmpty()) "请指定文件名"
                    else markFinalized(fileName)
                }
                "listFinalized" -> listFinalized()
                "webSearch" -> {
                    val query = args?.optString("query", "") ?: ""
                    val engine = args?.optString("engine", "bing") ?: "bing"
                    if (query.isBlank()) "请提供搜索关键词"
                    else searchWeb(query, engine)
                }
                "fetchUrl" -> {
                    val url = args?.optString("url", "") ?: ""
                    if (url.isBlank()) "请提供要打开的网址"
                    else fetchUrl(url)
                }
                else -> "未知工具: $toolName"
            }
        } catch (e: Exception) {
            "❌ 工具调用错误: ${e.message}"
        }
    }

    // ───────────────────── 工具函数实现 ─────────────────────

    /**
     * 列出项目子目录下的文件
     */
    fun listFiles(subDir: String): String {
        val dir = File(projectDir, subDir)
        if (!dir.exists()) return "目录「$subDir」不存在"
        val files = dir.listFiles()
        if (files == null) return "无法读取目录「$subDir」（I/O错误或权限不足）"
        if (files.isEmpty()) return "「$subDir」目录下没有文件"

        val result = StringBuilder("【$subDir】目录下的文件：\n")
        files.sortedBy { it.name }.forEachIndexed { index, file ->
            if (file.isDirectory) {
                result.appendLine("  ${index + 1}. 📁 ${file.name}/")
            } else {
                val size = if (file.length() > 1024) "${file.length() / 1024}KB" else "${file.length()}B"
                result.appendLine("  ${index + 1}. 📄 ${file.name} ($size)")
            }
        }
        return result.toString()
    }

    /**
     * 读取项目中的文件内容
     */
    fun readFile(subDir: String, fileName: String): String {
        val safeDir = sanitizeFileName(subDir)
        val safeName = sanitizeFileName(fileName)
        val file = File(File(projectDir, safeDir), safeName)
        if (!file.exists()) return "文件不存在：$subDir/$fileName"
        if (file.length() > MAX_READ_SIZE) return "文件过大（超过${MAX_READ_SIZE / 1024 / 1024}MB），拒绝读取"
        return try {
            file.readText()
        } catch (e: Exception) {
            "读取文件失败：${e.message}"
        }
    }

    /**
     * 写入/覆盖文件
     */
    fun writeFile(subDir: String, fileName: String, content: String): String {
        if (content.length > MAX_WRITE_SIZE) return "内容过大（超过${MAX_WRITE_SIZE / 1024 / 1024}MB），拒绝写入"
        return try {
            val safeDir = sanitizeFileName(subDir)
            val dir = File(projectDir, safeDir)
            if (!dir.exists()) dir.mkdirs()
            // 确保文件名合法
            val safeName = sanitizeFileName(fileName)
            val safeFile = File(dir, safeName)
            safeFile.writeText(content)
            onFileChanged?.invoke()
            "✅ 文件已创建/更新：$subDir/$safeName（${content.length}字符）"
        } catch (e: Exception) {
            "❌ 写入文件失败：${e.message}"
        }
    }

    /**
     * 删除文件
     */
    fun deleteFile(subDir: String, fileName: String): String {
        return try {
            val file = File(File(projectDir, subDir), fileName)
            if (!file.exists()) return "文件不存在：$subDir/$fileName"
            if (file.delete()) {
                onFileChanged?.invoke()
                "✅ 文件已删除：$subDir/$fileName"
            } else "❌ 删除失败"
        } catch (e: Exception) {
            "❌ 删除文件失败：${e.message}"
        }
    }

    /**
     * 获取项目目录结构概览
     */
    fun getProjectStructure(): String {
        if (!projectDir.exists()) return "项目目录不存在"
        val sb = StringBuilder("📁 项目目录结构：\n")
        sb.appendLine("  ${projectDir.name}/")
        for (subDir in ProjectStorageManager.SUB_DIRS) {
            val dir = File(projectDir, subDir)
            if (dir.exists()) {
                val files = dir.listFiles() ?: emptyArray()
                sb.appendLine("    ├── $subDir/ (${files.size}个文件)")
            } else {
                sb.appendLine("    ├── $subDir/ (目录不存在)")
            }
        }
        return sb.toString()
    }

    /**
     * AI 标记章节为定稿（纳入缓存前缀）
     */
    private fun markFinalized(fileName: String): String {
        val file = File(File(projectDir, "小说主体"), fileName)
        if (!file.exists()) return "❌ 文件不存在：小说主体/$fileName"
        if (file.isDirectory) return "❌ 不能标记文件夹，只能标记 .txt 章节文件"
        val finalizedManager = FinalizedManager()
        val relPath = "小说主体/$fileName"
        finalizedManager.markFinalized(projectId, relPath)
        onFileChanged?.invoke()
        return "✅ 已将「$fileName」标记为定稿。"
    }

    private fun listFinalized(): String {
        val finalizedManager = FinalizedManager()
        val files = finalizedManager.getFinalizedFiles(projectId)
        if (files.isEmpty()) return "暂无已定稿的章节。"
        return "【已定稿章节】\n" + files.sorted().joinToString("\n") { "  · $it" }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()
            .ifEmpty { "unnamed" }
    }

    // ───────────────────── 联网搜索 ─────────────────────

    /**
     * 联网搜索：优先 Bing，Google 备选。
     * 请求搜索结果页并解析出 标题/链接/摘要 列表，返回纯文本给 AI（不返回原始 HTML）。
     */
    fun searchWeb(query: String, engine: String = "bing"): String {
        val useGoogle = engine.equals("google", ignoreCase = true)
        val urlStr = if (useGoogle) {
            "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}&num=8&hl=zh-CN"
        } else {
            "https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}&count=8&setlang=zh-hans"
        }
        return try {
            val html = httpGet(urlStr, SEARCH_TIMEOUT_MS, MAX_SEARCH_HTML)
            parseSearchResults(html, useGoogle, query)
        } catch (e: Exception) {
            "❌ 搜索失败：${e.message}"
        }
    }

    /**
     * 打开指定网址获取页面内容（供 AI 查看页面原文/参考资料）。
     * 仅支持 http/https；页面超过上限时截断并提示。
     * fetchPlainText=true 时提取正文纯文本（省 Token），否则返回原始 HTML。
     */
    fun fetchUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "❌ 仅支持 http/https 网址：$trimmed"
        }
        return try {
            val raw = httpGet(trimmed, FETCH_TIMEOUT_MS, MAX_FETCH_HTML + 1)
            val text = if (fetchPlainText) htmlToText(raw) else raw
            if (text.length > MAX_FETCH_HTML) {
                "⚠️ 内容较大，已截断到前 ${MAX_FETCH_HTML / 1024 / 1024}MB：\n" + text.take(MAX_FETCH_HTML)
            } else {
                text
            }
        } catch (e: Exception) {
            "❌ 打开页面失败：${e.message}"
        }
    }

    /** 从 HTML 提取正文纯文本：去掉 script/style、块级标签转换行、解码实体、压缩空白 */
    private fun htmlToText(html: String): String {
        return html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li|h[1-6]|tr|blockquote|section|article|pre|ul|ol)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
            .replace(Regex("</(td|th)>", RegexOption.IGNORE_CASE), "\t")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * GET 请求指定网址，返回响应文本。
     * 最多读取 maxChars 个字符，避免超大页面占满内存。
     */
    private fun httpGet(urlStr: String, timeoutMs: Int, maxChars: Int): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw Exception("HTTP $code")
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            try {
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (total < maxChars) {
                    val n = reader.read(buf, 0, minOf(buf.size, maxChars - total))
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                return sb.toString()
            } finally {
                reader.close()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 解析搜索结果页 HTML（结构多变，尽力提取） */
    private fun parseSearchResults(html: String, isGoogle: Boolean, query: String): String {
        val engineName = if (isGoogle) "Google" else "Bing"
        val sb = StringBuilder("【$engineName 搜索结果：$query】\n")
        var count = 0
        if (isGoogle) {
            // Google：<a href="/url?q=URL&amp;...">标题</a>，摘要 <span class="aCOpRe"> / <div class="VwiC3b">
            val linkRegex = Regex("<a[^>]*href=\"/url\\?q=([^&\"]+)[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            for (m in linkRegex.findAll(html)) {
                if (count >= MAX_SEARCH_RESULTS) break
                val url = try { URLDecoder.decode(m.groupValues[1].replace("&amp;", "&"), "UTF-8") } catch (_: Exception) { m.groupValues[1] }
                val title = stripHtml(m.groupValues[2]).ifEmpty { "无标题" }
                sb.appendLine("${count + 1}. $title")
                sb.appendLine("   链接: $url")
                count++
            }
        } else {
            // Bing：<li class="b_algo"> 结果块
            val itemRegex = Regex("<li class=\"b_algo\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            for (item in itemRegex.findAll(html)) {
                if (count >= MAX_SEARCH_RESULTS) break
                val block = item.groupValues[1]
                val linkM = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(block)
                val title = linkM?.let { stripHtml(it.groupValues[2]) }?.ifEmpty { "无标题" } ?: "无标题"
                val url = linkM?.groupValues?.get(1)?.trim().orEmpty()
                val snipM = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(block)
                val snip = snipM?.let { stripHtml(it.groupValues[1]) }.orEmpty()
                sb.appendLine("${count + 1}. $title")
                if (url.isNotEmpty()) sb.appendLine("   链接: $url")
                if (snip.isNotEmpty()) sb.appendLine("   摘要: $snip")
                count++
            }
        }
        if (count == 0) return "未解析到搜索结果（可能页面结构变化或被拦截）。"
        return sb.toString()
    }

    /** 去除 HTML 标签、解码常见实体、压缩空白 */
    private fun stripHtml(s: String): String {
        return s.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val MAX_READ_SIZE = 1024 * 1024L
        private const val MAX_WRITE_SIZE = 5 * 1024 * 1024L
        private const val MAX_SEARCH_HTML = 5 * 1024 * 1024  // 搜索结果页最多读取 5MB
        private const val MAX_FETCH_HTML = 5 * 1024 * 1024   // 打开页面最多读取 5MB
        private const val MAX_SEARCH_RESULTS = 6             // 最多返回 6 条结果
        private const val SEARCH_TIMEOUT_MS = 8000           // 搜索超时 8 秒
        private const val FETCH_TIMEOUT_MS = 10000           // 打开页面超时 10 秒
    }
}
