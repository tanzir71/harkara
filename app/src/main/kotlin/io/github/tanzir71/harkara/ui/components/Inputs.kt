package io.github.tanzir71.harkara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

/** Single-line text field on the surface colour with a hairline, a label above and an optional error below. */
@Composable
fun HTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    error: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
) {
    val c = HarkaraTheme.colors
    val shape = RoundedCornerShape(Tokens.Radius.md)
    val base: TextStyle = if (mono) HarkaraTheme.type.mono else HarkaraTheme.type.body
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = HarkaraTheme.type.label, color = c.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = base.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.textPrimary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = imeAction,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .background(c.bgSurface)
                        .border(1.dp, if (error != null) c.stateSos else c.borderDefault, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
        if (error != null) Text(error, style = HarkaraTheme.type.caption, color = c.stateSos)
    }
}

/**
 * A QR code drawn on a Canvas from ZXing's bit matrix. Always Day ink on Day surface, so phones can scan
 * it in the Night theme too.
 */
@Composable
fun QrCode(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encodeQr(text) }
    val ink = Tokens.Day.textPrimary
    val paper = Tokens.Day.bgSurface
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(paper)
            .padding(12.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / matrix.width
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) drawRect(ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** Byte-mode QR with medium error correction and a two-module quiet zone (the box adds more). */
fun encodeQr(text: String): BitMatrix =
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
    )
