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
}
