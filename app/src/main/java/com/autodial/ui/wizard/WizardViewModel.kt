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
import com.autodial.model.TargetApps
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
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
    // For RECORD_DIGITS: the set of digits whose taps we've captured so far. The
    // step is complete when this covers 0..9. Empty for single-capture steps.
    val capturedDigits: Set<Int> = emptySet(),
    // For RECORD_DIGITS: true when every captured digit was derived from node
    // text/contentDescription (order-independent). False means at least one tap
    // fell back to queue order — user MUST tap 0,1,…,9 in sequence for labels
    // to be correct. Null until the first digit tap in this step.
    val allDigitsAutoDetected: Boolean? = null,
    // Non-null if the just-captured step collides with an earlier step (same
    // resourceId AND overlapping bounds). BizPhone reuses `clearCallButton` for
    // both call-start and end-call, so tapping call-start mid-transition ends
    // up capturing the in-call end button, producing a duplicate. We refuse to
    // advance past HANG_UP until the user re-records the colliding step.
    val duplicateWarning: String? = null,
    // True while the wizard is programmatically clearing the dial pad after
    // CLEAR_DIGITS. Blocks Start Recording so its debounce doesn't kick in
    // mid-wipe and accidentally capture one of the automated taps.
    val isAutoWiping: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)

// Macro wizard step that expands into DIGIT_0..DIGIT_9 captures recorded without
// leaving the target app between digit taps.
const val RECORD_DIGITS = "RECORD_DIGITS"
val DIGIT_STEP_IDS = (0..9).map { "DIGIT_$it" }

// Returns the wizard steps for a given target package. For BizPhone we drop
// HANG_UP entirely because (a) the end-call button isn't reachable without
// starting a real call mid-wizard and (b) BizPhone's in-call end button shares
// a resourceId with the dial-pad call bar, so the runtime can find it directly
// by resourceId + visibility at hangup time — no recording needed.
fun wizardStepsFor(targetPackage: String): List<String> {
    val base = listOf("OPEN_DIAL_PAD", RECORD_DIGITS, "CLEAR_DIGITS", "PRESS_CALL")
    return when (TargetApps.hangupStrategy(targetPackage)) {
        is TargetApps.HangupStrategy.AutoHangupById -> base
        TargetApps.HangupStrategy.ReuseCallButton -> base
        TargetApps.HangupStrategy.RecordedStep -> base + "HANG_UP"
    }
}

fun stepInstruction(stepId: String, appName: String): String = when (stepId) {
    "OPEN_DIAL_PAD" -> "Open $appName and tap the keypad/dial pad icon."
    RECORD_DIGITS -> "On the dial pad, tap every digit from 0 through 9. AutoDial will try to read each key's label so you can tap in any order — but if your dial pad hides labels, the screen will switch to a strict 0→9 sequence instead. Watch the captured chips below."
    "CLEAR_DIGITS" -> "Tap the backspace/delete button (⌫) ONCE to record it. After you press Next, AutoDial will tap it ~15× to clear the dial pad for the next step — so you don't need to wipe it by hand."
    "PRESS_CALL" -> "IMPORTANT: turn ON airplane mode before tapping. On $appName the call button starts dialing so fast the tap can't be captured — airplane mode makes it fail instantly and stay on the dial pad so we can read the button. Type any digit, then tap the wide call bar at the bottom."
    "HANG_UP" -> "Place any test call in $appName, then — while ringing or connected — tap the end-call button."
    else -> ""
}

