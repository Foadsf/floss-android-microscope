package com.floss.microscope.presentation.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.floss.microscope.presentation.viewmodel.CaptureViewModel
import com.floss.microscope.presentation.viewmodel.ProcessingViewModel
import com.floss.microscope.presentation.viewmodel.ProjectViewModel
import java.util.UUID

@Composable
fun MicroscopeNavigationHost(
    darkTheme: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
    highContrast: Boolean,
    onToggleHighContrast: (Boolean) -> Unit
) {
    val navController = rememberNavController()
    val projectViewModel: ProjectViewModel = hiltViewModel()
    val captureViewModel: CaptureViewModel = hiltViewModel()
    val processingViewModel: ProcessingViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = projectViewModel,
                onStartCapture = { projectId ->
                    val route = if (projectId != null) "capture?projectId=$projectId" else "capture"
                    navController.navigate(route)
                },
                onOpenSession = { sessionId ->
                    processingViewModel.loadCompletedSession(sessionId)
                    navController.navigate("result/$sessionId")
                },
                onOpenSettings = { navController.navigate("settings") },
                onOpenOnboarding = { navController.navigate("onboarding") }
            )
        }

        composable(
            route = "capture?projectId={projectId}",
            arguments = listOf(navArgument("projectId") { nullable = true; defaultValue = null })
        ) { backStackEntry ->
            val projectIdStr = backStackEntry.arguments?.getString("projectId")
            val projectId = projectIdStr?.let { UUID.fromString(it) }
            CaptureScreen(
                viewModel = captureViewModel,
                projectId = projectId,
                onNavigateBack = { navController.popBackStack() },
                onSessionCaptured = { sessionId ->
                    processingViewModel.startProcessingSession(sessionId)
                    navController.navigate("processing/$sessionId") {
                        popUpTo("home")
                    }
                }
            )
        }

        composable("processing/{sessionId}") { backStackEntry ->
            val sessionId = UUID.fromString(backStackEntry.arguments?.getString("sessionId")!!)
            ProcessingScreen(
                viewModel = processingViewModel,
                onProcessingComplete = {
                    navController.navigate("result/$sessionId") {
                        popUpTo("home")
                    }
                },
                onCancel = {
                    processingViewModel.cancelProcessing()
                    navController.popBackStack()
                }
            )
        }

        composable("result/{sessionId}") { backStackEntry ->
            val sessionId = UUID.fromString(backStackEntry.arguments?.getString("sessionId")!!)
            ResultViewerScreen(
                sessionId = sessionId,
                viewModel = processingViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProcessing = {
                    navController.navigate("processing/$sessionId")
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                captureViewModel = captureViewModel,
                darkTheme = darkTheme,
                onToggleDarkTheme = onToggleDarkTheme,
                highContrast = highContrast,
                onToggleHighContrast = onToggleHighContrast,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("onboarding") {
            OnboardingScreen(onDismiss = { navController.popBackStack() })
        }
    }
}
