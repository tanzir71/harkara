package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tanzir71.harkara.core.khata.Iou
import io.github.tanzir71.harkara.core.khata.IouBundle
import io.github.tanzir71.harkara.core.khata.IouTerms
import io.github.tanzir71.harkara.core.khata.Party
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.screens.IouDetailSheetContent
import io.github.tanzir71.harkara.ui.screens.KhataRow
import io.github.tanzir71.harkara.ui.screens.KhataRowStatus
import io.github.tanzir71.harkara.ui.screens.KhataScreen
import io.github.tanzir71.harkara.ui.screens.NewIouSheetContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screen 06 (Khata) with the reference's rows, plus the new-IOU and detail sheets, Day and Night. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class KhataScreenshotTest {
    private val rows = listOf(
        KhataRow("1", "Rafi Hasan", 500, "Star Kabab lunch · today", KhataRowStatus.WAITING),
        KhataRow("2", "Nasrin, Flat 3B", 450, "Generator fuel · 2 days", KhataRowStatus.SIGNED),
        KhataRow("3", "Karim Store", -300, "Baki: rice, oil · 1 wk", KhataRowStatus.SIGNED),
        KhataRow("4", "Mitu", 300, "Bus fare · 3 wks", KhataRowStatus.SMS),
        KhataRow("5", "Sumon Ahmed", 120, "Insulin pens · bKash", KhataRowStatus.SETTLED),
    )

    @Test
    fun khata() = captureDayNight("screen-06-khata", padded = false) {
        Column(Modifier.fillMaxSize()) {
            KhataScreen(rows, SampleData.online, Modifier.weight(1f))
            TabBar(HarkaraTab.Khata, {})
        }
    }

    @Test
    fun newIou() = captureDayNight("screen-khata-new", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { NewIouSheetContent({ _, _, _, _, _ -> }, { _, _, _ -> }) }
        }
    }

    @Test
    fun iouDetail() = captureDayNight("screen-khata-detail", padded = false) {
        val me = KeyPair.fromHex("33".repeat(32))
        val terms = IouTerms("iou", Party("Me", me.pubkeyHex), Party("Rafi Hasan", "44".repeat(32)), 500, note = "Star Kabab lunch", createdAt = 1_764_082_680)
        val state = Iou.state(IouBundle(terms, listOf(Iou.sign(me, terms, 1_764_082_680).toJson())))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { IouDetailSheetContent(state, me.pubkeyHex, {}, {}, {}, {}, {}, {}) }
        }
    }
}
