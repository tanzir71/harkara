package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.CheckInCard
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.ConnectivityPill
import io.github.tanzir71.harkara.ui.components.Delivery
import io.github.tanzir71.harkara.ui.components.DeliveryTicks
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HToggle
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.IouCard
import io.github.tanzir71.harkara.ui.components.LocationCard
import io.github.tanzir71.harkara.ui.components.MessageBubble
import io.github.tanzir71.harkara.ui.components.PinCard
import io.github.tanzir71.harkara.ui.components.PostCard
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.components.TaskCard
import io.github.tanzir71.harkara.ui.components.VoiceNote
import io.github.tanzir71.harkara.ui.components.Wordmark
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.ui.components.categoryColor
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import androidx.compose.material3.Text
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every component from Figma "02 Components", Day and Night. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class ComponentScreenshotTest {
    @Test
    fun connectivityPills() = captureDayNight("component-pills") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Carrier.entries.forEach { ConnectivityPill(it, meshPeers = 12) }
        }
    }

    @Test
    fun chips() = captureDayNight("component-chips") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Traffic", selected = false, dot = HarkaraTheme.colors.categoryColor(Category.TRAFFIC))
            Chip("Traffic", selected = true, dot = HarkaraTheme.colors.categoryColor(Category.TRAFFIC))
            Chip("All", selected = true)
            Chip("Fuel", selected = false)
        }
    }

    @Test
    fun buttons() = captureDayNight("component-buttons") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(200.dp)) {
            HButtonVariant.entries.forEach { HButton("Accept task", {}, variant = it) }
            HButton("Record payment", {}, icon = io.github.tanzir71.harkara.R.drawable.ic_khata)
        }
    }

    @Test
    fun tabBar() = captureDayNight("component-tabbar", padded = false) {
        Column(Modifier.width(360.dp)) { HarkaraTab.entries.forEach { TabBar(it, {}) } }
    }

    @Test
    fun postCard() = captureDayNight("component-post") { PostCard(SampleData.posts.first(), Modifier.width(330.dp)) }

    @Test
    fun locationCard() = captureDayNight("component-location") {
        LocationCard("Shared location", "Dhanmondi 27 · about 100 m", "7MMG+QX Dhaka", Modifier.width(260.dp))
    }

    @Test
    fun taskCard() = captureDayNight("component-task") { TaskCard(SampleData.task, Modifier.width(260.dp)) }

    @Test
    fun iouCard() = captureDayNight("component-iou") { IouCard(SampleData.iou, Modifier.width(260.dp)) }

    @Test
    fun checkInCard() = captureDayNight("component-checkin") {
        CheckInCard("I'm safe", "Home · 21:40", Carrier.SMS, "by SMS", Modifier.width(260.dp))
    }

    @Test
    fun voiceNote() = captureDayNight("component-voice") { VoiceNote("0:12") }

    @Test
    fun pinCard() = captureDayNight("component-pin") { PinCard(SampleData.pin, Modifier.width(260.dp)) }

    @Test
    fun messageBubbles() = captureDayNight("component-bubbles") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(260.dp)) {
            MessageBubble("Pharmacy on Road 8 has insulin, I just checked.", "21:14", outgoing = false, carrier = Carrier.MESH)
            MessageBubble("Great, can someone pick up two pens?", "21:14", outgoing = true, carrier = Carrier.ONLINE, delivery = Delivery.Read)
        }
    }

    @Test
    fun togglesAvatarsSegmented() = captureDayNight("component-controls") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HToggle(false, {})
                HToggle(true, {})
                Avatar("RA")
                Avatar("RA", live = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(listOf("Feed", "Map"), 0, {})
                Segmented(listOf("Feed", "Map"), 1, {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Delivery.entries.forEach { DeliveryTicks(it) } }
        }
    }

    @Test
    fun sheetAndLogo() = captureDayNight("component-sheet") {
        SheetSurface(Modifier.width(330.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Wordmark()
                Text("Bottom sheet", style = HarkaraTheme.type.headline, color = HarkaraTheme.colors.textPrimary)
            }
        }
    }
}
