package com.autodial.model

object TargetApps {
    const val BIZPHONE = "com.b3networks.bizphone"
    const val MOBILE_VOIP = "finarea.MobileVoip"

    // True when the target app leaves the previously-typed number in the dial pad
    // after a hang-up, so subsequent cycles only need to press Call again.
    fun retainsNumberAfterHangup(targetPackage: String): Boolean =
        targetPackage == BIZPHONE

    // When non-null, HANG_UP can be skipped from the wizard: at runtime we just
    // look up this resourceId in the live accessibility tree and tap whichever
    // visible+clickable node carries it. BizPhone reuses the same ID on both the
    // dial pad's call bar and the in-call end-call circle — but the end-call
    // circle is only *visible* while a call is active, so matching on visibility
    // disambiguates without requiring a separate recording (which is borderline
    // impossible to capture cleanly during wizard setup).
    fun autoHangupResourceId(targetPackage: String): String? = when (targetPackage) {
        BIZPHONE -> "com.b3networks.bizphone:id/clearCallButton"
        else -> null
    }

    // When non-null, CLEAR_DIGITS is executed as a single press-and-hold of
    // this duration instead of N discrete taps. BizPhone's backspace clears
    // all digits when held, so one 2.5s press does the work of 15 taps and
    // avoids looking like a scroll/flick to the target app. Apps without
    // this behavior fall back to the tap loop.
    fun clearDigitsLongPressMs(targetPackage: String): Long? = when (targetPackage) {
        BIZPHONE -> 2500L
        else -> null
    }
}
