package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.utils.ProjectStorageManager
import cn.novelmaker.wg1337.ui.home.AppContextHolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI文件读写工具
 * 供AI通过Function Calling调用来读写项目文件
 */
class AiFileTools(private val projectName: String, private val projectId: String, private val mode: AiChatViewModel.AiMode = AiChatViewModel.AiMode.AGENT) {

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
                    else deleteFile(subDir, fileName)
                }
                "getProjectStructure" -> getProjectStructure()
                "markFinalized" -> {
                    val fileName = args?.optString("fileName", "") ?: ""
                    if (fileName.isEmpty()) "请指定文件名"
                    else markFinalized(fileName)
                }
                "listFinalized" -> listFinalized()
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
            if (file.delete()) "✅ 文件已删除：$subDir/$fileName"
            else "❌ 删除失败"
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
        val finalizedManager = FinalizedManager(AppContextHolder.context)
        val relPath = "小说主体/$fileName"
        finalizedManager.markFinalized(projectId, relPath)
        return "✅ 已将「$fileName」标记为定稿。"
    }

    private fun listFinalized(): String {
        val finalizedManager = FinalizedManager(AppContextHolder.context)
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

    companion object {
        private const val MAX_READ_SIZE = 1024 * 1024L
        private const val MAX_WRITE_SIZE = 5 * 1024 * 1024L
    }
}
