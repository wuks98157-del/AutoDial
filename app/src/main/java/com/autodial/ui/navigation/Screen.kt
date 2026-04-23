package com.autodial.ui.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Dialer : Screen("dialer")
    object ActiveRun : Screen("active_run")
    object Settings : Screen("settings")
    object History : Screen("history")
    data class Wizard(val targetPackage: String) : Screen("wizard/$targetPackage") {
        companion object { const val ROUTE = "wizard/{targetPackage}" }
    }
}