@HiltViewModel
class WizardViewModel @Inject constructor(
    private val recipeRepo: RecipeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(WizardUiState())
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    // Keyed by stepId so re-tapping a digit overwrites the previous recording
    // (useful when the user tapped the wrong key and wants to correct it).
    private val capturedSteps = mutableMapOf<String, RecordedStep>()

    private var wizardSteps: List<String> = emptyList()

    fun init(targetPackage: String) {
        val name = if (targetPackage == "com.b3networks.bizphone") "BizPhone" else "Mobile VOIP"
        wizardSteps = wizardStepsFor(targetPackage)
        _state.value = WizardUiState(
            targetPackage = targetPackage,
            displayName = name,
            currentStepId = wizardSteps[0],
            stepIndex = 0,
            totalSteps = wizardSteps.size,
            instruction = stepInstruction(wizardSteps[0], name)
        )
        viewModelScope.launch {
            AutoDialAccessibilityService.instance?.recordedSteps()?.collect { recorded ->
                onCaptured(recorded)
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

        if (s.currentStepId == RECORD_DIGITS) {
            service.startRecordingSequence(DIGIT_STEP_IDS, s.targetPackage, digitAutoMode = true)
            _state.update {
                it.copy(
                    isRecording = true, error = null,
                    capturedDigits = emptySet(),
                    allDigitsAutoDetected = null
                )
            }
        } else {
            service.startRecording(s.currentStepId, s.targetPackage)
            _state.update { it.copy(isRecording = true, error = null) }
        }
    }

    fun reRecord() {
        val s = _state.value
        if (s.currentStepId == RECORD_DIGITS) {
            // Drop partially-captured digits so we start clean
            capturedSteps.keys.removeAll(DIGIT_STEP_IDS.toSet())
        }
        _state.update {
            it.copy(
                lastCaptured = null,
                isRecording = false,
                capturedDigits = emptySet(),
                allDigitsAutoDetected = null,
                duplicateWarning = null
            )
        }
        startRecording()
    }

    fun confirmAndAdvance() {
        val s = _state.value
        if (s.currentStepId == RECORD_DIGITS) {
            // Digit captures were already appended inside onCaptured as they arrived.
        } else {
            val captured = s.lastCaptured ?: return
            capturedSteps[captured.stepId] = captured
        }
        // After CLEAR_DIGITS is confirmed, programmatically run it ~15× so the
        // dial pad is empty before the user lands on PRESS_CALL. Without this
        // the residue digits from RECORD_DIGITS (0123456789) are still in the
        // field and any pre-PRESS_CALL backspace tap by the user gets consumed
        // by the PRESS_CALL recorder.
        val wasClearDigits = s.currentStepId == "CLEAR_DIGITS"
        val nextIndex = s.stepIndex + 1
        if (nextIndex >= wizardSteps.size) {
            saveRecipe()
        } else {
            val nextStepId = wizardSteps[nextIndex]
            _state.update {
                it.copy(
                    currentStepId = nextStepId,
                    stepIndex = nextIndex,
                    instruction = stepInstruction(nextStepId, it.displayName),
                    lastCaptured = null,
                    isRecording = false,
                    showMissingIdWarning = false,
                    capturedDigits = emptySet(),
                    allDigitsAutoDetected = null,
                    duplicateWarning = null
                )
            }
            if (wasClearDigits) autoWipeDialPad(s.targetPackage)
        }
    }

    private fun autoWipeDialPad(targetPackage: String) {
        val clearRecorded = capturedSteps["CLEAR_DIGITS"] ?: return
        val service = AutoDialAccessibilityService.instance ?: return
        val step = RecipeStep(
            targetPackage = targetPackage,
            stepId = "CLEAR_DIGITS",
            resourceId = clearRecorded.resourceId,
            text = clearRecorded.text,
            className = clearRecorded.className,
            boundsRelX = clearRecorded.boundsRelX, boundsRelY = clearRecorded.boundsRelY,
            boundsRelW = clearRecorded.boundsRelW, boundsRelH = clearRecorded.boundsRelH,
            screenshotHashHex = clearRecorded.screenshotHashHex,
            recordedOnDensityDpi = clearRecorded.recordedOnDensityDpi,
            recordedOnScreenW = clearRecorded.recordedOnScreenW,
            recordedOnScreenH = clearRecorded.recordedOnScreenH
        )
        _state.update { it.copy(isAutoWiping = true) }
        viewModelScope.launch {
            // Bring BizPhone to front so the taps land on its dial pad, not AutoDial.
            val intent = context.packageManager
                .getLaunchIntentForPackage(targetPackage)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
            delay(500L)  // let the window swap in
            repeat(15) {
                service.executeStep(step)
                delay(120L)
            }
            _state.update { it.copy(isAutoWiping = false) }
        }
    }

    private fun onCaptured(step: RecordedStep) {
        val s = _state.value
        if (s.currentStepId == RECORD_DIGITS) {
            if (step.stepId !in DIGIT_STEP_IDS) return
            capturedSteps[step.stepId] = step
            val digit = step.stepId.substringAfter("DIGIT_").toIntOrNull()
            val updated = if (digit != null) s.capturedDigits + digit else s.capturedDigits
            val doneAll = updated.size >= DIGIT_STEP_IDS.size
            val autoOk = (s.allDigitsAutoDetected ?: true) && step.digitAutoDetected
            _state.update {
                it.copy(
                    capturedDigits = updated,
                    allDigitsAutoDetected = autoOk,
                    isRecording = !doneAll,
                    lastCaptured = if (doneAll) step else null,
                    showMissingIdWarning = it.showMissingIdWarning || step.missingResourceId
                )
            }
            if (doneAll) AutoDialAccessibilityService.instance?.stopRecording()
        } else {
            if (step.stepId != s.currentStepId) return
            AutoDialAccessibilityService.instance?.stopRecording()
            val dup = findDuplicateWarning(step)
            _state.update {
                it.copy(
                    isRecording = false,
                    lastCaptured = step,
                    showMissingIdWarning = step.missingResourceId,
                    duplicateWarning = dup
                )
            }
        }
    }

    // Detect the "same-button-captured-twice" failure mode. Primary case:
    // BizPhone's call-start and end-call share resourceId `clearCallButton`
    // at identical bounds — if the user tapped call-start and it transitioned
    // too fast to capture, the wizard silently captures the in-call end-call
    // button for PRESS_CALL. At run time that ID doesn't exist on the dial pad,
    // so the step falls through to a coord tap on the "0" digit.
    private fun findDuplicateWarning(step: RecordedStep): String? {
        val rid = step.resourceId ?: return null  // no ID → can't detect reliably
        val match = capturedSteps.values.firstOrNull { prior ->
            prior.stepId != step.stepId &&
            prior.resourceId == rid &&
            kotlin.math.abs(prior.boundsRelX - step.boundsRelX) < 0.02f &&
            kotlin.math.abs(prior.boundsRelY - step.boundsRelY) < 0.02f
        } ?: return null
        return "This capture looks identical to ${match.stepId} (same id, same position). " +
               "On BizPhone this usually means the call button tap was too fast and we " +
               "caught the in-call end button instead. Re-record with AIRPLANE MODE ON " +
               "so BizPhone can't transition away mid-tap."
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
            val steps = capturedSteps.values.map { r ->
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
