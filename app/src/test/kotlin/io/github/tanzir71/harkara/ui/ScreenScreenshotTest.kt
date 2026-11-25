package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.settings.MeshMode
import io.github.tanzir71.harkara.settings.SettingsState
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.screens.BackupSheetContent
import io.github.tanzir71.harkara.ui.screens.BackupStep
import io.github.tanzir71.harkara.ui.screens.RestoreSheetContent
import io.github.tanzir71.harkara.ui.screens.CardActionsSheetContent
import io.github.tanzir71.harkara.ui.screens.ChatsScreen
import io.github.tanzir71.harkara.ui.screens.ComposeSheetContent
import io.github.tanzir71.harkara.ui.screens.RelaysScreen
import io.github.tanzir71.harkara.ui.screens.ConnectivitySheetContent
import io.github.tanzir71.harkara.ui.screens.MeScreen
import io.github.tanzir71.harkara.ui.screens.ModulesScreen
import io.github.tanzir71.harkara.ui.screens.NearbyScreen
import io.github.tanzir71.harkara.ui.screens.OnboardingScreen
import io.github.tanzir71.harkara.ui.screens.PanicWipeSheetContent
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.core.ConnectivityState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Full screens at the 360 × 800 dp reference size, Day and Night. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class ScreenScreenshotTest {
    @Composable
    private fun WithTabs(tab: HarkaraTab, content: @Composable (Modifier) -> Unit) {
        Column(Modifier.fillMaxSize()) {
            content(Modifier.weight(1f))
            TabBar(tab, {})
        }
    }

    @Test
    fun nearbyFeed() = captureDayNight("screen-01-nearby-feed", padded = false) {
        WithTabs(HarkaraTab.Nearby) { NearbyScreen("Dhanmondi 27", areaAuto = true, connectivity = SampleData.online, posts = SampleData.posts, modifier = it) }
    }

    @Test
    fun nearbyEmptySaved() = captureDayNight("screen-01b-nearby-empty", padded = false) {
        WithTabs(HarkaraTab.Nearby) { NearbyScreen("Dhanmondi 27", areaAuto = false, connectivity = ConnectivityState.Saved, posts = emptyList(), modifier = it) }
    }

    @Test
    fun chatsUpcoming() = captureDayNight("screen-04-chats-upcoming", padded = false) {
        WithTabs(HarkaraTab.Chats) { ChatsScreen(SampleData.meshOnly, it) }
    }

    @Test
    fun meTools() = captureDayNight("screen-07-me-tools", padded = false) {
        WithTabs(HarkaraTab.Me) {
            MeScreen(SettingsState(onboarded = true, areaName = "Dhanmondi 27"), SampleData.online, it, npub = SampleData.npub)
        }
    }

    @Test
    fun backupSheet() = captureDayNight("screen-backup-sheet", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface {
                BackupSheetContent(BackupStep.Password, "amar notun phone", "amar notun phone", {}, {}, {}, {}, {}, Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
        }
    }

    @Test
    fun backupQr() = captureDayNight("screen-backup-qr", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface {
                BackupSheetContent(BackupStep.Qr(SampleData.ncryptsec), "", "", {}, {}, {}, {}, {}, Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
        }
    }

    @Test
    fun restoreSheet() = captureDayNight("screen-restore-sheet", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface {
                RestoreSheetContent(SampleData.ncryptsec, "purono", false, "Wrong password or damaged backup", {}, {}, {}, {}, Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
        }
    }

    @Test
    fun connectivitySheet() = captureDayNight("screen-08-connectivity", padded = false) {
        Box(Modifier.fillMaxSize()) {
            NearbyScreen("Dhanmondi 27", true, SampleData.online, SampleData.posts)
            Box(Modifier.fillMaxSize().background(HarkaraTheme.colors.bgInverse.copy(alpha = 0.32f)))
            SheetSurface(Modifier.align(Alignment.BottomCenter)) {
                ConnectivitySheetContent(SampleData.online, MeshMode.SAVER, {})
            }
        }
    }

    @Test
    fun composeSheet() = captureDayNight("screen-03-compose", padded = false) {
        Box(Modifier.fillMaxSize()) {
            NearbyScreen("Dhanmondi 27", true, SampleData.online, SampleData.posts)
            Box(Modifier.fillMaxSize().background(HarkaraTheme.colors.bgInverse.copy(alpha = 0.32f)))
            SheetSurface(Modifier.align(Alignment.BottomCenter)) {
                ComposeSheetContent(
                    "Dhanmondi 27 · auto",
                    SampleData.online,
                    { _, _, _ -> },
                    initialText = "Octane is back at the Satmasjid Road pump, short queue now",
                    initialCategory = io.github.tanzir71.harkara.core.Category.STOCK,
                )
            }
        }
    }

    @Test
    fun cardActionsSheet() = captureDayNight("screen-card-actions", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { CardActionsSheetContent({}, {}, {}) }
        }
    }

    @Test
    fun relays() = captureDayNight("screen-relays", padded = false) {
        RelaysScreen(
            listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://relay.nostr.net", "wss://nos.lol"),
            mapOf("wss://relay.primal.net" to io.github.tanzir71.harkara.relay.RelayStatus.CONNECTED),
            {}, {}, {}, {},
        )
    }

    @Test
    fun modules() = captureDayNight("screen-modules", padded = false) {
        ModulesScreen(SettingsState(), { _, _ -> }, {})
    }

    @Test
    fun onboarding() = captureDayNight("screen-onboarding", padded = false) {
        OnboardingScreen(listOf("Dhanmondi", "Gulshan 1", "Banani", "Mirpur 10", "Uttara Sector 7", "Mohammadpur", "Shahbagh", "Farmgate", "Old Dhaka"), {}, {})
    }

    @Test
    fun panicWipe() = captureDayNight("screen-panic-wipe", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { PanicWipeSheetContent({}, {}, Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
        }
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h800dp-mdpi")
    fun meToolsBangla() = captureDayNight("screen-07-me-tools-bn", padded = false) {
        WithTabs(HarkaraTab.Me) { MeScreen(SettingsState(onboarded = true), ConnectivityState.Saved, it, npub = SampleData.npub) }
    }
}
