package com.autodial.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autodial.data.repository.SettingsRepository
import com.autodial.oem.OemCompatibilityHelper
import com.autodial.ui.common.PermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    WELCOME,
    ACCESSIBILITY,
    OVERLAY,
    NOTIFICATIONS,
    BATTERY,
    OEM_SETUP,
    INSTALL_APPS,
    RECORD_BIZPHONE,
    RECORD_MOBILE_VOIP,
    DONE
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val accessibilityDone: Boolean = false,
    val overlayDone: Boolean = false,
    val notificationsDone: Boolean = false,
    val batteryDone: Boolean = false,
    val oemDone: Boolean = false,
    val bizPhoneInstalled: Boolean = false,
    val mobileVoipInstalled: Boolean = false,
    val bizPhoneRecipeRecorded: Boolean = false,
    val mobileVoipRecipeRecorded: Boolean = false,
    val oemHelper: OemCompatibilityHelper = OemCompatibilityHelper.forManufacturer(""),
    val skipOem: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val permChecker: PermissionChecker,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState(
        oemHelper = OemCompatibilityHelper.forManufacturer(android.os.Build.MANUFACTURER)
    ))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun refreshPermissions() {
        val perms = permChecker.checkAll()
        _state.update {
            it.copy(
                accessibilityDone = perms.accessibility,
                overlayDone = perms.overlay,
                notificationsDone = perms.notifications,
                batteryDone = perms.batteryExempt,
                bizPhoneInstalled = perms.bizPhoneInstalled,
                mobileVoipInstalled = perms.mobileVoipInstalled
            )
        }
    }

    fun advance() {
        val s = _state.value
        val next = when (s.currentStep) {
            OnboardingStep.WELCOME -> OnboardingStep.ACCESSIBILITY
            OnboardingStep.ACCESSIBILITY -> if (s.accessibilityDone) OnboardingStep.OVERLAY else return
            OnboardingStep.OVERLAY -> if (s.overlayDone) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) OnboardingStep.NOTIFICATIONS
                else OnboardingStep.BATTERY
            } else return
            OnboardingStep.NOTIFICATIONS -> if (s.notificationsDone) OnboardingStep.BATTERY else return
            OnboardingStep.BATTERY -> if (s.batteryDone) {
                if (s.oemHelper.getRequiredSettings().isEmpty()) OnboardingStep.INSTALL_APPS
                else OnboardingStep.OEM_SETUP
            } else return
            OnboardingStep.OEM_SETUP -> OnboardingStep.INSTALL_APPS
            OnboardingStep.INSTALL_APPS -> if (s.bizPhoneInstalled && s.mobileVoipInstalled) OnboardingStep.RECORD_BIZPHONE else return
            OnboardingStep.RECORD_BIZPHONE -> OnboardingStep.RECORD_MOBILE_VOIP
            OnboardingStep.RECORD_MOBILE_VOIP -> OnboardingStep.DONE
            OnboardingStep.DONE -> return
        }
        _state.update { it.copy(currentStep = next) }
    }

    fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openOverlaySettings() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun requestBatteryExemption() {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun markOnboardingComplete() {
        viewModelScope.launch { settingsRepo.markOnboardingComplete() }
    }

    fun markOemDone() { _state.update { it.copy(oemDone = true) } }
    fun markBizPhoneRecorded() { _state.update { it.copy(bizPhoneRecipeRecorded = true) }; advance() }
    fun markMobileVoipRecorded() { _state.update { it.copy(mobileVoipRecipeRecorded = true) }; advance() }
}
