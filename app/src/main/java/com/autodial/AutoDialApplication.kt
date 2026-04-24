package com.autodial

import android.app.Application
import com.autodial.data.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AutoDialApplication : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // Pre-warm the DataStore on a background thread so MainActivity.onCreate
        // doesn't block on a cold disk read for first-frame rendering. Without
        // this, cold launch shows the splash for ~300-800ms while DataStore
        // reads the preferences file. Fire-and-forget; ignore failures — the
        // activity's own load path handles errors.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { settingsRepository.settings.first() } catch (_: Throwable) { /* ignore */ }
        }
    }
}
