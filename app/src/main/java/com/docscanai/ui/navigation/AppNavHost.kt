package com.docscanai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.docscanai.ui.screens.camera.CameraScreen
import com.docscanai.ui.screens.edit.PageEditScreen
import com.docscanai.ui.screens.export.ExportPdfScreen
import com.docscanai.ui.screens.library.LibraryScreen
import com.docscanai.ui.screens.onboarding.ModelDownloadScreen
import com.docscanai.ui.screens.review.PageReviewScreen
import com.docscanai.ui.screens.splash.SplashScreen
import com.docscanai.ui.screens.viewer.PageViewerScreen
import com.docscanai.ui.session.ScanSessionViewModel

/**
 * Single nav graph for the whole app (TECHNICAL_SPEC.md §6.2).
 *
 * Flow: Splash → ModelDownload (first run) → Camera → Review → Edit → Export, with
 * Camera → Library and Export → Library side-paths. The [ScanSessionViewModel] is created at this
 * level (scoped to the hosting Activity/ViewModelStore) so the active session is shared across
 * Camera/Review/Edit/Export navigation and survives rotation & process death.
 */
@Composable
fun AppNavHost(startDestination: String = Routes.SPLASH) {
    val navController = rememberNavController()
    val sessionViewModel: ScanSessionViewModel = viewModel(factory = ScanSessionViewModel.Factory)

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.SPLASH) {
            // Auto-route once, dropping the splash so Back never returns to it.
            SplashScreen(
                onModelsReady = {
                    navController.navigate(Routes.CAMERA) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onModelsMissing = {
                    navController.navigate(Routes.MODEL_DOWNLOAD) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.MODEL_DOWNLOAD) {
            // "Download complete" or "Skip" -> Camera; drop onboarding from the back stack.
            ModelDownloadScreen(
                onContinue = {
                    navController.navigate(Routes.CAMERA) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.CAMERA) {
            CameraScreen(
                viewModel = sessionViewModel,
                onOpenReview = { navController.navigate(Routes.REVIEW) },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) }
            )
        }

        composable(Routes.REVIEW) {
            PageReviewScreen(
                viewModel = sessionViewModel,
                // Tapping a captured page opens the full-screen zoomable viewer (SUBTASK F).
                onOpenPage = { pageId -> navController.navigate(Routes.viewerPage(pageId)) },
                onEditPage = { pageId -> navController.navigate(Routes.editPage(pageId)) },
                onExport = { navController.navigate(Routes.EXPORT) },
                onAddMore = { navController.navigate(Routes.CAMERA) }
            )
        }

        composable(
            route = Routes.VIEWER,
            arguments = listOf(navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            PageViewerScreen(
                viewModel = sessionViewModel,
                startPageId = backStackEntry.arguments?.getString(Routes.ARG_PAGE_ID).orEmpty(),
                // Back/close pops the viewer, returning to Review with the session intact.
                onClose = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.EDIT,
            arguments = listOf(navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            PageEditScreen(
                pageId = backStackEntry.arguments?.getString(Routes.ARG_PAGE_ID).orEmpty(),
                viewModel = sessionViewModel,
                onDone = { navController.popBackStack() }
            )
        }

        composable(Routes.EXPORT) {
            ExportPdfScreen(
                viewModel = sessionViewModel,
                onBack = { navController.popBackStack() },
                onNewScan = {
                    sessionViewModel.clear()
                    navController.navigate(Routes.CAMERA) {
                        popUpTo(Routes.CAMERA) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) }
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
