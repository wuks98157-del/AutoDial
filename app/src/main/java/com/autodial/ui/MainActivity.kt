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
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.i(TAG, "MainActivity.onCreate saved=${savedInstanceState != null}")

        // Resolve the start destination off the main thread. Previously this
        // used runBlocking, which on OPPO ColorOS routinely killed MainActivity
        // while a run was in progress in the target app — when finishRun fired
        // startActivity, the cold re-creation's runBlocking competed with
        // Hilt/Room/DataStore warm-up for the main thread and the window
        // rendered black until force-closed. Windows' background is
        // @android:color/black (themes.xml), so ANY composition gap shows as
        // black. Two safeguards: a 2s timeout that defaults to Dialer so we
        // never get stuck on a slow DataStore read, and an explicit dark
        // Surface behind the NavHost so there's a real Compose background.
        splash.setKeepOnScreenCondition { startDest == null }
        lifecycleScope.launch {
            Log.i(TAG, "MainActivity loading settings…")
            val t0 = System.currentTimeMillis()
            val s = withTimeoutOrNull(2_000L) { settingsRepository.settings.first() }
            val onboardedFlag = s?.onboardingCompletedAt ?: -1L
            val dest = if (onboardedFlag != 0L) Screen.Dialer.route
                       else Screen.Onboarding.route
            Log.i(TAG, "MainActivity settings loaded in ${System.currentTimeMillis() - t0}ms " +
                "onboardedAt=$onboardedFlag dest=$dest (timeout=${s == null})")
            startDest = dest
        }

        setContent {
            AutoDialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val dest = startDest
                    if (dest != null) {
                        Log.i(TAG, "MainActivity composing NavGraph dest=$dest")
                        val navController = rememberNavController()
                        NavGraph(navController = navController, startDestination = dest)
                    }
                }
            }
        }
    }
}
