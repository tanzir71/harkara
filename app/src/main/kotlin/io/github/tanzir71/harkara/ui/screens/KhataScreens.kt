package io.github.tanzir71.harkara.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.core.khata.IouState
import io.github.tanzir71.harkara.core.khata.IouStatus
import io.github.tanzir71.harkara.khata.IouDirection
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HFab
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.components.QrCode
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.components.initialsOf
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import java.text.NumberFormat
import java.util.Locale

/** "৳1,250" with grouping; tabular figures come from the theme. */
fun taka(amount: Long): String = "৳" + NumberFormat.getIntegerInstance(Locale.US).format(amount)

/** One row on the Khata list: an IOU, or a receipt derived from a paid task. */
data class KhataRow(
    val id: String,
    val name: String,
    /** Positive when they owe me, negative when I owe; settled rows show the plain amount. */
    val amount: Long,
    val subtitle: String,
    val status: KhataRowStatus,
    val iou: IouState? = null,
)

enum class KhataRowStatus(@DrawableRes val icon: Int) {
    WAITING(R.drawable.ic_lock), NEEDS_YOU(R.drawable.ic_lock), SIGNED(R.drawable.ic_check),
    SETTLING(R.drawable.ic_queued), SETTLED(R.drawable.ic_check), SMS(R.drawable.ic_sms),
}

enum class KhataFilter { ALL, WAITING, AGREED, SETTLED }

fun IouState.toRow(me: String, subtitle: String): KhataRow {
    val incoming = terms.creditor.pubkey == me
    val other = if (incoming) terms.debtor else terms.creditor
    val status = when (status) {
        IouStatus.CLAIMED -> if (waitingFor(me)) KhataRowStatus.NEEDS_YOU else if (other.pubkey == null) KhataRowStatus.SMS else KhataRowStatus.WAITING
        IouStatus.AGREED -> KhataRowStatus.SIGNED
        IouStatus.SETTLING -> KhataRowStatus.SETTLING
        IouStatus.SETTLED -> KhataRowStatus.SETTLED
    }
    return KhataRow(terms.id, other.name, if (incoming) terms.amount else -terms.amount, subtitle, status, this)
}

@Composable
private fun statusText(s: KhataRowStatus): String = stringResource(
    when (s) {
        KhataRowStatus.WAITING -> R.string.khata_status_waiting
        KhataRowStatus.NEEDS_YOU -> R.string.khata_status_needs_you
        KhataRowStatus.SIGNED -> R.string.khata_status_signed
        KhataRowStatus.SETTLING -> R.string.khata_status_settling
        KhataRowStatus.SETTLED -> R.string.khata_status_settled
        KhataRowStatus.SMS -> R.string.khata_status_sms
    },
)

