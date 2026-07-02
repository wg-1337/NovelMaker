package cn.novelmaker.wg1337.data.model

/**
 * 章节数据模型
 */
data class Chapter(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val projectId: String,
    val content: String = "",
    val wordCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)
