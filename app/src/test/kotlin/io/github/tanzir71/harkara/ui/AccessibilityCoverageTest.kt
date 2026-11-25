package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.settings.MeshMode
import io.github.tanzir71.harkara.ui.chats.ChatThreadScreen
import io.github.tanzir71.harkara.ui.chats.ChatsListScreen
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.screens.BackupSheetContent
import io.github.tanzir71.harkara.ui.screens.BackupStep
import io.github.tanzir71.harkara.ui.screens.ConnectivitySheetContent
import io.github.tanzir71.harkara.ui.screens.HandoffEntrySheetContent
import io.github.tanzir71.harkara.ui.screens.OnboardingScreen
import io.github.tanzir71.harkara.ui.screens.PanicWipeSheetContent
import io.github.tanzir71.harkara.ui.screens.RestoreSheetContent
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
 * These are semantics/touch-bound checks, not proof of TalkBack speech or unclipped text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "bn-w360dp-h800dp-mdpi")
class AccessibilityCoverageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check(content: @Composable () -> Unit) {
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
                    node.config.getOrNull(SemanticsActions.SetText) != null
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
    fun sos() = check { SosSheetContent(0, {}, { _, _ -> }) }

    @Test
    fun panicWipe() = check { PanicWipeSheetContent({}, {}) }
}
