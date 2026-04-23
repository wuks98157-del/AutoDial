package com.autodial.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "recipe_steps",
    primaryKeys = ["targetPackage", "stepId"],
    foreignKeys = [ForeignKey(
        entity = Recipe::class,
        parentColumns = ["targetPackage"],
        childColumns = ["targetPackage"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RecipeStep(
    val targetPackage: String,
    val stepId: String,          // "OPEN_DIAL_PAD" | "DIGIT_0".."DIGIT_9" | "PRESS_CALL" | "HANG_UP_CONNECTED" | "HANG_UP_RINGING" | "RETURN_TO_DIAL_PAD"
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
    val recordedOnScreenH: Int
)