/** Screen 06: totals, filters and the ledger. */
@Composable
fun KhataScreen(
    rows: List<KhataRow>,
    connectivity: ConnectivityState,
    modifier: Modifier = Modifier,
    pill: PillActions = PillActions(),
    onOpen: (KhataRow) -> Unit = {},
    onNew: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val c = HarkaraTheme.colors
    var filter by rememberSaveable { mutableStateOf(KhataFilter.ALL) }
    val open = rows.filter { it.status != KhataRowStatus.SETTLED }
    val get = open.filter { it.amount > 0 }
    val owe = open.filter { it.amount < 0 }
    val waiting = rows.count { it.status in setOf(KhataRowStatus.WAITING, KhataRowStatus.NEEDS_YOU, KhataRowStatus.SMS) }
    val shown = rows.filter {
        when (filter) {
            KhataFilter.ALL -> true
            KhataFilter.WAITING -> it.status in setOf(KhataRowStatus.WAITING, KhataRowStatus.NEEDS_YOU, KhataRowStatus.SMS)
            KhataFilter.AGREED -> it.status in setOf(KhataRowStatus.SIGNED, KhataRowStatus.SETTLING)
            KhataFilter.SETTLED -> it.status == KhataRowStatus.SETTLED
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item { TitleBar(stringResource(R.string.tab_khata), connectivity, pill, bangla = "খাতা".takeIf { stringResource(R.string.tab_khata) != it }) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TotalCard(stringResource(R.string.khata_youll_get), get.sumOf { it.amount }, c.moneyIn, stringResource(R.string.khata_from_people, get.map { it.name }.distinct().size), Modifier.weight(1f))
                    val oweNames = owe.map { it.name }.distinct()
                    TotalCard(
                        stringResource(R.string.khata_you_owe), -owe.sumOf { it.amount }, c.moneyOut,
                        if (oweNames.size == 1) stringResource(R.string.khata_to_one, oweNames.first()) else stringResource(R.string.khata_to_people, oweNames.size),
                        Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.khata_filter_all), filter == KhataFilter.ALL, onClick = { filter = KhataFilter.ALL })
                    Chip(if (waiting > 0) stringResource(R.string.khata_filter_waiting, waiting) else stringResource(R.string.khata_filter_waiting_none), filter == KhataFilter.WAITING, onClick = { filter = KhataFilter.WAITING })
                    Chip(stringResource(R.string.khata_filter_agreed), filter == KhataFilter.AGREED, onClick = { filter = KhataFilter.AGREED })
                    Chip(stringResource(R.string.khata_filter_settled), filter == KhataFilter.SETTLED, onClick = { filter = KhataFilter.SETTLED })
                }
            }
            if (shown.isEmpty()) {
                item { CalmState(R.drawable.ic_khata, stringResource(R.string.khata_empty_title), stringResource(R.string.khata_empty_body)) }
            } else {
                item {
                    HCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), padding = PaddingValues(0.dp)) {
                        Column {
                            shown.forEachIndexed { i, row ->
                                if (i > 0) RowDivider(Modifier.padding(start = 0.dp))
                                KhataRowView(row) { onOpen(row) }
                            }
                        }
                    }
                }
            }
            if (rows.isNotEmpty()) {
                item { HButton(stringResource(R.string.khata_action_export), onExport, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), variant = HButtonVariant.Ghost, icon = R.drawable.ic_drop) }
            }
        }
        HFab(R.drawable.ic_plus, stringResource(R.string.khata_new), onNew, Modifier.align(Alignment.BottomEnd).padding(16.dp), text = stringResource(R.string.khata_new))
    }
}

@Composable
private fun TotalCard(label: String, amount: Long, color: Color, detail: String, modifier: Modifier) {
    val c = HarkaraTheme.colors
    HCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = HarkaraTheme.type.caption, color = c.textSecondary)
            Text(taka(amount), style = HarkaraTheme.type.amount, color = color)
            Text(detail, style = HarkaraTheme.type.label, color = c.textSecondary)
        }
    }
}

@Composable
private fun KhataRowView(row: KhataRow, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    val settled = row.status == KhataRowStatus.SETTLED
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(initialsOf(row.name))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = HarkaraTheme.type.headline.copy(fontWeight = FontWeight.Medium), color = c.textPrimary, maxLines = 1)
            Text(row.subtitle, style = HarkaraTheme.type.caption, color = c.textSecondary, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                when {
                    settled -> taka(kotlin.math.abs(row.amount))
                    row.amount >= 0 -> "+" + taka(row.amount)
                    else -> "−" + taka(-row.amount)
                },
                style = HarkaraTheme.type.headline,
                color = when {
                    settled -> c.textTertiary
                    row.amount >= 0 -> c.moneyIn
                    else -> c.moneyOut
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(row.status.icon, tint = if (row.status == KhataRowStatus.SMS) c.stateSms else c.textTertiary, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(statusText(row.status), style = HarkaraTheme.type.label, color = c.textSecondary)
            }
        }
    }
}

