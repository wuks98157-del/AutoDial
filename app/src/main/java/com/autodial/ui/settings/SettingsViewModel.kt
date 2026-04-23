package com.autodial.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autodial.UserSettings
import com.autodial.data.repository.RecipeRepository
import com.autodial.data.repository.SettingsRepository
import com.autodial.oem.OemCompatibilityHelper
import com.autodial.ui.common.PermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: UserSettings? = null,
    val bizPhoneRecordedAt: Long? = null,
    val bizPhoneVersion: String? = null,
    val mobileVoipRecordedAt: Long? = null,
    val mobileVoipVersion: String? = null,
    val accessibilityOk: Boolean = false,
    val overlayOk: Boolean = false,
    val notificationsOk: Boolean = false,
    val batteryOk: Boolean = false,
    val devMenuTaps: Int = 0,
    val devMenuUnlocked: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val recipeRepo: RecipeRepository,
    private val permChecker: PermissionChecker
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val oemHelper = OemCompatibilityHelper.forManufacturer(Build.MANUFACTURER)

    init {
        viewModelScope.launch {
            combine(
                settingsRepo.settings,
                recipeRepo.observeRecipe("com.b3networks.bizphone"),
                recipeRepo.observeRecipe("finarea.MobileVoip")
            ) { s, biz, mobile ->
                val perms = permChecker.checkAll()
                _state.value.copy(
                    settings = s,
                    bizPhoneRecordedAt = biz?.recordedAt,
                    bizPhoneVersion = biz?.recordedVersion,
                    mobileVoipRecordedAt = mobile?.recordedAt,
                    mobileVoipVersion = mobile?.recordedVersion,
                    accessibilityOk = perms.accessibility,
                    overlayOk = perms.overlay,
                    notificationsOk = perms.notifications,
                    batteryOk = perms.batteryExempt
                )
            }.collect { _state.value = it }
        }
    }

    fun setDefaultHangup(s: Int) { viewModelScope.launch { settingsRepo.setDefaultHangupSeconds(s) } }
    fun setDefaultCycles(c: Int) { viewModelScope.launch { settingsRepo.setDefaultCycles(c) } }
    fun setSpamCap(cap: Int) { viewModelScope.launch { settingsRepo.setSpamModeSafetyCap(cap) } }
    fun setInterDigitDelay(ms: Int) { viewModelScope.launch { settingsRepo.setInterDigitDelayMs(ms) } }

    fun tapVersion() {
        val taps = _state.value.devMenuTaps + 1
        _state.update { it.copy(devMenuTaps = taps, devMenuUnlocked = taps >= 7) }
    }
}
