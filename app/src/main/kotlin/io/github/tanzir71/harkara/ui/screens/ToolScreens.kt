package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.tools.DayPrayers
import io.github.tanzir71.harkara.tools.EmergencyNumber
import io.github.tanzir71.harkara.tools.HandbookSection
import io.github.tanzir71.harkara.tools.LightMode
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.SectionHeader
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens
import java.time.Instant

/** Prayer times for today and the Qibla bearing (Karachi, Hanafi Asr). */
@Composable
fun PrayerScreen(day: DayPrayers, qibla: Double, place: String, now: Instant, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val next = day.next(now)?.first
    val names = mapOf(
        "fajr" to R.string.prayer_fajr, "sunrise" to R.string.prayer_sunrise, "dhuhr" to R.string.prayer_dhuhr,
        "asr" to R.string.prayer_asr, "maghrib" to R.string.prayer_maghrib, "isha" to R.string.prayer_isha,
    )
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackBar(stringResource(R.string.prayer_title), onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.prayer_method, place), style = HarkaraTheme.type.caption, color = c.textSecondary)
            HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                Column {
                    day.all().forEachIndexed { i, (key, at) ->
                        if (i > 0) RowDivider(Modifier.padding(start = 0.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(names.getValue(key)), style = HarkaraTheme.type.headline.copy(fontWeight = if (key == next) FontWeight.SemiBold else FontWeight.Medium), color = c.textPrimary)
                            if (key == next) {
                                Spacer(Modifier.width(8.dp))
                                ComingSoonTag(stringResource(R.string.prayer_next))
                            }
                            Spacer(Modifier.weight(1f))
                            Text(hhmm(at.epochSecond), style = HarkaraTheme.type.mono, color = c.textPrimary)
                        }
                    }
                }
            }
            HCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QiblaDial(qibla, Modifier.size(96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.qibla_bearing, qibla.toInt()), style = HarkaraTheme.type.headline, color = c.textPrimary)
                        Text(stringResource(R.string.qibla_hint), style = HarkaraTheme.type.caption, color = c.textSecondary)
                    }
                }
            }
        }
    }
}

