package com.autodial.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.autodial.data.repository.SettingsRepository
import com.autodial.ui.navigation.NavGraph
import com.autodial.ui.navigation.Screen
import com.autodial.ui.theme.AutoDialTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object { private const val TAG = "AutoDial" }

    @Inject lateinit var settingsRepository: SettingsRepository

    // Backed by Compose state so both the splash keep-condition and the
    // NavHost recompose when settings finish loading.
    private var startDest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.i(TAG, "MainActivity.onCreate saved=${savedInstanceState != null}")

        // No splash keep-condition: the splash dismisses on the first Compose
        // frame. Compose renders Dialer immediately (via the startDest
        // fallback below), so the user sees the app UI as fast as possible.
        // DataStore is pre-warmed in AutoDialApplication.onCreate so the
        // `settings.first()` call below usually completes within a few ms —
        // no visible flash before startDest resolves.
        lifecycleScope.launch {
            Log.i(TAG, "MainActivity loading settings…")
            val t0 = System.currentTimeMillis()
            val dest = try {
                val s = withTimeoutOrNull(2_000L) { settingsRepository.settings.first() }
                val onboardedFlag = s?.onboardingCompletedAt ?: -1L
                val resolved = if (onboardedFlag != 0L) Screen.Dialer.route
                               else Screen.Onboarding.route
                Log.i(TAG, "MainActivity settings loaded in ${System.currentTimeMillis() - t0}ms " +
                    "onboardedAt=$onboardedFlag dest=$resolved (timeout=${s == null})")
                resolved
            } catch (t: Throwable) {
                Log.e(TAG, "settings load failed; defaulting to Dialer", t)
                Screen.Dialer.route
            }
            startDest = dest
        }

        setContent {
            AutoDialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Render NavGraph UNCONDITIONALLY. If startDest hasn't
                    // resolved by first composition we start at Dialer; once
                    // the settings flow emits, it'll either match (no-op) or
                    // (first-time user) the Dialer's own VM will flip them
                    // to onboarding. Never leave Compose empty — empty
                    // composition shows the black window background forever.
                    val dest = startDest ?: Screen.Dialer.route
                    Log.i(TAG, "MainActivity composing NavGraph dest=$dest")
                    val navController = rememberNavController()
                    NavGraph(navController = navController, startDestination = dest)
                }
            }
        }
    }
}
