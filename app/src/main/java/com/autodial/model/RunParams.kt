package com.autodial.model

data class RunParams(
    val number: String,
    val targetPackage: String,
    val plannedCycles: Int,        // 0 = spam mode
    val hangupSeconds: Int,
    val spamModeSafetyCap: Int,
    val interDigitDelayMs: Long
)
