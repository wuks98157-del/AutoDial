package com.autodial

data class UserSettings(
    val defaultHangupSeconds: Int = 25,
    val defaultCycles: Int = 10,
    val defaultTargetPackage: String = "com.b3networks.bizphone",
    val spamModeSafetyCap: Int = 9999,
    val interDigitDelayMs: Int = 400,
    val overlayX: Int = 0,
    val overlayY: Int = 200,
    val onboardingCompletedAt: Long = 0L,
    val verboseLoggingEnabled: Boolean = false
)
