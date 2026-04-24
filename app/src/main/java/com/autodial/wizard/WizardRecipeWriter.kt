package com.autodial.wizard

import android.content.Context
import com.autodial.data.db.entity.Recipe
import com.autodial.data.db.entity.RecipeStep
import com.autodial.data.repository.RecipeRepository
import com.autodial.model.RecordedStep
import com.autodial.model.TargetApps

class WizardRecipeWriter(
    private val context: Context,
    private val recipeRepo: RecipeRepository
) {
    suspend fun save(targetPackage: String, captured: Map<String, RecordedStep>) {
        val displayName = when (targetPackage) {
            TargetApps.BIZPHONE -> "BizPhone"
            TargetApps.MOBILE_VOIP -> "Mobile VOIP"
            else -> targetPackage
        }
        val installedVersion = try {
            context.packageManager.getPackageInfo(targetPackage, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        val recipe = Recipe(
            targetPackage = targetPackage,
            displayName = displayName,
            recordedVersion = installedVersion,
            recordedAt = System.currentTimeMillis(),
            schemaVersion = 1
        )
        val steps = captured.values.map { r ->
            RecipeStep(
                targetPackage = targetPackage,
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
    }
}
