package com.autodial.oem

import android.content.Intent
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OemCompatibilityHelperTest {

    @Test
    fun xiaomiManufacturerReturnsXiaomiSettings() {
        val helper = OemCompatibilityHelper.forManufacturer("Xiaomi")
        assertEquals(OemCompatibilityHelper.Oem.XIAOMI, helper.oem)
        val settings = helper.getRequiredSettings()
        assertTrue(settings.isNotEmpty())
        assertTrue(settings.any { it.id == "xiaomi_autostart" })
        assertTrue(settings.any { it.id == "xiaomi_battery" })
    }

    @Test
    fun redmiMapsToXiaomi() {
        assertEquals(OemCompatibilityHelper.Oem.XIAOMI,
            OemCompatibilityHelper.forManufacturer("Redmi").oem)
    }

    @Test
    fun oppoManufacturerReturnsOppoSettings() {
        val helper = OemCompatibilityHelper.forManufacturer("OPPO")
        assertEquals(OemCompatibilityHelper.Oem.OPPO, helper.oem)
        assertTrue(helper.getRequiredSettings().any { it.id == "oppo_auto_launch" })
    }

    @Test
    fun vivoManufacturerReturnsVivoSettings() {
        val helper = OemCompatibilityHelper.forManufacturer("vivo")
        assertEquals(OemCompatibilityHelper.Oem.VIVO, helper.oem)
        assertTrue(helper.getRequiredSettings().any { it.id == "vivo_autostart" })
    }

    @Test
    fun unknownManufacturerReturnsGeneric() {
        val helper = OemCompatibilityHelper.forManufacturer("Google")
        assertEquals(OemCompatibilityHelper.Oem.GENERIC, helper.oem)
        assertTrue(helper.getRequiredSettings().isEmpty())
    }

    @Test
    fun xiaomiAutostartHasDeepLink() {
        val helper = OemCompatibilityHelper.forManufacturer("Xiaomi")
        val autostart = helper.getRequiredSettings().first { it.id == "xiaomi_autostart" }
        assertNotNull(autostart.deepLinkIntent)
    }
}
