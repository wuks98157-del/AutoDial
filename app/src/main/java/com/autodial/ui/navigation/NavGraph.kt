package com.autodial.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.autodial.ui.activerun.ActiveRunScreen
import com.autodial.ui.dialer.DialerScreen
import com.autodial.ui.history.HistoryScreen
import com.autodial.ui.onboarding.OnboardingScreen
import com.autodial.ui.onboarding.OnboardingViewModel
import com.autodial.ui.settings.SettingsScreen
import com.autodial.ui.wizard.WizardScreen

@Composable
fun NavGraph(navController: NavHostController, startDestination: String) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Onboarding.route) {
            val vm: OnboardingViewModel = hiltViewModel()
            OnboardingScreen(
                vm = vm,
                onComplete = {
                    navController.navigate(Screen.Dialer.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onOpenWizard = { pkg -> navController.navigate(Screen.Wizard(pkg).route) }
            )
        }

        composable(Screen.Dialer.route) {
            DialerScreen(
                onNavigateToActiveRun = { navController.navigate(Screen.ActiveRun.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToWizard = { pkg -> navController.navigate(Screen.Wizard(pkg).route) }
            )
        }

        composable(Screen.ActiveRun.route) {
            ActiveRunScreen(
                onRunEnd = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToWizard = { pkg -> navController.navigate(Screen.Wizard(pkg).route) },
                onNavigateUp = { navController.popBackStack() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(onNavigateUp = { navController.popBackStack() })
        }

        composable(
            route = Screen.Wizard.ROUTE,
            arguments = listOf(navArgument("targetPackage") { type = NavType.StringType })
        ) { backStackEntry ->
            val pkg = backStackEntry.arguments?.getString("targetPackage") ?: return@composable
            WizardScreen(
                targetPackage = pkg,
                onComplete = { navController.popBackStack() }
            )
        }
    }
}
