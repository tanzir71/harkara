package io.github.tanzir71.harkara.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.tanzir71.harkara.R

/** Bundled OFL-1.1 fonts (licences in `app/src/main/assets/licenses/`). */
object HarkaraFonts {
    val geist = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
    )
    val anekBangla = FontFamily(
        Font(R.font.anek_bangla_regular, FontWeight.Normal),
        Font(R.font.anek_bangla_medium, FontWeight.Medium),
        Font(R.font.anek_bangla_semibold, FontWeight.SemiBold),
    )
    val jetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}
