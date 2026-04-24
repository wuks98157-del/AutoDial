package com.autodial.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.autodial.ui.activerun.ActiveRunScreen
import com.autodial.ui.dialer.DialerScreen
import com.autodial.ui.history.HistoryScreen
import com.autodial.ui.onboarding.OnboardingScreen
import com.autodial.ui.onboarding.OnboardingViewModel
import com.autodial.ui.settings.SettingsScreen
import com.autodial.wizard.WizardLauncher

@Composable
fun NavGraph(navController: NavHostController, startDestination: String) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Onboarding.route) {
            val vm: OnboardingViewModel = hiltViewModel()
            val context = LocalContext.current
            OnboardingScreen(
                vm = vm,
                onComplete = {
                    navController.navigate(Screen.Dialer.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onOpenWizard = { pkg -> WizardLauncher.launch(context, pkg) }
            )
        }

        composable(Screen.Dialer.route) {
            val context = LocalContext.current
            DialerScreen(
                onNavigateToActiveRun = { navController.navigate(Screen.ActiveRun.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onBeginWizard = { pkg -> WizardLauncher.launch(context, pkg) }
            )
        }

        composable(Screen.ActiveRun.route) {
            ActiveRunScreen(
                onRunEnd = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            val context = LocalContext.current
            SettingsScreen(
                onNavigateToWizard = { pkg -> WizardLauncher.launch(context, pkg) },
                onNavigateUp = { navController.popBackStack() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(onNavigateUp = { navController.popBackStack() })
        }
    }
}
