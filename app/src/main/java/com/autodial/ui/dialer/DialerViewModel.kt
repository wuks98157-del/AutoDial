package com.autodial.ui.dialer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autodial.data.db.entity.Recipe
import com.autodial.data.repository.HistoryRepository
import com.autodial.data.repository.RecipeRepository
import com.autodial.data.repository.SettingsRepository
import com.autodial.model.RunParams
import com.autodial.model.isActive
import com.autodial.service.RunForegroundService
import com.autodial.ui.common.PermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DialerUiState(
    val number: String = "",
    val cycles: Int = 10,
    val hangupSeconds: Int = 25,
    val targetPackage: String = "com.b3networks.bizphone",
    val spamMode: Boolean = false,
    val spamModeSafetyCap: Int = 9999,
    val interDigitDelayMs: Long = 250L,
    val bizPhoneRecipe: Recipe? = null,
    val mobileVoipRecipe: Recipe? = null,
    val isRunActive: Boolean = false,
    val canStart: Boolean = false,
    val startBlockReason: String = "",
    val accessibilityEnabled: Boolean = true,
    val bizPhoneStale: Boolean = false,
    val mobileVoipStale: Boolean = false,
    val recentNumbers: List<String> = emptyList(),
)

@HiltViewModel
class DialerViewModel @Inject constructor(
    private val recipeRepo: RecipeRepository,
    private val settingsRepo: SettingsRepository,
    private val historyRepo: HistoryRepository,
    private val permChecker: PermissionChecker,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(DialerUiState())
    val state: StateFlow<DialerUiState> = _state.asStateFlow()

    init {
        // Recent numbers — observed independently so the 4-flow combine stays
        // focused on start-blocking state. `setNumber` is called by the chip
        // tap path, which sets _state.value's `number` directly.
        viewModelScope.launch {
            historyRepo.observeRecentNumbers(limit = 5).collect { list ->
                _state.update { it.copy(recentNumbers = list) }
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepo.settings,
                recipeRepo.observeRecipe("com.b3networks.bizphone"),
                recipeRepo.observeRecipe("finarea.MobileVoip"),
                RunForegroundService.publicState
            ) { settings, bizRecipe, mobileRecipe, runState ->
                val perms = permChecker.checkAll()
                val current = _state.value
                val targetHasRecipe = if (current.targetPackage == "com.b3networks.bizphone")
                    bizRecipe != null else mobileRecipe != null
                val blockReason = when {
                    current.number.isEmpty() -> "Enter a phone number"
                    !perms.accessibility -> "Accessibility service disabled"
                    !perms.overlay -> "Overlay permission required"
                    !targetHasRecipe -> "No recipe recorded for selected app"
                    runState.isActive() -> "A run is already active"
                    else -> ""
                }
                val bizInstalledVersion = try {
                    context.packageManager.getPackageInfo("com.b3networks.bizphone", 0).versionName
                } catch (e: Exception) { null }
                val mobileInstalledVersion = try {
                    context.packageManager.getPackageInfo("finarea.MobileVoip", 0).versionName
                } catch (e: Exception) { null }
                val bizStale = bizRecipe != null && bizInstalledVersion != null &&
                               bizRecipe.recordedVersion != bizInstalledVersion
                val mobileStale = mobileRecipe != null && mobileInstalledVersion != null &&
                                  mobileRecipe.recordedVersion != mobileInstalledVersion
                current.copy(
                    cycles = settings.defaultCycles,
                    hangupSeconds = settings.defaultHangupSeconds,
                    targetPackage = current.targetPackage.ifEmpty { settings.defaultTargetPackage },
                    spamModeSafetyCap = settings.spamModeSafetyCap,
                    interDigitDelayMs = settings.interDigitDelayMs.toLong(),
                    bizPhoneRecipe = bizRecipe,
                    mobileVoipRecipe = mobileRecipe,
                    isRunActive = runState.isActive(),
                    canStart = blockReason.isEmpty(),
                    startBlockReason = blockReason,
                    accessibilityEnabled = perms.accessibility,
                    bizPhoneStale = bizStale,
                    mobileVoipStale = mobileStale
                )
            }.collect { _state.value = it }
        }
    }

    fun setNumber(n: String) {
        _state.update { it.copy(number = n.filter(Char::isDigit).take(15)) }
        revalidate()
    }

    fun setCycles(c: Int) { _state.update { it.copy(cycles = c.coerceIn(1, it.spamModeSafetyCap)) } }

    fun setHangupSeconds(s: Int) { _state.update { it.copy(hangupSeconds = s.coerceIn(1, 600)) } }

    fun setTarget(pkg: String) { _state.update { it.copy(targetPackage = pkg) }; revalidate() }

    fun setSpamMode(on: Boolean) { _state.update { it.copy(spamMode = on) } }

    fun startRun() {
        val s = _state.value
        if (!s.canStart) return
        RunForegroundService.start(context, RunParams(
            number = s.number,
            targetPackage = s.targetPackage,
            plannedCycles = if (s.spamMode) 0 else s.cycles,
            hangupSeconds = s.hangupSeconds,
            spamModeSafetyCap = s.spamModeSafetyCap,
            interDigitDelayMs = s.interDigitDelayMs
        ))
    }

    private fun revalidate() {
        val s = _state.value
        val targetHasRecipe = if (s.targetPackage == "com.b3networks.bizphone")
            s.bizPhoneRecipe != null else s.mobileVoipRecipe != null
        val reason = when {
            s.number.isEmpty() -> "Enter a phone number"
            !permChecker.isAccessibilityEnabled() -> "Accessibility service disabled"
            !permChecker.isOverlayGranted() -> "Overlay permission required"
            !targetHasRecipe -> "No recipe recorded for selected app"
            s.isRunActive -> "A run is already active"
            else -> ""
        }
        _state.update { it.copy(canStart = reason.isEmpty(), startBlockReason = reason) }
    }
}
