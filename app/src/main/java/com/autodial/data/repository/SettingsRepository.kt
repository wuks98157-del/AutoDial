package com.autodial.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.autodial.UserSettings
import com.autodial.data.datastore.SettingsKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            defaultHangupSeconds = prefs[SettingsKeys.HANGUP_SECONDS] ?: 25,
            defaultCycles = prefs[SettingsKeys.CYCLES] ?: 10,
            defaultTargetPackage = prefs[SettingsKeys.TARGET_PACKAGE] ?: "com.b3networks.bizphone",
            spamModeSafetyCap = prefs[SettingsKeys.SAFETY_CAP] ?: 9999,
            interDigitDelayMs = prefs[SettingsKeys.INTER_DIGIT_DELAY_MS] ?: 400,
            overlayX = prefs[SettingsKeys.OVERLAY_X] ?: 0,
            overlayY = prefs[SettingsKeys.OVERLAY_Y] ?: 200,
            onboardingCompletedAt = prefs[SettingsKeys.ONBOARDING_COMPLETED_AT] ?: 0L,
            verboseLoggingEnabled = prefs[SettingsKeys.VERBOSE_LOGGING] ?: false
        )
    }

    suspend fun setDefaultHangupSeconds(seconds: Int) {
        dataStore.edit { it[SettingsKeys.HANGUP_SECONDS] = seconds }
    }

    suspend fun setDefaultCycles(cycles: Int) {
        dataStore.edit { it[SettingsKeys.CYCLES] = cycles }
    }

    suspend fun setDefaultTargetPackage(pkg: String) {
        dataStore.edit { it[SettingsKeys.TARGET_PACKAGE] = pkg }
    }

    suspend fun setSpamModeSafetyCap(cap: Int) {
        dataStore.edit { it[SettingsKeys.SAFETY_CAP] = cap }
    }

    suspend fun setInterDigitDelayMs(ms: Int) {
        dataStore.edit { it[SettingsKeys.INTER_DIGIT_DELAY_MS] = ms }
    }

    suspend fun setOverlayPosition(x: Int, y: Int) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.OVERLAY_X] = x
            prefs[SettingsKeys.OVERLAY_Y] = y
        }
    }

    suspend fun markOnboardingComplete() {
        dataStore.edit { it[SettingsKeys.ONBOARDING_COMPLETED_AT] = System.currentTimeMillis() }
    }

    suspend fun setVerboseLogging(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.VERBOSE_LOGGING] = enabled }
    }
}
