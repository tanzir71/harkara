package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tanzir71.harkara.core.tools.PrayerTimesCalc
import io.github.tanzir71.harkara.tools.Handbook
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.screens.HandbookScreen
import io.github.tanzir71.harkara.ui.screens.PrayerScreen
import io.github.tanzir71.harkara.ui.screens.SosAlertCard
import io.github.tanzir71.harkara.ui.screens.SosSheetContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** SOS sheet, an incoming SOS card, prayer times + Qibla and the handbook index, Day and Night. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class ToolScreenshotTest {
    @Test
    fun sosSheet() = captureDayNight("screen-sos", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                SosAlertCard("Water rising at Road 7, two elderly neighbours on the ground floor", "Tania · 450 m · 21:40", {})
            }
            SheetSurface { SosSheetContent(0, {}, { _, _ -> }) }
        }
    }

    @Test
    fun prayer() = captureDayNight("screen-prayer", padded = false) {
        val day = PrayerTimesCalc.forDay(23.7465, 90.376, LocalDate.of(2026, 11, 25))
        PrayerScreen(day, PrayerTimesCalc.qibla(23.7465, 90.376), "Dhanmondi", day.dhuhr.plusSeconds(60), {})
    }

    @Test
    fun handbook() = captureDayNight("screen-handbook", padded = false) {
        val hb = Handbook { File("../data/$it").readText() }
        HandbookScreen(hb.index.sections, hb.index.emergencyNumbers, "en", {}, {}, {})
    }
}
