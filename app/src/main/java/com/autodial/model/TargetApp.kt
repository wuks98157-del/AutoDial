package com.autodial.model

object TargetApps {
    const val BIZPHONE = "com.b3networks.bizphone"
    const val MOBILE_VOIP = "finarea.MobileVoip"

    fun retainsNumberAfterHangup(targetPackage: String): Boolean =
        targetPackage == BIZPHONE

    fun clearDigitsLongPressMs(targetPackage: String): Long? = when (targetPackage) {
        BIZPHONE -> 2500L
        else -> null
    }

    // How the runtime should end a call for this target.
    sealed class HangupStrategy {
        // Tap the visible+clickable node with this resourceId (BizPhone).
        data class AutoHangupById(val resourceId: String) : HangupStrategy()
        // Re-execute the recorded PRESS_CALL step — the end-call button sits
        // at the same on-screen location as the call button (Mobile VOIP).
        object ReuseCallButton : HangupStrategy()
        // Default — execute the recorded HANG_UP step.
        object RecordedStep : HangupStrategy()
    }

    fun hangupStrategy(targetPackage: String): HangupStrategy = when (targetPackage) {
        BIZPHONE    -> HangupStrategy.AutoHangupById("com.b3networks.bizphone:id/clearCallButton")
        MOBILE_VOIP -> HangupStrategy.ReuseCallButton
        else        -> HangupStrategy.RecordedStep
    }

    // Whether the target app returns to its own dial pad activity when a call
    // ends from the network/recipient side (invalid number, busy, recipient
    // hangup). When true, the run-time polls for the recorded PRESS_CALL
    // node's reappearance during InCall — its visibility means the call has
    // already ended and the recorded HANG_UP tap will fail (the in-call view
    // is gone). We synthesize a HANG_UP success and advance to the next
    // cycle instead of letting auto-hangup time out and Fail the whole run.
    //
    // Mobile VOIP qualifies even though it uses HangupStrategy.ReuseCallButton:
    // that strategy works via UiPlayer's Tier-3 *coordinate* fallback, not by
    // resourceId — the in-call hangup view has a different id from the dial-pad
    // call button. So PRESS_CALL's id is genuinely hidden during an active
    // Mobile VOIP call and reappears when the call ends.
    fun detectsCallEndByDialPad(targetPackage: String): Boolean = when (targetPackage) {
        BIZPHONE, MOBILE_VOIP -> true
        else                  -> false
    }
}
