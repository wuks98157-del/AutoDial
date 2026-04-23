package com.autodial.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.autodial.data.repository.SettingsRepository
import com.autodial.ui.navigation.NavGraph
import com.autodial.ui.navigation.Screen
import com.autodial.ui.theme.AutoDialTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startDest = runBlocking {
            val s = settingsRepository.settings.first()
            if (s.onboardingCompletedAt > 0L) Screen.Dialer.route else Screen.Onboarding.route
        }

        setContent {
            AutoDialTheme {
                val navController = rememberNavController()
                NavGraph(navController = navController, startDestination = startDest)
            }
        }
    }
}
