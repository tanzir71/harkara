package io.github.tanzir71.harkara.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HarkaraColors = darkColorScheme(
    primary = Color(0xFF85DCC6),
    secondary = Color(0xFFE0C25C),
    tertiary = Color(0xFFFF9F7A),
    background = Color(0xFF101313),
    surface = Color(0xFF171B1B),
    onPrimary = Color(0xFF002019),
    onSecondary = Color(0xFF242000),
    onTertiary = Color(0xFF351000),
    onBackground = Color(0xFFE8F0ED),
    onSurface = Color(0xFFE8F0ED),
)

/** Harkara Material 3 theme. */
@Composable
fun HarkaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HarkaraColors,
        content = content,
    )
}
