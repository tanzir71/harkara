package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.feed.ReportReason
import io.github.tanzir71.harkara.relay.RelayStatus
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HIconButton
import io.github.tanzir71.harkara.ui.components.SectionHeader
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

private val reasonLabels = listOf(
    ReportReason.SPAM to R.string.report_spam,
    ReportReason.ABUSE to R.string.report_abuse,
    ReportReason.ILLEGAL to R.string.report_illegal,
    ReportReason.FALSE to R.string.report_false,
)

/** Block, report and mute, on every card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardActionsSheetContent(
    onReport: (ReportReason) -> Unit,
    onBlock: () -> Unit,
    onMuteWord: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFetch: (() -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    var word by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.card_actions_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        if (onFetch != null) HButton(stringResource(R.string.task_fetch), onFetch, Modifier.fillMaxWidth(), variant = HButtonVariant.Accent, icon = R.drawable.ic_tasks)
        Text(stringResource(R.string.card_report), style = HarkaraTheme.type.headline, color = c.textPrimary)
        Text(stringResource(R.string.card_report_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reasonLabels.forEach { (reason, label) -> Chip(stringResource(label), selected = false, onClick = { onReport(reason) }) }
        }
        HCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
            SettingsRow(R.drawable.ic_close, stringResource(R.string.card_block), subtitle = stringResource(R.string.card_block_body), onClick = onBlock)
        }
        Text(stringResource(R.string.card_mute), style = HarkaraTheme.type.headline, color = c.textPrimary)
        val hint = stringResource(R.string.card_mute_hint)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(Tokens.Radius.md)
            Box(Modifier.weight(1f).clip(shape).background(c.bgSurface).border(1.dp, c.borderDefault, shape).padding(12.dp)) {
                BasicTextField(
                    word,
                    { word = it.take(40) },
                    singleLine = true,
                    textStyle = HarkaraTheme.type.body.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.textPrimary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                    decorationBox = { inner ->
                        if (word.isEmpty()) Text(hint, style = HarkaraTheme.type.body, color = c.textTertiary)
                        inner()
                    },
                )
            }
            HButton(stringResource(R.string.card_mute), { if (word.isNotBlank()) onMuteWord(word) }, variant = HButtonVariant.Secondary, enabled = word.isNotBlank())
        }
    }
}

/** Settings → Relays: the user's relay list with live status. */
@Composable
fun RelaysScreen(
    relays: List<String>,
    status: Map<String, RelayStatus>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    var draft by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.relays_title), onBack)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.relays_body), style = HarkaraTheme.type.caption, color = c.textSecondary) }
            items(relays, key = { it }) { url ->
                val s = status[url.trimEnd('/')]
                HCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (s == RelayStatus.CONNECTED) c.stateOnline else c.stateQueued))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(url.removePrefix("wss://"), style = HarkaraTheme.type.mono, color = c.textPrimary)
                            Text(
                                stringResource(
                                    when (s) {
                                        RelayStatus.CONNECTED -> R.string.relays_connected
                                        RelayStatus.CONNECTING -> R.string.relays_connecting
                                        else -> R.string.relays_offline
                                    },
                                ),
                                style = HarkaraTheme.type.label,
                                color = c.textSecondary,
                            )
                        }
                        HIconButton(R.drawable.ic_close, stringResource(R.string.relays_remove, url), { onRemove(url) }, size = 44.dp)
                    }
                }
            }
            item {
                SectionHeader(stringResource(R.string.relays_add))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shape = RoundedCornerShape(Tokens.Radius.md)
                    val hint = stringResource(R.string.relays_add_hint)
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape).background(c.bgSurface).border(1.dp, c.borderDefault, shape).padding(12.dp)) {
                        BasicTextField(
                            draft,
                            { draft = it.trim() },
                            singleLine = true,
                            textStyle = HarkaraTheme.type.mono.copy(color = c.textPrimary),
                            cursorBrush = SolidColor(c.textPrimary),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                            decorationBox = { inner ->
                                if (draft.isEmpty()) Text(hint, style = HarkaraTheme.type.mono, color = c.textTertiary)
                                inner()
                            },
                        )
                    }
                    HButton(stringResource(R.string.relays_add), {
                        if (draft.startsWith("wss://") || draft.startsWith("ws://")) {
                            onAdd(draft)
                            draft = ""
                        }
                    }, variant = HButtonVariant.Secondary)
                }
            }
            item { HButton(stringResource(R.string.relays_reset), onReset, Modifier.padding(vertical = 12.dp), variant = HButtonVariant.Ghost) }
        }
    }
}
