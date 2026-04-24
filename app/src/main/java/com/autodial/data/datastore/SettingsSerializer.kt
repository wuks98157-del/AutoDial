package com.autodial.data.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SettingsKeys {
    val HANGUP_SECONDS = intPreferencesKey("hangup_seconds")
    val CYCLES = intPreferencesKey("cycles")
    val TARGET_PACKAGE = stringPreferencesKey("target_package")
    val SAFETY_CAP = intPreferencesKey("safety_cap")
    val INTER_DIGIT_DELAY_MS = intPreferencesKey("inter_digit_delay_ms")
    val OVERLAY_X = intPreferencesKey("overlay_x")
    val OVERLAY_Y = intPreferencesKey("overlay_y")
    val ONBOARDING_COMPLETED_AT = longPreferencesKey("onboarding_completed_at")
    val VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging")
}