/** New IOU: direction, amount, what for, who (name and optional key) or a split bill. */
@Composable
fun NewIouSheetContent(
    onSave: (direction: IouDirection, name: String, npub: String, amount: Long, note: String) -> Unit,
    onSplit: (names: List<String>, total: Long, note: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    var direction by remember { mutableIntStateOf(0) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var npub by remember { mutableStateOf("") }
    var split by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.khata_new_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf(stringResource(R.string.khata_they_owe), stringResource(R.string.khata_i_owe)), if (split) 0 else direction, { direction = it; if (it == 1) split = false })
            Spacer(Modifier.weight(1f))
            Chip(stringResource(R.string.khata_split), split, onClick = { split = !split; if (split) direction = 0 })
        }
        HTextField(amount, { v -> amount = v.filter(Char::isDigit).take(7) }, stringResource(R.string.khata_amount), mono = true)
        HTextField(note, { note = it.take(120) }, stringResource(R.string.khata_what))
        if (split) {
            HTextField(names, { names = it.take(600) }, stringResource(R.string.khata_split_names), singleLine = false)
            val people = names.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (people.isNotEmpty() && amount.isNotEmpty()) {
                Text(stringResource(R.string.khata_split_each, people.size, (amount.toLong() / people.size).toInt()), style = HarkaraTheme.type.caption, color = c.textSecondary)
            }
            HButton(stringResource(R.string.khata_save), { onSplit(people, amount.toLong(), note) }, Modifier.fillMaxWidth(), height = 52.dp, enabled = people.isNotEmpty() && amount.isNotEmpty())
        } else {
            HTextField(name, { v -> name = v.filterNot(Char::isDigit).take(40) }, stringResource(R.string.khata_who))
            HTextField(npub, { npub = it.trim().take(100) }, stringResource(R.string.khata_who_key), mono = true)
            HButton(
                stringResource(R.string.khata_save),
                { onSave(if (direction == 0) IouDirection.THEY_OWE_ME else IouDirection.I_OWE_THEM, name, npub, amount.toLong(), note) },
                Modifier.fillMaxWidth(), height = 52.dp, enabled = name.isNotBlank() && amount.isNotEmpty(),
            )
        }
        Text(stringResource(R.string.khata_save_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
    }
}

/** One IOU with its signatures and what can be done next. */
@Composable
fun IouDetailSheetContent(
    state: IouState,
    me: String,
    onSign: () -> Unit,
    onShowQr: () -> Unit,
    onPasteQr: () -> Unit,
    onRemind: () -> Unit,
    onSettle: () -> Unit,
    onConfirmSettle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    val t = state.terms
    val incoming = t.creditor.pubkey == me
    val other = if (incoming) t.debtor else t.creditor
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (incoming) stringResource(R.string.khata_owes_you, other.name) else stringResource(R.string.khata_you_owe_them, other.name),
            style = HarkaraTheme.type.caption, color = c.textSecondary,
        )
        Text(taka(t.amount), style = HarkaraTheme.type.amount, color = if (incoming) c.moneyIn else c.moneyOut)
        if (t.note.isNotBlank()) Text(t.note, style = HarkaraTheme.type.body, color = c.textPrimary)
        val line = when (state.status) {
            IouStatus.CLAIMED -> if (me in state.signedBy) stringResource(R.string.khata_waiting_for, other.name) else stringResource(R.string.khata_status_needs_you)
            IouStatus.AGREED -> stringResource(R.string.khata_agreed)
            IouStatus.SETTLING -> stringResource(R.string.khata_status_settling) + " · " + (state.settlement?.trxId ?: "")
            IouStatus.SETTLED -> stringResource(R.string.khata_settled_with, listOfNotNull(state.settlement?.method, state.settlement?.trxId?.ifBlank { null }).joinToString(" · "))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(if (state.status == IouStatus.CLAIMED) R.drawable.ic_lock else R.drawable.ic_check, tint = c.textSecondary, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(line, style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
        if (state.waitingFor(me)) HButton(stringResource(R.string.khata_action_sign), onSign, Modifier.fillMaxWidth(), height = 52.dp)
        if (state.status == IouStatus.CLAIMED) {
            HButton(stringResource(R.string.khata_action_qr), onShowQr, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, icon = R.drawable.ic_qr)
            HButton(stringResource(R.string.khata_action_paste), onPasteQr, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost)
        }
        if (incoming && state.status != IouStatus.SETTLED) HButton(stringResource(R.string.khata_action_remind), onRemind, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, icon = R.drawable.ic_sms)
        if (state.status == IouStatus.AGREED) HButton(stringResource(R.string.khata_action_settle), onSettle, Modifier.fillMaxWidth())
        if (state.status == IouStatus.SETTLING && state.settlement != null) HButton(stringResource(R.string.khata_action_confirm_settle), onConfirmSettle, Modifier.fillMaxWidth())
    }
}

/** QR for face-to-face co-signing. */
@Composable
fun IouQrSheetContent(qr: String, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.khata_qr_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        QrCode(qr, stringResource(R.string.khata_action_qr), Modifier.size(260.dp))
        Text(stringResource(R.string.khata_qr_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
    }
}

@Composable
fun PasteQrSheetContent(onRead: (String) -> Unit, bad: Boolean, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var text by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.khata_action_paste), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        HTextField(text, { text = it.take(8000) }, stringResource(R.string.khata_qr_paste), mono = true, singleLine = false, error = if (bad) stringResource(R.string.khata_qr_bad) else null)
        HButton(stringResource(R.string.khata_qr_import), { onRead(text) }, Modifier.fillMaxWidth(), height = 52.dp, enabled = text.isNotBlank())
    }
}
