package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

/** Golden screenshots live in `app/src/test/screenshots`; verified in CI with a small tolerance for font rasterising. */
@OptIn(ExperimentalRoborazziApi::class)
val screenshotOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f),
)

/** Captures [content] in Day and Night themes as `<name>-day.png` / `<name>-night.png`. */
fun captureDayNight(name: String, padded: Boolean = true, content: @Composable () -> Unit) {
    for (night in listOf(false, true)) {
        captureRoboImage("src/test/screenshots/$name-${if (night) "night" else "day"}.png", roborazziOptions = screenshotOptions) {
            HarkaraTheme(night = night) {
                Box(Modifier.background(HarkaraTheme.colors.bgCanvas).then(if (padded) Modifier.padding(12.dp) else Modifier)) {
                    content()
                }
            }
        }
    }
}
