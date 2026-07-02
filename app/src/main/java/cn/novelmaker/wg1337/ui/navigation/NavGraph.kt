package cn.novelmaker.wg1337.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import cn.novelmaker.wg1337.ui.editor.ChapterEditScreen
import cn.novelmaker.wg1337.ui.editor.EditorScreen
import cn.novelmaker.wg1337.ui.home.HomeScreen
import cn.novelmaker.wg1337.ui.onboarding.OnboardingScreen
import cn.novelmaker.wg1337.ui.settings.SettingsScreen
import java.net.URLDecoder

@Composable
fun NovelMakerNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onProjectClick = { projectName ->
                    navController.navigate(Routes.editor(projectName))
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectName") { type = NavType.StringType })
        ) { backStackEntry ->
            val projectName = backStackEntry.arguments?.getString("projectName") ?: ""
            EditorScreen(
                projectName = projectName,
                onBack = { navController.popBackStack() },
                onOpenEditor = { projName, projId ->
                    navController.navigate(Routes.chapterEdit(projName, projId))
                }
            )
        }

        composable(
            route = Routes.CHAPTER_EDIT,
            arguments = listOf(
                navArgument("projectName") { type = NavType.StringType },
                navArgument("projectId") { type = NavType.StringType; defaultValue = "" },
                navArgument("chapterId") { type = NavType.StringType; defaultValue = "" },
                navArgument("filePath") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val projectName = backStackEntry.arguments?.getString("projectName") ?: ""
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val chapterId = backStackEntry.arguments?.getString("chapterId") ?: ""
            val rawPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = if (rawPath.isNotEmpty()) URLDecoder.decode(rawPath, "UTF-8") else null
            ChapterEditScreen(
                projectName = projectName,
                projectId = projectId,
                chapterId = chapterId,
                filePath = filePath,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
