package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.settings.SettingsState
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.screens.KhataRow
import io.github.tanzir71.harkara.ui.screens.KhataRowStatus
import io.github.tanzir71.harkara.ui.screens.KhataScreen
import io.github.tanzir71.harkara.ui.screens.MeScreen
import io.github.tanzir71.harkara.ui.screens.ModulesScreen
import io.github.tanzir71.harkara.ui.screens.NearbyScreen
import io.github.tanzir71.harkara.ui.screens.SettingsScreen
import io.github.tanzir71.harkara.ui.theme.HarkaraColors
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M12 accessibility pass: every tappable element has a label for TalkBack and a touch target of at least
 * 48 dp (Compose extends small hit areas to the minimum), and token text colours meet contrast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        compose.setContent { HarkaraTheme { Column(Modifier.fillMaxSize()) { content() } } }
        val density = compose.density
        val problems = mutableListOf<String>()
        fun visit(node: SemanticsNode) {
            // Only judge elements fully on screen (scrolled-away or clipped nodes report shrunken bounds).
            val onScreen = node.boundsInRoot.width.toInt() == node.size.width && node.boundsInRoot.height.toInt() == node.size.height && node.size.width > 0
            val clickable = onScreen && node.config.getOrNull(SemanticsActions.OnClick) != null
            if (clickable) {
                val merged = node.config
                val label = (merged.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + merged.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ")
                val childText = node.children.flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                if (label.isBlank() && childText.isEmpty()) problems += "unlabelled clickable at ${node.boundsInRoot}"
                val touch = node.touchBoundsInRoot
                val w = with(density) { touch.width.toDp() }
                val h = with(density) { touch.height.toDp() }
                if (w < 47.dp || h < 47.dp) problems += "small target ($w x $h) for '${label.ifBlank { childText.joinToString() }}'"
            }
            node.children.forEach(::visit)
        }
        visit(compose.onRoot(useUnmergedTree = false).fetchSemanticsNode())
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `nearby feed`() = check { NearbyScreen("Dhanmondi 27", true, SampleData.online, SampleData.posts, Modifier.weight(1f)); TabBar(HarkaraTab.Nearby, {}) }

    @Test
    fun `me and modules`() {
        check { MeScreen(SettingsState(onboarded = true), SampleData.online, Modifier.weight(1f)) }
    }

    @Test
    fun `modules list`() = check { ModulesScreen(SettingsState(), { _, _ -> }, {}) }

    @Test
    fun `settings`() = check { SettingsScreen(SettingsState(), "0.11.0", {}, {}, {}, {}, {}, {}) }

    @Test
    fun `khata`() = check {
        KhataScreen(listOf(KhataRow("1", "Rafi Hasan", 500, "Lunch", KhataRowStatus.WAITING)), SampleData.online, Modifier.weight(1f))
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = maxOf(a.luminance(), b.luminance()) + 0.05
        val l2 = minOf(a.luminance(), b.luminance()) + 0.05
        return l1 / l2
    }

    @Test
    fun `token text colours meet contrast on their backgrounds`() {
        for (c: HarkaraColors in listOf(Tokens.Day, Tokens.Night)) {
            for (bg in listOf(c.bgCanvas, c.bgSurface, c.bgRaised)) {
                assertTrue("primary", contrast(c.textPrimary, bg) >= 4.5)
                assertTrue("secondary ${contrast(c.textSecondary, bg)}", contrast(c.textSecondary, bg) >= 4.5)
                // Tertiary is reserved for incidental text (counters, hints); WCAG's non-text/large threshold.
                assertTrue("tertiary ${contrast(c.textTertiary, bg)}", contrast(c.textTertiary, bg) >= 3.0)
            }
            assertTrue("accent text on accent soft", contrast(c.accentText, c.accentSoft) >= 4.5)
            assertTrue("ink on accent", contrast(c.textOnAccent, c.accentFill) >= 4.5)
            assertTrue("inverse", contrast(c.textInverse, c.bgInverse) >= 4.5)
            assertTrue("sos on soft (large text)", contrast(c.stateSos, c.stateSosSoft) >= 3.0)
            assertTrue("money in", contrast(c.moneyIn, c.bgSurface) >= 3.0)
        }
    }
}