/** North-up dial with the Qibla needle in the accent colour. */
@Composable
fun QiblaDial(bearing: Double, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val description = stringResource(R.string.qibla_bearing, bearing.toInt())
    Canvas(modifier.semantics { contentDescription = description }) {
        val r = size.minDimension / 2
        drawCircle(c.bgSunken, r)
        drawCircle(c.borderDefault, r, style = Stroke(1.dp.toPx()))
        drawLine(c.textTertiary, Offset(center.x, center.y - r + 6.dp.toPx()), Offset(center.x, center.y - r + 14.dp.toPx()), 2.dp.toPx())
        rotate(bearing.toFloat()) {
            drawLine(c.accentFill, center, Offset(center.x, center.y - r * 0.8f), 4.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(c.textPrimary, 4.dp.toPx())
    }
}

/** Torch, strobe, Morse SOS and siren; everything stops when the screen goes away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorchScreen(mode: LightMode, lit: Boolean, torchAvailable: Boolean, onMode: (LightMode) -> Unit, onLeave: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) leave()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); leave() }
    }
    Column(modifier.fillMaxSize().background(if (!torchAvailable && lit) c.textInverse else c.bgCanvas)) {
        BackBar(stringResource(R.string.torch_title), onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LightMode.TORCH to R.string.torch_on, LightMode.STROBE to R.string.torch_strobe, LightMode.MORSE_SOS to R.string.torch_morse, LightMode.SIREN to R.string.torch_siren)
                    .forEach { (m, label) -> Chip(stringResource(label), selected = mode == m, onClick = { onMode(if (mode == m) LightMode.OFF else m) }) }
            }
            if (mode != LightMode.OFF) HButton(stringResource(R.string.torch_stop), { onMode(LightMode.OFF) }, Modifier.fillMaxWidth(), height = 52.dp)
            Text(stringResource(R.string.torch_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
            if (!torchAvailable) Text(stringResource(R.string.torch_unavailable), style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
    }
}

/** Handbook index: emergency numbers first, then sections by group. */
@Composable
fun HandbookScreen(sections: List<HandbookSection>, numbers: List<EmergencyNumber>, language: String, onOpen: (HandbookSection) -> Unit, onCall: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.handbook_title), onBack)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader(stringResource(R.string.handbook_numbers)) }
            items(numbers.take(6), key = { "n" + it.number }) { n ->
                HCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.handbook_call, n.number)) { onCall(n.number) }, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.number, style = HarkaraTheme.type.mono.copy(fontSize = Tokens.Text.title.sizeSp), color = if (n.number == "999") c.stateSos else c.textPrimary)
                        Spacer(Modifier.width(12.dp))
                        Text(n.label.pick(language), style = HarkaraTheme.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                    }
                }
            }
            sections.groupBy { it.group }.forEach { (group, list) ->
                item { SectionHeader(stringResource(when (group) {
                    "start" -> R.string.handbook_group_start
                    "numbers" -> R.string.handbook_group_numbers
                    "first-aid" -> R.string.handbook_group_first_aid
                    "flood" -> R.string.handbook_group_flood
                    "cyclone" -> R.string.handbook_group_cyclone
                    "fire" -> R.string.handbook_group_fire
                    "earthquake" -> R.string.handbook_group_earthquake
                    "outage" -> R.string.handbook_group_outage
                    "crowd" -> R.string.handbook_group_crowd
                    "kit" -> R.string.handbook_group_kit
                    else -> R.string.handbook_title
                })) }
                items(list, key = { it.id }) { s ->
                    HCard(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(s) }, padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HIcon(R.drawable.ic_handbook, tint = c.textSecondary, size = 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(s.title.pick(language), style = HarkaraTheme.type.headline.copy(fontWeight = FontWeight.Medium), color = c.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** Renders the handbook's small Markdown subset: #/## headings, numbered and bulleted lists, **bold**. */
@Composable
fun HandbookPage(markdown: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val lines = markdown.lines()
    val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ") ?: ""
    Column(modifier.fillMaxSize()) {
        BackBar(title, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.filterNot { it.startsWith("# ") }.forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.isBlank() -> Spacer(Modifier.size(4.dp))
                    line.startsWith("## ") -> Text(line.removePrefix("## "), style = HarkaraTheme.type.headline, color = c.textPrimary, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                    line.startsWith("- ") || line.startsWith("* ") -> Row { Text("•  ", color = c.textSecondary, style = HarkaraTheme.type.body); Text(bold(line.drop(2)), style = HarkaraTheme.type.body, color = c.textPrimary) }
                    Regex("^\\d+\\. ").containsMatchIn(line) -> {
                        val n = line.substringBefore(". ")
                        Row { Text("$n.  ", color = c.textSecondary, style = HarkaraTheme.type.mono); Text(bold(line.substringAfter(". ")), style = HarkaraTheme.type.body, color = c.textPrimary) }
                    }
                    else -> Text(bold(line), style = HarkaraTheme.type.body, color = c.textPrimary)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

private fun bold(text: String) = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part) }
}

/** The SOS sheet: "Call 999" first, then send SOS on every open route. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SosSheetContent(
    waitMinutes: Int,
    onCall999: () -> Unit,
    onSend: (reason: String, alsoSms: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    val reasons = listOf(R.string.sos_reason_medical, R.string.sos_reason_fire, R.string.sos_reason_water, R.string.sos_reason_trapped, R.string.sos_reason_unsafe)
    var reason by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var sms by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val reasonText = stringResource(reasons[reason])
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.sos_title), style = HarkaraTheme.type.title, color = c.stateSos, modifier = Modifier.semantics { heading() })
        HButton(stringResource(R.string.sos_call_999), onCall999, Modifier.fillMaxWidth(), height = 56.dp, icon = R.drawable.ic_sos)
        Text(stringResource(R.string.sos_call_999_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { RowDivider(Modifier.padding(start = 0.dp)) }
        Text(stringResource(R.string.sos_send_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reasons.forEachIndexed { i, r -> Chip(stringResource(r), selected = reason == i, onClick = { reason = i }) }
            Chip(stringResource(R.string.sos_also_sms), selected = sms, onClick = { sms = !sms })
        }
        if (waitMinutes > 0) {
            Text(stringResource(R.string.sos_wait, waitMinutes), style = HarkaraTheme.type.caption, color = c.stateSos)
        }
        HButton(stringResource(R.string.sos_send), { onSend(reasonText, sms) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, height = 52.dp, enabled = waitMinutes == 0)
    }
}

/** Banner for the user's own active SOS. */
@Composable
fun ActiveSosBanner(onResolve: () -> Unit, onFalseAlarm: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(
        modifier.fillMaxWidth().background(c.stateSosSoft, androidx.compose.foundation.shape.RoundedCornerShape(Tokens.Radius.lg)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(R.drawable.ic_sos, tint = c.stateSos, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sos_active), style = HarkaraTheme.type.headline, color = c.stateSos)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(R.string.sos_resolve), onResolve, height = 40.dp)
            HButton(stringResource(R.string.sos_false_alarm), onFalseAlarm, variant = HButtonVariant.Ghost, height = 40.dp)
        }
    }
}

/** An SOS from someone nearby. "Call 999" comes first. */
@Composable
fun SosAlertCard(message: String, detail: String, onCall999: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(
        modifier.fillMaxWidth().background(c.stateSosSoft, androidx.compose.foundation.shape.RoundedCornerShape(Tokens.Radius.lg)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(R.drawable.ic_sos, tint = c.stateSos, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sos_incoming), style = HarkaraTheme.type.headline, color = c.stateSos)
        }
        Text(message, style = HarkaraTheme.type.body, color = c.textPrimary)
        Text(detail, style = HarkaraTheme.type.caption, color = c.textSecondary)
        HButton(stringResource(R.string.sos_call_999), onCall999, height = 40.dp, icon = R.drawable.ic_sos)
    }
}
