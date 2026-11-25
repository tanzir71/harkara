package io.github.tanzir71.harkara.tools.lan

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.ui.PlatformActions
import io.github.tanzir71.harkara.ui.screens.LanToolsScreen
import io.github.tanzir71.harkara.ui.screens.LanToolsSession
import io.github.tanzir71.harkara.ui.screens.parseLanToolsPeer
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LanToolsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `peer input accepts numeric LAN only and never resolves a hostname`() {
        assertNotNull(parseLanToolsPeer("192.168.1.8", "53317"))
        assertNotNull(parseLanToolsPeer("127.0.0.1", "1234"))
        listOf("example.com", "8.8.8.8", "192.168.1.999", "192.168.1", "0.0.0.0", "http://192.168.1.8", "192.168.1.+8").forEach {
            assertNull(it, parseLanToolsPeer(it, "53317"))
        }
        assertNull(parseLanToolsPeer("192.168.1.8", "0"))
        assertNull(parseLanToolsPeer("192.168.1.8", "65536"))
    }

    @Test fun `session construction foreground and background return never start a listener`() {
        val session = LanToolsSession(context)
        assertEquals(-1, session.state.value.port)
        session.foreground(true)
        assertEquals(-1, session.state.value.port)
        session.start(false, null, 0)
        val port = session.state.value.port
        assertTrue(port > 0)
        val staleStart = session.generation()
        session.foreground(false)
        assertEquals(-1, session.state.value.port)
        assertThrows(Exception::class.java) { Socket("127.0.0.1", port).close() }
        session.start(false, null, 0)
        assertEquals(-1, session.state.value.port)
        session.foreground(true)
        assertEquals(-1, session.state.value.port)
        session.start(false, null, 0, staleStart)
        assertEquals(-1, session.state.value.port)
        session.stop()
    }

    @Test fun `both tool modes initially show stopped explicit start and cleartext warning`() {
        val ptt = mutableStateOf(false)
        compose.setContent { HarkaraTheme { LanToolsScreen(ptt.value, PlatformActions.None, {}) } }
        compose.onNodeWithText(context.getString(R.string.lan_tools_stopped)).assertExists()
        compose.onNodeWithText(context.getString(R.string.lan_tools_start)).assertExists()
        compose.onNodeWithText(context.getString(R.string.lan_tools_warning)).assertIsDisplayed()
        compose.runOnIdle { ptt.value = true }
        compose.onNodeWithText(context.getString(R.string.lan_tools_ptt_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lan_tools_stopped)).assertExists()
        compose.onNodeWithText(context.getString(R.string.lan_tools_copy_token)).assertDoesNotExist()
    }

    @Test fun `back leaves without requesting microphone or starting networking`() {
        var back = false
        compose.setContent { HarkaraTheme { LanToolsScreen(true, PlatformActions.None, { back = true }) } }
        compose.onNodeWithText(context.getString(R.string.lan_tools_ptt_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lan_tools_stopped)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.runOnIdle { assertTrue(back) }
    }

    @Test fun `hold control has a bounded accessibility action disabled until explicit setup`() {
        compose.setContent { HarkaraTheme { LanToolsScreen(true, PlatformActions.None, {}) } }
        val hold = compose.onNodeWithContentDescription(context.getString(R.string.lan_tools_hold))
        hold.assertExists().assertHasClickAction().assertIsNotEnabled()
        val action = hold.fetchSemanticsNode().config[SemanticsActions.OnClick]
        compose.runOnIdle {
            assertEquals(context.getString(R.string.lan_tools_speak_briefly), action.label)
            assertFalse(action.action!!.invoke())
        }
        compose.onNodeWithText(context.getString(R.string.lan_tools_stopped)).assertExists()
    }
}
