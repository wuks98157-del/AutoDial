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
import com.autodial.model.RunState
import com.autodial.service.RunForegroundService
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

    // Bumped on every onResume; read inside setContent so the Compose tree is
    // forced to recompose whenever the activity comes back to foreground. Vivo
    // OriginOS has been observed to leave Compose frozen after the run's
    // foreground service terminates while MainActivity was paused — the
    // activity regains window focus but Compose never re-renders, leaving the
    // user on a fully-black Surface(BackgroundDark) view. Forcing a
    // recomposition unfreezes the runtime; if even that fails, the
    // recreate() fallback below kicks in.
    private var resumeTick by mutableStateOf(0)

    // Snapshotted at onPause: was a run active when we lost foreground? Used
    // by onResume to decide whether to recreate() the activity as a last-
    // resort recovery from the Compose-frozen state described above.
    private var pausedDuringActiveRun = false

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
                    // Reading resumeTick here ties this composition's lifetime
                    // to onResume(); when the activity comes back from the
                    // background after a run, the tick bump forces the tree
                    // to re-evaluate even if the Compose runtime missed the
                    // window-focus signal. Cheap on a normal resume (one extra
                    // recomposition), critical on Vivo's frozen-resume case.
                    val tick = resumeTick

                    // Render NavGraph UNCONDITIONALLY. If startDest hasn't
                    // resolved by first composition we start at Dialer; once
                    // the settings flow emits, it'll either match (no-op) or
                    // (first-time user) the Dialer's own VM will flip them
                    // to onboarding. Never leave Compose empty — empty
                    // composition shows the black window background forever.
                    val dest = startDest ?: Screen.Dialer.route
                    Log.i(TAG, "MainActivity composing NavGraph dest=$dest tick=$tick")
                    val navController = rememberNavController()
                    NavGraph(navController = navController, startDestination = dest)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "MainActivity.onStart")
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        val publicState = RunForegroundService.publicState.value
        Log.i(TAG, "MainActivity.onResume tick=$resumeTick publicState=${publicState::class.simpleName} pausedDuringActiveRun=$pausedDuringActiveRun")

        // Recovery path: if we were paused while a run was active, the
        // foreground service has since stopped, and Vivo's window manager has
        // sometimes left Compose unable to recompose into a visible tree.
        // recreate() forces a fresh onCreate which reliably renders correctly.
        // We only do this once per resume cycle — pausedDuringActiveRun is
        // cleared so a follow-up resume (e.g. user toggling away and back)
        // doesn't trigger an infinite recreate loop.
        if (pausedDuringActiveRun && publicState is RunState.Idle) {
            Log.i(TAG, "MainActivity.onResume: recovering from frozen-resume — recreate()")
            pausedDuringActiveRun = false
            recreate()
        }
    }

    override fun onPause() {
        super.onPause()
        val publicState = RunForegroundService.publicState.value
        pausedDuringActiveRun = publicState !is RunState.Idle
        Log.i(TAG, "MainActivity.onPause publicState=${publicState::class.simpleName} pausedDuringActiveRun=$pausedDuringActiveRun")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "MainActivity.onStop")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "MainActivity.onWindowFocusChanged hasFocus=$hasFocus")
    }
}
