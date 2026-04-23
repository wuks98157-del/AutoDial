package com.autodial.ui.wizard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autodial.accessibility.AutoDialAccessibilityService
import com.autodial.data.db.entity.Recipe
import com.autodial.data.db.entity.RecipeStep
import com.autodial.data.repository.RecipeRepository
import com.autodial.model.RecordedStep
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WizardUiState(
    val targetPackage: String = "",
    val displayName: String = "",
    val currentStepId: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val instruction: String = "",
    val isRecording: Boolean = false,
    val lastCaptured: RecordedStep? = null,
    val showMissingIdWarning: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)

val WIZARD_STEPS = listOf(
    "OPEN_DIAL_PAD",
    "DIGIT_0", "DIGIT_1", "DIGIT_2", "DIGIT_3", "DIGIT_4",
    "DIGIT_5", "DIGIT_6", "DIGIT_7", "DIGIT_8", "DIGIT_9",
    "PRESS_CALL",
    "HANG_UP_RINGING",
    "HANG_UP_CONNECTED",
    "RETURN_TO_DIAL_PAD"
)

fun stepInstruction(stepId: String, appName: String): String = when {
    stepId == "OPEN_DIAL_PAD" -> "Open $appName and tap the keypad/dial pad icon."
    stepId.startsWith("DIGIT_") -> "Tap the '${stepId.removePrefix("DIGIT_")}' digit on the dial pad."
    stepId == "PRESS_CALL" -> "Tap the call / green phone button."
    stepId == "HANG_UP_RINGING" -> "Call any number. While it's ringing (before it's picked up), tap the cancel/end button."
    stepId == "HANG_UP_CONNECTED" -> "Call again and wait until connected (or voicemail picks up). Tap the end-call button."
    stepId == "RETURN_TO_DIAL_PAD" -> "After hanging up, tap the button or gesture to return to the dial pad."
    else -> ""
}

@HiltViewModel
class WizardViewModel @Inject constructor(
    private val recipeRepo: RecipeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(WizardUiState())
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    private val capturedSteps = mutableListOf<RecordedStep>()

    fun init(targetPackage: String) {
        val name = if (targetPackage == "com.b3networks.bizphone") "BizPhone" else "Mobile VOIP"
        _state.value = WizardUiState(
            targetPackage = targetPackage,
            displayName = name,
            currentStepId = WIZARD_STEPS[0],
            stepIndex = 0,
            totalSteps = WIZARD_STEPS.size,
            instruction = stepInstruction(WIZARD_STEPS[0], name)
        )
        viewModelScope.launch {
            AutoDialAccessibilityService.instance?.recordedSteps()?.collect { recorded ->
                if (recorded.stepId == _state.value.currentStepId) {
                    onStepCaptured(recorded)
                }
            }
        }
    }

    fun startRecording() {
        val s = _state.value
        val service = AutoDialAccessibilityService.instance
        if (service == null) {
            _state.update { it.copy(error = "Accessibility service not enabled") }
            return
        }
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(s.targetPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent != null) context.startActivity(launchIntent)

        service.startRecording(s.currentStepId, s.targetPackage)
        _state.update { it.copy(isRecording = true, error = null) }
    }

    fun reRecord() {
        _state.update { it.copy(lastCaptured = null, isRecording = false) }
        startRecording()
    }

    fun confirmAndAdvance() {
        val captured = _state.value.lastCaptured ?: return
        capturedSteps.add(captured)
        val nextIndex = _state.value.stepIndex + 1
        if (nextIndex >= WIZARD_STEPS.size) {
            saveRecipe()
        } else {
            val nextStepId = WIZARD_STEPS[nextIndex]
            _state.update {
                it.copy(
                    currentStepId = nextStepId,
                    stepIndex = nextIndex,
                    instruction = stepInstruction(nextStepId, it.displayName),
                    lastCaptured = null,
                    isRecording = false,
                    showMissingIdWarning = false
                )
            }
        }
    }

    private fun onStepCaptured(step: RecordedStep) {
        AutoDialAccessibilityService.instance?.stopRecording()
        _state.update {
            it.copy(
                isRecording = false,
                lastCaptured = step,
                showMissingIdWarning = step.missingResourceId
            )
        }
    }

    private fun saveRecipe() {
        val s = _state.value
        viewModelScope.launch {
            val installedVersion = try {
                context.packageManager.getPackageInfo(s.targetPackage, 0).versionName ?: "unknown"
            } catch (e: Exception) { "unknown" }

            val recipe = Recipe(
                targetPackage = s.targetPackage,
                displayName = s.displayName,
                recordedVersion = installedVersion,
                recordedAt = System.currentTimeMillis(),
                schemaVersion = 1
            )
            val steps = capturedSteps.map { r ->
                RecipeStep(
                    targetPackage = s.targetPackage,
                    stepId = r.stepId,
                    resourceId = r.resourceId,
                    text = r.text,
                    className = r.className,
                    boundsRelX = r.boundsRelX, boundsRelY = r.boundsRelY,
                    boundsRelW = r.boundsRelW, boundsRelH = r.boundsRelH,
                    screenshotHashHex = r.screenshotHashHex,
                    recordedOnDensityDpi = r.recordedOnDensityDpi,
                    recordedOnScreenW = r.recordedOnScreenW,
                    recordedOnScreenH = r.recordedOnScreenH
                )
            }
            recipeRepo.saveRecipe(recipe, steps)
            _state.update { it.copy(isComplete = true) }
        }
    }
}
