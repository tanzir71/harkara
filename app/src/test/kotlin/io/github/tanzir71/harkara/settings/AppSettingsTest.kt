package io.github.tanzir71.harkara.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `every module from the handover is listed with its default`() {
        val expectedDefaults = mapOf(
            "offline_map" to false, "no_map_lite" to false, "saved_pins" to true, "location_card" to true,
            "live_share" to false, "meetup_arrow" to true, "check_ins" to true, "trip_plan" to false,
            "compass" to true, "qibla_prayer" to false, "torch_tools" to true, "qr" to true, "handbook" to true,
            "mesh" to false, "node" to false, "push_to_talk" to false, "radar" to false, "file_drop" to false,
            "lite_mode" to false, "sms_direct" to false, "sms_gateway" to false,
        )
        assertEquals(expectedDefaults, Module.entries.associate { it.key to it.defaultOn })
        val state = AppSettings(context).state.value
        Module.entries.forEach { assertEquals(it.defaultOn, state.isOn(it)) }
    }

    @Test
    fun `grey area modules are off by default and flagged`() {
        val state = AppSettings(context).state.value
        val grey = Module.entries.filter { it.greyArea }
        assertEquals(setOf(Module.SMS_DIRECT, Module.SMS_GATEWAY), grey.toSet())
        grey.forEach { assertFalse("${it.key} must default off", state.isOn(it)) }
    }

    @Test
    fun `module switches persist across instances`() {
        AppSettings(context).setModule(Module.MESH, true)
        AppSettings(context).setModule(Module.HANDBOOK, false)

        val reloaded = AppSettings(context).state.value
        assertTrue(reloaded.isOn(Module.MESH))
        assertFalse(reloaded.isOn(Module.HANDBOOK))
    }

    @Test
    fun `onboarding stores the area and language override persists`() {
        val settings = AppSettings(context)
        assertFalse(settings.state.value.onboarded)

        settings.completeOnboarding("Dhanmondi", auto = false)
        settings.setLanguage(AppLanguage.BN)

        val reloaded = AppSettings(context).state.value
        assertTrue(reloaded.onboarded)
        assertEquals("Dhanmondi", reloaded.areaName)
        assertFalse(reloaded.areaAuto)
        assertEquals(AppLanguage.BN, AppSettings.readLanguage(context))
    }

    @Test
    fun `clear resets everything to defaults`() {
        val settings = AppSettings(context)
        settings.completeOnboarding(null, auto = true)
        settings.setModule(Module.MESH, true)

        settings.clear()

        assertFalse(settings.state.value.onboarded)
        assertNull(settings.state.value.areaName)
        assertFalse(settings.state.value.isOn(Module.MESH))
    }
}
