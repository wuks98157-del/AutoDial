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
    val missingResourceId: Boolean  // true = warn in wizard UI
)
