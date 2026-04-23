package com.autodial.data.repository

import androidx.datastore.core.DataStore
import com.autodial.UserSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<UserSettings>) {

    val settings: Flow<UserSettings> = dataStore.data

    suspend fun setDefaultHangupSeconds(seconds: Int) {
        dataStore.updateData { it.toBuilder().setDefaultHangupSeconds(seconds).build() }
    }

    suspend fun setDefaultCycles(cycles: Int) {
        dataStore.updateData { it.toBuilder().setDefaultCycles(cycles).build() }
    }

    suspend fun setDefaultTargetPackage(pkg: String) {
        dataStore.updateData { it.toBuilder().setDefaultTargetPackage(pkg).build() }
    }

    suspend fun setSpamModeSafetyCap(cap: Int) {
        dataStore.updateData { it.toBuilder().setSpamModeSafetyCap(cap).build() }
    }

    suspend fun setInterDigitDelayMs(ms: Int) {
        dataStore.updateData { it.toBuilder().setInterDigitDelayMs(ms).build() }
    }

    suspend fun setOverlayPosition(x: Int, y: Int) {
        dataStore.updateData { it.toBuilder().setOverlayX(x).setOverlayY(y).build() }
    }

    suspend fun markOnboardingComplete() {
        dataStore.updateData {
            it.toBuilder().setOnboardingCompletedAt(System.currentTimeMillis()).build()
        }
    }

    suspend fun setVerboseLogging(enabled: Boolean) {
        dataStore.updateData { it.toBuilder().setVerboseLoggingEnabled(enabled).build() }
    }
}
