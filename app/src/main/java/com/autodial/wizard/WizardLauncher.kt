package com.autodial.wizard

import android.content.Context
import android.widget.Toast
import com.autodial.accessibility.AutoDialAccessibilityService

// Central entry point for starting a wizard from any UI surface (Dialer,
// Onboarding, Settings). Shows a Toast if the accessibility service isn't
// bound so the user gets feedback instead of a dead button.
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
        svc.beginWizard(targetPackage)
    }
}
