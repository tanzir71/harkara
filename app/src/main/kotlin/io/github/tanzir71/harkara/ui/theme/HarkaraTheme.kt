package io.github.tanzir71.harkara.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle

/** Text styles built from [Tokens.Text] with the bundled fonts. */
@Immutable
data class HarkaraTypography(
    val display: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val micro: TextStyle,
    val amount: TextStyle,
    val banglaBody: TextStyle,
    val banglaLabel: TextStyle,
    val banglaTitle: TextStyle,
    val mono: TextStyle,
)

private fun familyFor(name: String): FontFamily = when (name) {
    "Anek Bangla" -> HarkaraFonts.anekBangla
    "JetBrains Mono" -> HarkaraFonts.jetBrainsMono
    else -> HarkaraFonts.geist
}

/** Converts a design token into a Compose [TextStyle]; numbers use tabular figures. */
fun TextToken.toTextStyle(): TextStyle = TextStyle(
    fontFamily = familyFor(family),
    fontWeight = FontWeight(weight),
    fontSize = sizeSp,
    lineHeight = lineHeightSp,
    letterSpacing = letterSpacingSp,
    fontFeatureSettings = "tnum",
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Typography for the current UI language. Bangla UIs use Anek Bangla for UI text at the same sizes. */
fun typographyFor(bangla: Boolean): HarkaraTypography {
    if (!bangla) return DefaultTypography
    val b = HarkaraFonts.anekBangla
    return with(DefaultTypography) {
        copy(
            display = display.copy(fontFamily = b),
            title = title.copy(fontFamily = b),
            headline = headline.copy(fontFamily = b),
            body = body.copy(fontFamily = b),
            bodyStrong = bodyStrong.copy(fontFamily = b),
            caption = caption.copy(fontFamily = b),
            label = label.copy(fontFamily = b),
            micro = micro.copy(fontFamily = b),
        )
    }
}

val DefaultTypography = HarkaraTypography(
    display = Tokens.Text.display.toTextStyle(),
    title = Tokens.Text.title.toTextStyle(),
    headline = Tokens.Text.headline.toTextStyle(),
    body = Tokens.Text.body.toTextStyle(),
    bodyStrong = Tokens.Text.bodyStrong.toTextStyle(),
    caption = Tokens.Text.caption.toTextStyle(),
    label = Tokens.Text.label.toTextStyle(),
    micro = Tokens.Text.micro.toTextStyle(),
    amount = Tokens.Text.amount.toTextStyle(),
    banglaBody = Tokens.Text.banglaBody.toTextStyle(),
    banglaLabel = Tokens.Text.banglaLabel.toTextStyle(),
    banglaTitle = Tokens.Text.banglaTitle.toTextStyle(),
    mono = Tokens.Text.monoCode.toTextStyle(),
)

val LocalHarkaraColors = staticCompositionLocalOf { Tokens.Day }
val LocalHarkaraTypography = staticCompositionLocalOf { DefaultTypography }
val LocalIsNight = staticCompositionLocalOf { false }

/** Accessors for the current Harkara theme values. */
object HarkaraTheme {
    val colors: HarkaraColors
        @Composable @ReadOnlyComposable get() = LocalHarkaraColors.current
    val type: HarkaraTypography
        @Composable @ReadOnlyComposable get() = LocalHarkaraTypography.current
    val isNight: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsNight.current
}

private fun HarkaraColors.toMaterial(night: Boolean): ColorScheme {
    val base = if (night) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = bgInverse,
        onPrimary = textInverse,
        secondary = accentFill,
        onSecondary = textOnAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = accentText,
        background = bgCanvas,
        onBackground = textPrimary,
        surface = bgSurface,
        onSurface = textPrimary,
        surfaceVariant = bgSunken,
        onSurfaceVariant = textSecondary,
        surfaceContainer = bgSurface,
        surfaceContainerLow = bgRaised,
        surfaceContainerHigh = bgSurface,
        outline = borderDefault,
        outlineVariant = borderSubtle,
        error = stateSos,
        onError = textInverse,
        errorContainer = stateSosSoft,
        onErrorContainer = stateSos,
        scrim = bgInverse,
    )
}

/** Harkara theme: Day (sunlight) by default, Night for OLED, following the system unless overridden. */
@Composable
fun HarkaraTheme(night: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (night) Tokens.Night else Tokens.Day
    val type = typographyFor(LocalConfiguration.current.locales[0].language == "bn")
    CompositionLocalProvider(
        LocalHarkaraColors provides colors,
        LocalHarkaraTypography provides type,
        LocalIsNight provides night,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(night),
            typography = Typography(
                bodyLarge = type.body,
                bodyMedium = type.caption,
                bodySmall = type.micro,
                labelLarge = type.bodyStrong,
                labelMedium = type.label,
                labelSmall = type.micro,
                titleLarge = type.title,
                titleMedium = type.headline,
                titleSmall = type.bodyStrong,
                headlineSmall = type.display,
            ),
            content = content,
        )
    }
}
