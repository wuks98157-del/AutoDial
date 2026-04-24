package com.autodial.model

data class RecordedStep(
    val stepId: String,
    val resourceId: String?,
    val text: String?,
    val className: String?,
    val boundsRelX: Float,
    val boundsRelY: Float,
    val boundsRelW: Float,
    val boundsRelH: Float,
    val screenshotHashHex: String?,
    val recordedOnDensityDpi: Int,
    val recordedOnScreenW: Int,
    val recordedOnScreenH: Int,
    val missingResourceId: Boolean,  // true = warn in wizard UI
    // In RECORD_DIGITS, true means the tapped node exposed digit text and we
    // derived the DIGIT_X label from it (order-independent). False means we fell
    // back to tap order — user MUST tap 0,1,…,9 in sequence for labels to match.
    val digitAutoDetected: Boolean = false
)
