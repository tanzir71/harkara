package io.github.tanzir71.harkara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

/** Counts taps; three within [windowMs] triggers the panic wipe prompt. */
class TripleTapDetector(private val windowMs: Long = 1_200, private val now: () -> Long = System::currentTimeMillis) {
    private val taps = ArrayDeque<Long>()

    /** Registers one tap and returns true when it completes a triple tap. */
    fun tap(): Boolean {
        val t = now()
        taps.addLast(t)
        while (taps.isNotEmpty() && t - taps.first() > windowMs) taps.removeFirst()
        if (taps.size >= 3) {
            taps.clear()
            return true
        }
        return false
    }
}

/**
 * The logo mark from `design/brand/logo-mark.svg`: ink rounded square, dashed hop route,
 * two white stops and the mustard destination dot. Triple-tap calls [onTripleTap].
 */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 32.dp, onTripleTap: (() -> Unit)? = null) {
    val detector = remember { TripleTapDetector() }
    val description = stringResource(R.string.logo_description)
    val ink = Tokens.Day.bgInverse
    val mustard = Tokens.Day.accentFill
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = description }
            .then(
                if (onTripleTap != null) {
                    Modifier.pointerInput(Unit) { detectTapGestures { if (detector.tap()) onTripleTap() } }
                } else {
                    Modifier
                },
            ),
    ) {
        val s = this.size.width / 32f
        drawRoundRect(ink, cornerRadius = CornerRadius(9 * s))
        val route = Path().apply {
            moveTo(8 * s, 23 * s)
            lineTo(15 * s, 12 * s)
            lineTo(24 * s, 18 * s)
        }
        drawPath(
            route,
            Color.White,
            style = Stroke(
                width = 2 * s,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * s, 3.5f * s)),
            ),
        )
        drawCircle(Color.White, 2.5f * s, Offset(8 * s, 23 * s))
        drawCircle(Color.White, 2.5f * s, Offset(15 * s, 12 * s))
        drawCircle(mustard, 4 * s, Offset(24 * s, 18 * s))
    }
}

/** Mark + "harkara" wordmark + optional Bangla "হরকরা" in tertiary. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, showBangla: Boolean = true, onTripleTap: (() -> Unit)? = null) {
    val c = HarkaraTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LogoMark(size = 32.dp, onTripleTap = onTripleTap)
        Text("harkara", style = HarkaraTheme.type.title, color = c.textPrimary)
        if (showBangla) Text(stringResource(R.string.app_name_bn), style = HarkaraTheme.type.banglaLabel, color = c.textTertiary)
    }
}
