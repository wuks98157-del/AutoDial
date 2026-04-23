package com.autodial.oem

import android.content.ComponentName
import android.content.Intent

data class OemSetting(
    val id: String,
    val displayName: String,
    val description: String,
    val canVerify: Boolean,
    val verify: (() -> Boolean)?,
    val deepLinkIntent: Intent?
)

class OemCompatibilityHelper private constructor(
    val oem: Oem
) {
    enum class Oem { XIAOMI, OPPO, VIVO, SAMSUNG, GENERIC }

    companion object {
        fun forManufacturer(manufacturer: String): OemCompatibilityHelper {
            val oem = when (manufacturer.lowercase()) {
                "xiaomi", "redmi", "poco" -> Oem.XIAOMI
                "oppo", "realme" -> Oem.OPPO
                "vivo", "iqoo" -> Oem.VIVO
                "samsung" -> Oem.SAMSUNG
                else -> Oem.GENERIC
            }
            return OemCompatibilityHelper(oem)
        }
    }

    fun getRequiredSettings(): List<OemSetting> = when (oem) {
        Oem.XIAOMI -> xiaomiSettings()
        Oem.OPPO -> oppoSettings()
        Oem.VIVO -> vivoSettings()
        Oem.SAMSUNG -> samsungSettings()
        Oem.GENERIC -> emptyList()
    }

    private fun xiaomiSettings() = listOf(
        OemSetting(
            id = "xiaomi_autostart",
            displayName = "Autostart",
            description = "Security → Permissions → Autostart → enable AutoDial",
            canVerify = false, verify = null,
            deepLinkIntent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "xiaomi_battery",
            displayName = "Battery Saver — No Restrictions",
            description = "Settings → Battery → App battery saver → AutoDial → No restrictions",
            canVerify = false, verify = null,
            deepLinkIntent = Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "xiaomi_lock_screen",
            displayName = "Show on Lock Screen",
            description = "Settings → Apps → AutoDial → Other permissions → Show on Lock screen → allow",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "xiaomi_lock_recents",
            displayName = "Lock in Recents",
            description = "Open Recents, pull down on the AutoDial card (or tap the lock icon on the card)",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )

    private fun oppoSettings() = listOf(
        OemSetting(
            id = "oppo_auto_launch",
            displayName = "Auto Launch",
            description = "Settings → Apps → Auto launch → enable AutoDial",
            canVerify = false, verify = null,
            deepLinkIntent = Intent().apply {
                action = "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "oppo_battery",
            displayName = "App Power Management",
            description = "Settings → Battery → Power saving → App power management → AutoDial → enable all 3 toggles",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "oppo_lock_recents",
            displayName = "Lock in Recents",
            description = "Open Recents, tap the lock icon on the AutoDial card",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )

    private fun vivoSettings() = listOf(
        OemSetting(
            id = "vivo_autostart",
            displayName = "Autostart",
            description = "Settings → More settings → Permission management → Autostart → AutoDial → enable",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "vivo_battery",
            displayName = "Background Power Consumption",
            description = "Settings → Battery → Background power consumption management → AutoDial → allow",
            canVerify = false, verify = null, deepLinkIntent = null
        ),
        OemSetting(
            id = "vivo_lock_recents",
            displayName = "Lock in Recents",
            description = "Open Recents, tap the lock icon on the AutoDial card",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )

    private fun samsungSettings() = listOf(
        OemSetting(
            id = "samsung_battery",
            displayName = "Battery — Unrestricted",
            description = "Settings → Apps → AutoDial → Battery → Unrestricted",
            canVerify = false, verify = null,
            deepLinkIntent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ),
        OemSetting(
            id = "samsung_never_sleep",
            displayName = "Never Sleeping Apps",
            description = "Settings → Device care → Battery → Background usage limits → Never sleeping apps → add AutoDial",
            canVerify = false, verify = null, deepLinkIntent = null
        )
    )
}
