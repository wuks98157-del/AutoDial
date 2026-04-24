package com.autodial.wizard

import android.content.Context
import android.widget.Toast
import com.autodial.accessibility.AutoDialAccessibilityService
import com.autodial.model.isActive
import com.autodial.service.RunForegroundService

// Central entry point for starting a wizard from any UI surface (Dialer,
// Onboarding, Settings). Shows a Toast and bails on any precondition
// failure so the user gets feedback instead of a dead button or a
// silent overlay collision on top of a running run.
object WizardLauncher {
    fun launch(context: Context, targetPackage: String) {
        val svc = AutoDialAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(
                context,
                "Enable AutoDial accessibility service first",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (RunForegroundService.publicState.value.isActive()) {
            Toast.makeText(
                context,
                "Stop the active run before recording a recipe",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        svc.beginWizard(targetPackage)
    }
}
