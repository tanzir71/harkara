package io.github.tanzir71.harkara.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.location.LocationCardData
import io.github.tanzir71.harkara.core.location.TripPlan
import io.github.tanzir71.harkara.core.map.LabelLanguage
import io.github.tanzir71.harkara.core.map.MapLayer
import io.github.tanzir71.harkara.core.map.MapPack
import io.github.tanzir71.harkara.core.map.MapsManifest
import io.github.tanzir71.harkara.core.map.MapsRelease
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.sms.GatewayConfig
import io.github.tanzir71.harkara.core.sms.GatewayMember
import io.github.tanzir71.harkara.data.HarkaraDatabase
import io.github.tanzir71.harkara.map.MapStyleSpec
import io.github.tanzir71.harkara.map.MapViewState
import io.github.tanzir71.harkara.map.PackState
import io.github.tanzir71.harkara.map.mapColors
import io.github.tanzir71.harkara.places.OutgoingShare
import io.github.tanzir71.harkara.search.LocalSearch
import io.github.tanzir71.harkara.settings.MeshMode
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.sms.SmsComposer
import io.github.tanzir71.harkara.ui.chats.ChatThreadScreen
import io.github.tanzir71.harkara.ui.chats.ChatsListScreen
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.map.CheckInSheetContent
import io.github.tanzir71.harkara.ui.map.CompassScreen
import io.github.tanzir71.harkara.ui.map.CompassTarget
import io.github.tanzir71.harkara.ui.map.FakeMapSurface
import io.github.tanzir71.harkara.ui.map.LiveShareSheetContent
import io.github.tanzir71.harkara.ui.map.LocationCardSheetContent
import io.github.tanzir71.harkara.ui.map.MapActionsCard
import io.github.tanzir71.harkara.ui.map.MapPacksScreen
import io.github.tanzir71.harkara.ui.map.MapSamples
import io.github.tanzir71.harkara.ui.map.MyPinsScreen
import io.github.tanzir71.harkara.ui.map.NearbyMapContent
import io.github.tanzir71.harkara.ui.map.PinEditorSheetContent
import io.github.tanzir71.harkara.ui.screens.BackupSheetContent
import io.github.tanzir71.harkara.ui.screens.BackupStep
import io.github.tanzir71.harkara.ui.screens.ConnectivitySheetContent
import io.github.tanzir71.harkara.ui.screens.EventQrSheetContent
import io.github.tanzir71.harkara.ui.screens.GreyAreaSheetContent
import io.github.tanzir71.harkara.ui.screens.HandoffEntrySheetContent
import io.github.tanzir71.harkara.ui.screens.LanToolsScreen
import io.github.tanzir71.harkara.ui.screens.OnboardingScreen
import io.github.tanzir71.harkara.ui.screens.PanicWipeSheetContent
import io.github.tanzir71.harkara.ui.screens.PasteLinkSheetContent
import io.github.tanzir71.harkara.ui.screens.RestrictedSettingsScreen
import io.github.tanzir71.harkara.ui.screens.RestoreSheetContent
import io.github.tanzir71.harkara.ui.screens.SendSmsSheetContent
import io.github.tanzir71.harkara.ui.screens.SmsGatewayScreen
import io.github.tanzir71.harkara.ui.screens.SosSheetContent
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Complements AccessibilityTest with previously unaudited flows, Bangla and larger text.
 * UI-only fixtures avoid application startup, native maps, sensor flows and live LAN sessions.
 * These are semantics/touch-bound checks, not proof of TalkBack speech or unclipped text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "bn-w360dp-h800dp-mdpi", application = Application::class)
class AccessibilityCoverageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val trip = TripPlan("trip", "Banani", 1_795_590_000, listOf("ab".repeat(32)), 1_795_586_400)

    private fun check(beforeCheck: () -> Unit = {}, content: @Composable () -> Unit) {
        val night = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                HarkaraTheme(night = night.value) { Column(Modifier.fillMaxSize()) { content() } }
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { night.value = dark }
            compose.waitForIdle()
            beforeCheck()
            compose.waitForIdle()
            val problems = mutableListOf<String>()
            var checked = 0
            fun labels(node: SemanticsNode): List<String> =
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text) +
                    node.children.flatMap(::labels)
            fun visit(node: SemanticsNode) {
                val bounds = node.boundsInRoot
                // Clipped/off-screen elements need a scroll audit, not a false size failure.
                val visible = node.size.width > 0 && node.size.height > 0 &&
                    abs(bounds.width - node.size.width) < 0.5f && abs(bounds.height - node.size.height) < 0.5f
                val interactive = node.config.getOrNull(SemanticsActions.OnClick) != null ||
                    node.config.getOrNull(SemanticsActions.OnLongClick) != null ||
                    node.config.getOrNull(SemanticsActions.SetText) != null ||
                    node.config.getOrNull(SemanticsProperties.Role) == Role.Button
                if (visible && interactive) {
                    checked++
                    val label = labels(node).joinToString(" ").trim()
                    if (label.isBlank()) problems += "unlabelled action at $bounds"
                    val touch = node.touchBoundsInRoot
                    val minimum = with(compose.density) { 48.dp.toPx() }
                    if (touch.width + 0.5f < minimum || touch.height + 0.5f < minimum) {
                        problems += "target below 48 dp: '$label' at $touch"
                    }
                }
                node.children.forEach(::visit)
            }
            visit(compose.onRoot(useUnmergedTree = false).fetchSemanticsNode())
            assertTrue("No visible actions were audited", checked > 0)
            assertTrue("night=$dark\n${problems.joinToString("\n")}", problems.isEmpty())
        }
    }

    @Test
    fun banglaTabs() {
        check { TabBar(HarkaraTab.Nearby, {}) }
        compose.onNodeWithText("\u0995\u09be\u099b\u09c7").assertExists()
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp-mdpi")
    fun englishTabs() {
        check { TabBar(HarkaraTab.Nearby, {}) }
        compose.onNodeWithText("Nearby").assertExists()
    }

    @Test
    fun onboarding() = check { OnboardingScreen(listOf("Dhanmondi", "Banani"), {}, {}) }

    @Test
    fun chatsList() = check { ChatsListScreen(ChatSampleData.rooms, ChatSampleData.conversations, SampleData.online) }

    @Test
    fun chatThread() = check {
        ChatThreadScreen(ChatSampleData.header, ChatSampleData.thread, ChatSampleData.meshSix, hint = "Message")
    }

    @Test
    fun backupPassword() = check { BackupSheetContent(BackupStep.Password, "", "", {}, {}, {}, {}, {}) }

    @Test
    fun restorePassword() = check { RestoreSheetContent("", "", false, null, {}, {}, {}, {}) }

    @Test
    fun connectivity() = check { ConnectivitySheetContent(SampleData.online, MeshMode.SAVER, {}) }

    @Test
    fun taskHandoff() = check { HandoffEntrySheetContent({}, false) }

    @Test
    fun qrImport() = check { io.github.tanzir71.harkara.ui.screens.PasteLinkSheetContent({}) }

    @Test
    fun sos() = check { SosSheetContent(0, {}, { _, _ -> }) }

    @Test
    fun panicWipe() = check { PanicWipeSheetContent({}, {}) }

    @Composable
    private fun MapAudit(surface: FakeMapSurface?) {
        NearbyMapContent(
            modeSwitch = {}, surface = surface,
            style = if (surface != null) MapStyleSpec("/fake.pmtiles", 15, HarkaraTheme.isNight, HarkaraTheme.colors.mapColors(), LabelLanguage.BANGLA) else null,
            mapState = remember { MapViewState(MapSamples.camera) },
            markers = MapSampleData.markers, enabled = MapLayer.DEFAULT, onToggleLayer = {},
            user = MapSampleData.user, origin = MapSampleData.user,
            selectedId = null, onSelect = {}, personTag = { "Rafi" },
            bottomCard = { MapActionsCard({}, {}, {}, {}) }, onCompass = {}, onLocate = {},
        )
    }

    @Test
    fun nearbyMapControls() = check { MapAudit(remember { FakeMapSurface() }) }

    @Test
    fun nearbyLiteControls() = check { MapAudit(null) }

    @Test
    fun myPins() = check {
        MyPinsScreen(MapSampleData.pinViews, listOf(MapSampleData.overdue), trip, { "Rafi" }, {}, {}, {}, {})
    }

    @Test
    fun privatePinEditor() = check {
        PinEditorSheetContent(false, { _, _, _, _, _, _ -> }, initialTitle = "Water point")
    }

    @Test
    fun sharedPinEditor() = check {
        PinEditorSheetContent(false, { _, _, _, _, _, _ -> }, initialTitle = "Water point", initialVisibility = PinVisibility.SHARED)
    }

    @Test
    fun publicPinEditor() = check {
        PinEditorSheetContent(true, { _, _, _, _, _, _ -> }, initialTitle = "Water point", initialVisibility = PinVisibility.PUBLIC, onDelete = {})
    }

    @Test
    fun locationCard() = check {
        LocationCardSheetContent({ precision -> LocationCardData.make(MapSampleData.user, precision, "Dhanmondi") }, {}, {})
    }

    @Test
    fun liveShareSetup() = check { LiveShareSheetContent(null, { _, _ -> }, {}) }

    @Test
    fun liveShareRunning() = check {
        LiveShareSheetContent(OutgoingShare("share", listOf("ab".repeat(32)), 1_795_590_000, 1, null, 2), { _, _ -> }, {})
    }

    @Test
    fun safeCheckIn() = check { CheckInSheetContent("Home", null, true, { _, _, _ -> }, { _, _, _ -> }, {}) }

    @Test
    fun tripPlanSetup() = check(
        beforeCheck = { compose.onNodeWithText(context.getString(R.string.trip_tab)).performClick() },
    ) { CheckInSheetContent("Home", null, true, { _, _, _ -> }, { _, _, _ -> }, {}) }

    @Test
    fun cancelActiveTrip() = check(
        beforeCheck = { compose.onNodeWithText(context.getString(R.string.trip_tab)).performClick() },
    ) { CheckInSheetContent("Home", trip, true, { _, _, _ -> }, { _, _, _ -> }, {}) }

    @Test
    fun compass() = check {
        val target = CompassTarget("pin", "Water point", MapSamples.at(203f, 250f))
        CompassScreen(20.0, true, MapSampleData.user, listOf(target), target, {}, {})
    }

    @Test
    fun mapPackChoices() = check {
        val pack = MapPack("dhaka-z15.pmtiles", 15, 22_789_276, "ab".repeat(32))
        MapPacksScreen(
            PackState.Available(null, MapsRelease("maps-202611", emptyMap()), MapsManifest(packs = listOf(pack))),
            null, pack, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun smsSendAndDirectNumber() = check(
        beforeCheck = { compose.onNodeWithText(context.getString(R.string.sms_direct_send)).performScrollTo() },
    ) {
        SendSmsSheetContent(
            SmsComposer.Draft.Ready("Harkara SOS\nHK1:fixture", 1), {}, {},
            directOn = true, gateway = GatewayCard("Rafi", "01711000111"),
        )
    }

    @Test
    fun smsIneligibleQrFallback() = check { SendSmsSheetContent(SmsComposer.Draft.NotEligible, {}, {}) }

    @Test
    fun multipartQr() = check { EventQrSheetContent(listOf("hkq1:fixture:1:2:first", "hkq1:fixture:2:2:second"), {}) }

    @Test
    fun pasteLink() = check { PasteLinkSheetContent({}) }

    @Test
    fun directSmsConsent() = check { GreyAreaSheetContent(Module.SMS_DIRECT, {}, {}) }

    @Test
    fun smsGatewayConsent() = check { GreyAreaSheetContent(Module.SMS_GATEWAY, {}, {}) }

    @Test
    fun restrictedSmsSettings() = check { RestrictedSettingsScreen({}, {}) }

    @Test
    fun smsGatewayConfiguration() = check {
        SmsGatewayScreen(GatewayConfig("Hall 3", "01711000999", listOf(GatewayMember("Rafi", "01711000111"))), 12, {}, {}, {})
    }

    @Test
    fun areaPicker() = check { AreaPickerSheet(listOf("Dhanmondi", "Mirpur", "Banani"), {}, {}) }

    @Test
    fun localSearch() {
        val db = HarkaraDatabase.inMemory(context)
        val search = LocalSearch(db)
        val shown = mutableStateOf(true)
        try {
            check { if (shown.value) LocalSearchSheet(search, {}) }
        } finally {
            // Dispose the sheet's delayed search coroutine before closing its database.
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    @Test
    fun lanFileDropIdle() = check { LanToolsScreen(false, PlatformActions.None, {}) }

    @Test
    fun lanFileDropFileAction() = check(
        beforeCheck = { compose.onNodeWithText(context.getString(R.string.lan_tools_choose)).performScrollTo() },
    ) { LanToolsScreen(false, PlatformActions.None, {}) }

    @Test
    fun lanPushToTalkIdle() = check { LanToolsScreen(true, PlatformActions.None, {}) }

    @Test
    fun lanPushToTalkHoldControl() = check(
        beforeCheck = { compose.onNodeWithText(context.getString(R.string.lan_tools_hold)).performScrollTo() },
    ) { LanToolsScreen(true, PlatformActions.None, {}) }
}
