package cn.novelmaker.wg1337.ui.navigation

/**
 * 应用路由常量定义
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val EDITOR = "editor/{projectName}"
    const val CHAPTER_EDIT = "chapterEdit/{projectName}?projectId={projectId}?chapterId={chapterId}?filePath={filePath}"
    const val SETTINGS = "settings"

    fun editor(projectName: String) = "editor/$projectName"

    fun chapterEdit(
        projectName: String,
        projectId: String = "",
        chapterId: String = "",
        filePath: String = ""
    ): String {
        val encodedPath = filePath.let { if (it.isNotEmpty()) java.net.URLEncoder.encode(it, "UTF-8") else "" }
        return "chapterEdit/$projectName?projectId=$projectId?chapterId=$chapterId?filePath=$encodedPath"
    }
}
