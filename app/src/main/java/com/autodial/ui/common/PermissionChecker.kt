package com.autodial.ui.common

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.contains(context.packageName, ignoreCase = true) }
    }

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(context)

    fun isNotificationsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun isBatteryOptimizationExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isTargetInstalled(packageName: String): Boolean =
        try { context.packageManager.getPackageInfo(packageName, 0); true }
        catch (e: PackageManager.NameNotFoundException) { false }

    data class PermissionStatus(
        val accessibility: Boolean,
        val overlay: Boolean,
        val notifications: Boolean,
        val batteryExempt: Boolean,
        val bizPhoneInstalled: Boolean,
        val mobileVoipInstalled: Boolean
    ) {
        val allGranted: Boolean get() =
            accessibility && overlay && notifications && batteryExempt
    }

    fun checkAll(): PermissionStatus = PermissionStatus(
        accessibility = isAccessibilityEnabled(),
        overlay = isOverlayGranted(),
        notifications = isNotificationsGranted(),
        batteryExempt = isBatteryOptimizationExempt(),
        bizPhoneInstalled = isTargetInstalled("com.b3networks.bizphone"),
        mobileVoipInstalled = isTargetInstalled("finarea.MobileVoip")
    )
}
