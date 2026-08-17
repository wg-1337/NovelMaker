package cn.novelmaker.wg1337.data.model

import java.io.File
import java.util.UUID

/**
 * 项目数据模型
 */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val wordCount: Int = 0,
    val chapterCount: Int = 0
) {
    /**
     * 获取项目在本地存储的目录名（基于 id）
     */
    fun getStorageDirName(): String = "project_$id"
}
