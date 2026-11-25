package io.github.tanzir71.harkara.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.tasks.DisputeReason
import io.github.tanzir71.harkara.core.tasks.Handoff
import io.github.tanzir71.harkara.core.tasks.Reputation
import io.github.tanzir71.harkara.core.tasks.StepType
import io.github.tanzir71.harkara.core.tasks.TaskState
import io.github.tanzir71.harkara.core.tasks.TaskStatus
import io.github.tanzir71.harkara.core.tasks.TaskTypes
import io.github.tanzir71.harkara.tasks.HandoffDisplay
import io.github.tanzir71.harkara.tasks.PayMethod
import io.github.tanzir71.harkara.tasks.TaskAction
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HFab
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HIconButton
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.components.QrCode
import io.github.tanzir71.harkara.ui.components.SectionHeader
import io.github.tanzir71.harkara.ui.components.Tag
import io.github.tanzir71.harkara.ui.components.TaskCard
import io.github.tanzir71.harkara.ui.components.TaskUi
import io.github.tanzir71.harkara.ui.components.ConnectivityPill
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Dhaka"))
fun hhmm(seconds: Long): String = clock.format(Instant.ofEpochSecond(seconds))

/** Short label for a pubkey when no name is known. */
fun shortKey(pubkey: String): String = pubkey.take(4) + "…" + pubkey.takeLast(4)

@StringRes
fun statusLabel(status: TaskStatus): Int = when (status) {
    TaskStatus.OPEN -> R.string.task_status_open
    TaskStatus.ACCEPTED -> R.string.task_status_accepted
    TaskStatus.PICKED_UP -> R.string.task_status_picked_up
    TaskStatus.DELIVERED -> R.string.task_status_delivered
    TaskStatus.PAID -> R.string.task_status_paid
    TaskStatus.CANCELLED -> R.string.task_status_cancelled
    TaskStatus.DISPUTED -> R.string.task_status_disputed
    TaskStatus.EXPIRED -> R.string.task_status_expired
}

/** Steps done out of five (accepted, picked up, delivered, paid, rated) for the progress bar. */
fun stepsDone(s: TaskState): Int = when (s.status) {
    TaskStatus.OPEN, TaskStatus.EXPIRED, TaskStatus.CANCELLED -> 0
    TaskStatus.ACCEPTED -> 1
    TaskStatus.PICKED_UP -> 2
    TaskStatus.DELIVERED -> 3
    TaskStatus.PAID -> if (s.ratings.isNotEmpty()) 5 else 4
    TaskStatus.DISPUTED -> s.applied.count { it.type in setOf(StepType.CONFIRM, StepType.PICKED_UP, StepType.DELIVERED) }
}

/** Display name for a person on a task: their chosen name, else a short key. */
fun nameOf(state: TaskState, pubkey: String?): String {
    if (pubkey == null) return ""
    if (pubkey == state.requester) return state.offer.requesterName ?: shortKey(pubkey)
    return (state.pendingAccepts + state.applied.map { it.event }).firstOrNull { it.pubkey == pubkey }?.tagValue("name") ?: shortKey(pubkey)
}

@Composable
fun typeLabel(types: TaskTypes?, id: String): String {
    val lang = LocalConfiguration.current.locales[0].language
    return types?.byId(id)?.label?.forLanguage(lang) ?: id
}

@Composable
fun TaskState.toCardUi(types: TaskTypes?, reputation: (String) -> Reputation?): TaskUi {
    val status = when {
        status == TaskStatus.OPEN && pendingAccepts.isNotEmpty() -> stringResource(R.string.task_status_offers, pendingAccepts.size)
        else -> stringResource(statusLabel(status)) + (applied.lastOrNull()?.let { " " + hhmm(it.event.createdAt) } ?: "")
    }
    val runnerLine = runner?.let { r ->
        val rep = reputation(r)
        nameOf(this, r) + " · " + (rep?.let { stringResource(R.string.task_done_count, it.done) } ?: stringResource(R.string.task_new_runner))
    }
    return TaskUi(
        type = stringResource(R.string.task_kind, typeLabel(types, offer.type)),
        price = "৳" + offer.price,
        title = offer.title,
        stepsDone = stepsDone(this),
        status = status,
        runner = runnerLine,
    )
}

/** Tasks tab: yours first (posted or running), then open tasks near you. */
@Composable
fun TasksScreen(
    states: List<TaskState>,
    me: String?,
    types: TaskTypes?,
    connectivity: ConnectivityState,
    modifier: Modifier = Modifier,
    pill: PillActions = PillActions(),
    reputation: (String) -> Reputation? = { null },
    onOpen: (TaskState) -> Unit = {},
    onNew: () -> Unit = {},
) {
    val yours = states.filter { it.requester == me || it.runner == me || it.pendingAccepts.any { a -> a.pubkey == me } }
    val nearby = states.filter { it !in yours && it.status == TaskStatus.OPEN }
    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TitleBar(stringResource(R.string.tab_tasks), connectivity, pill) }
            if (yours.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.tasks_yours), Modifier.padding(horizontal = 16.dp)) }
                items(yours, key = { "y" + it.offer.event.id }) { s ->
                    TaskCard(s.toCardUi(types, reputation), Modifier.padding(horizontal = 16.dp).clickable(role = Role.Button) { onOpen(s) })
                }
            }
            item { SectionHeader(stringResource(R.string.tasks_nearby), Modifier.padding(horizontal = 16.dp)) }
            if (nearby.isEmpty()) {
                item { CalmState(R.drawable.ic_tasks, stringResource(R.string.tasks_empty_title), stringResource(R.string.tasks_empty_body)) }
            } else {
                items(nearby, key = { "n" + it.offer.event.id }) { s ->
                    TaskCard(s.toCardUi(types, reputation), Modifier.padding(horizontal = 16.dp).clickable(role = Role.Button) { onOpen(s) })
                }
            }
        }
        HFab(R.drawable.ic_plus, stringResource(R.string.tasks_new), onNew, Modifier.align(Alignment.BottomEnd).padding(16.dp), text = stringResource(R.string.tasks_new))
    }
}

/** Callbacks for the detail screen's actions. */
data class TaskActions(
    val onBack: () -> Unit = {},
    val onAction: (TaskAction) -> Unit = {},
    val onChoose: (Event) -> Unit = {},
    val onProblem: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onMessage: (String) -> Unit = {},
)

/** Screen 05: one task with its signed timeline, the handoff card and the next action. */
@Composable
fun TaskDetailScreen(
    state: TaskState,
    me: String?,
    types: TaskTypes?,
    connectivity: ConnectivityState,
    next: TaskAction?,
    modifier: Modifier = Modifier,
    handoff: HandoffDisplay? = null,
    reputation: (String) -> Reputation? = { null },
    mutualContacts: (String) -> Int = { 0 },
    actions: TaskActions = TaskActions(),
    pill: PillActions = PillActions(),
) {
    val c = HarkaraTheme.colors
    val isRequester = me == state.requester
    val isRunner = me != null && me == state.runner
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HIconButton(R.drawable.ic_arrow_left, stringResource(R.string.back), actions.onBack, background = c.bgCanvas)
            Text(stringResource(R.string.task_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            ConnectivityPill(connectivity.best, meshPeers = connectivity.meshPeers, onClick = pill.onClick, onLongClick = pill.onLongClick)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val posted = hhmm(state.offer.createdAt)
            Text(
                if (isRequester) stringResource(R.string.task_posted_by_you, typeLabel(types, state.offer.type), posted)
                else stringResource(R.string.task_posted_by, typeLabel(types, state.offer.type), nameOf(state, state.requester), posted),
                style = HarkaraTheme.type.caption, color = c.textSecondary,
            )
            Text(state.offer.title, style = HarkaraTheme.type.display.copy(fontSize = Tokens.Text.title.sizeSp * 1.1f), color = c.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("৳" + state.offer.price, style = HarkaraTheme.type.amount, color = c.textPrimary)
                Tag(stringResource(R.string.task_paid_on_handoff))
            }
            state.offer.fields.forEach { (k, v) ->
                val label = types?.byId(state.offer.type)?.fields?.firstOrNull { it.key == k }?.label?.forLanguage(LocalConfiguration.current.locales[0].language) ?: k
                Text("$label: $v", style = HarkaraTheme.type.caption, color = c.textSecondary)
            }
            if (state.offer.description.isNotBlank()) Text(state.offer.description, style = HarkaraTheme.type.body, color = c.textPrimary)

            val other = if (isRequester) state.runner else state.requester
            if (other != null) PersonCard(nameOf(state, other), reputation(other), mutualContacts(other)) { actions.onMessage(other) }

            if (isRequester && state.status == TaskStatus.OPEN && state.pendingAccepts.isNotEmpty()) {
                SectionHeader(stringResource(R.string.task_offers_title))
                state.pendingAccepts.forEach { a ->
                    HCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val name = nameOf(state, a.pubkey)
                            Avatar(io.github.tanzir71.harkara.ui.components.initialsOf(name))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = HarkaraTheme.type.headline, color = c.textPrimary)
                                val rep = reputation(a.pubkey)
                                Text(rep?.let { stringResource(R.string.task_done_count, it.done) } ?: stringResource(R.string.task_new_runner), style = HarkaraTheme.type.caption, color = c.textSecondary)
                            }
                            HButton(stringResource(R.string.task_action_choose, name), { actions.onChoose(a) }, height = 40.dp)
                        }
                    }
                }
            }

            Timeline(state, isRequester, isRunner)

            if (handoff != null && isRequester && state.status in setOf(TaskStatus.ACCEPTED, TaskStatus.PICKED_UP)) {
                HandoffCard(handoff, nameOf(state, state.runner))
            }
            Spacer(Modifier.height(8.dp))
        }
        val nextLabel = next?.let { actionLabel(it, state) }
        val canProblem = (isRequester || isRunner) && state.status in setOf(TaskStatus.ACCEPTED, TaskStatus.PICKED_UP, TaskStatus.DELIVERED, TaskStatus.PAID)
        val secondary: Pair<String, () -> Unit>? = when {
            canProblem -> stringResource(R.string.task_action_problem) to actions.onProblem
            isRequester && state.status == TaskStatus.OPEN -> stringResource(R.string.task_action_cancel) to actions.onCancel
            else -> null
        }
        if (nextLabel != null || secondary != null) {
            Column(Modifier.fillMaxWidth().background(c.bgSurface)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (secondary != null) HButton(secondary.first, secondary.second, Modifier.weight(if (nextLabel == null) 1f else 0.4f), variant = HButtonVariant.Secondary, height = 52.dp)
                    if (nextLabel != null) HButton(nextLabel, { actions.onAction(next) }, Modifier.weight(0.6f), height = 52.dp)
                }
            }
        }
    }
}

@Composable
private fun actionLabel(a: TaskAction, state: TaskState): String = when (a) {
    TaskAction.ACCEPT -> stringResource(R.string.task_action_accept)
    TaskAction.CONFIRM_RUNNER -> stringResource(R.string.task_action_choose, nameOf(state, state.pendingAccepts.first().pubkey))
    TaskAction.PICKED_UP -> stringResource(R.string.task_action_picked_up)
    TaskAction.ENTER_CODE -> stringResource(R.string.task_action_enter_code)
    TaskAction.RECORD_PAYMENT -> stringResource(R.string.task_action_record_payment)
    TaskAction.CONFIRM_PAYMENT -> stringResource(R.string.task_action_confirm_payment)
    TaskAction.RATE -> stringResource(R.string.task_action_rate)
}

@Composable
private fun PersonCard(name: String, rep: Reputation?, mutual: Int, onMessage: () -> Unit) {
    val c = HarkaraTheme.colors
    HCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(io.github.tanzir71.harkara.ui.components.initialsOf(name))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = HarkaraTheme.type.headline.copy(fontWeight = FontWeight.Medium), color = c.textPrimary)
                    if (rep != null && rep.done > 0) {
                        Spacer(Modifier.width(6.dp))
                        HIcon(R.drawable.ic_verified, tint = c.stateOnline, size = 16.dp)
                    }
                }
                Text(
                    listOf(
                        rep?.let { stringResource(R.string.task_done_count, it.done) } ?: stringResource(R.string.task_new_runner),
                        stringResource(R.string.task_mutual, mutual),
                    ).joinToString(" · "),
                    style = HarkaraTheme.type.caption, color = c.textSecondary,
                )
            }
            HIconButton(R.drawable.ic_chats, stringResource(R.string.task_message, name), onMessage, size = 44.dp)
        }
    }
}

private data class Line(val label: String, val time: Long?, val hint: String? = null)

@Composable
private fun Timeline(state: TaskState, isRequester: Boolean, isRunner: Boolean) {
    val c = HarkaraTheme.colors
    val lines = listOf(
        Line(stringResource(R.string.task_step_posted), state.offer.createdAt),
        Line(stringResource(R.string.task_step_accepted, nameOf(state, state.runner ?: "")), state.at(StepType.CONFIRM)?.event?.createdAt),
        Line(stringResource(R.string.task_step_picked_up), state.at(StepType.PICKED_UP)?.event?.createdAt),
        Line(
            stringResource(R.string.task_step_delivered), state.at(StepType.DELIVERED)?.event?.createdAt,
            when {
                isRequester -> stringResource(R.string.task_hint_delivered_requester)
                isRunner -> stringResource(R.string.task_hint_delivered_runner)
                else -> null
            },
        ),
        Line(stringResource(R.string.task_step_paid), state.at(StepType.PAID_ACK)?.event?.createdAt, stringResource(R.string.task_hint_paid)),
        Line(stringResource(R.string.task_step_rated), state.applied.lastOrNull { it.type == StepType.RATED }?.event?.createdAt),
    )
    val currentIndex = lines.indexOfFirst { it.time == null }.takeIf { it >= 0 && state.status !in setOf(TaskStatus.CANCELLED, TaskStatus.DISPUTED, TaskStatus.EXPIRED) }
    Column {
        lines.forEachIndexed { i, line ->
            val done = line.time != null
            val current = i == currentIndex
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .background(if (done) c.bgInverse else if (current) c.accentFill else c.bgSurface)
                            .border(1.5.dp, if (done) c.bgInverse else if (current) c.accentFill else c.borderStrong, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) HIcon(R.drawable.ic_check, tint = c.textInverse, size = 14.dp)
                        if (current) Box(Modifier.size(7.dp).clip(CircleShape).background(c.textOnAccent))
                    }
                    if (i < lines.lastIndex) Box(Modifier.width(1.5.dp).height(if (current && line.hint != null) 38.dp else 26.dp).background(if (done) c.bgInverse else c.borderStrong))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(top = 1.dp)) {
                    Text(line.label, style = HarkaraTheme.type.headline.copy(fontWeight = FontWeight.Medium), color = if (done || current) c.textPrimary else c.textTertiary)
                    if (done) Text(hhmm(line.time!!), style = HarkaraTheme.type.caption, color = c.textSecondary)
                    else if (current && line.hint != null) Text(line.hint, style = HarkaraTheme.type.caption, color = c.accentText)
                    else if (line.hint != null && i == 4) Text(line.hint, style = HarkaraTheme.type.caption, color = c.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun HandoffCard(handoff: HandoffDisplay, runnerName: String) {
    val c = HarkaraTheme.colors
    HCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QrCode(handoff.qrPayload, stringResource(R.string.task_handoff_qr), Modifier.size(120.dp))
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.task_handoff_title), style = HarkaraTheme.type.caption, color = c.textSecondary)
                Text(Handoff.displayCode(handoff.code), style = HarkaraTheme.type.mono.copy(fontSize = Tokens.Text.display.sizeSp), color = c.textPrimary)
                Text(stringResource(R.string.task_handoff_body, runnerName), style = HarkaraTheme.type.caption, color = c.textSecondary)
            }
        }
    }
}

/** New task: type chips, title, price, the type's fields, details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskComposeSheetContent(
    types: TaskTypes,
    onPost: (type: String, title: String, price: Long, fields: Map<String, String>, description: String) -> Unit,
    modifier: Modifier = Modifier,
    initialType: String = types.types.first().id,
    initialTitle: String = "",
    initialFields: Map<String, String> = emptyMap(),
) {
    val c = HarkaraTheme.colors
    val lang = LocalConfiguration.current.locales[0].language
    var type by remember { mutableStateOf(initialType) }
    var title by remember { mutableStateOf(initialTitle) }
    var price by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    val fields = remember { mutableStateMapOf<String, String>().apply { putAll(initialFields) } }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.task_new_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.task_new_type), style = HarkaraTheme.type.label.copy(fontSize = Tokens.Text.caption.sizeSp), color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            types.types.forEach { t -> Chip(t.label.forLanguage(lang), selected = type == t.id, onClick = { type = t.id }) }
        }
        HTextField(title, { title = it.take(120) }, stringResource(R.string.task_new_name))
        HTextField(price, { v -> price = v.filter(Char::isDigit).take(6) }, stringResource(R.string.task_new_price), mono = true)
        types.byId(type)?.fields?.forEach { f ->
            HTextField(fields[f.key].orEmpty(), { fields[f.key] = it.take(200) }, f.label.forLanguage(lang) + if (f.required) "" else "", singleLine = !f.multiline)
        }
        HTextField(details, { details = it.take(500) }, stringResource(R.string.task_new_details), singleLine = false)
        if (missing.isNotEmpty()) {
            val labels = missing.map { key -> types.byId(type)?.fields?.firstOrNull { it.key == key }?.label?.forLanguage(lang) ?: key }
            Text(stringResource(R.string.task_new_missing, labels.joinToString(", ")), style = HarkaraTheme.type.caption, color = c.stateSos)
        }
        Text(stringResource(R.string.task_new_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HButton(stringResource(R.string.task_new_post), {
            missing = types.missing(type, fields)
            if (missing.isEmpty()) onPost(type, title, price.toLong(), fields.filterKeys { k -> types.byId(type)?.fields?.any { it.key == k } == true }, details)
        }, Modifier.fillMaxWidth(), height = 52.dp, enabled = title.isNotBlank() && price.isNotEmpty())
    }
}

/** Runner enters the 6 digits (or pastes the QR text); checked offline. */
@Composable
fun HandoffEntrySheetContent(onCheck: (String) -> Unit, wrong: Boolean, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var code by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.task_code_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.task_code_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HTextField(code, { code = it.take(4000) }, stringResource(R.string.task_code_label), mono = true, error = if (wrong) stringResource(R.string.task_code_wrong) else null)
        HButton(stringResource(R.string.task_code_check), { onCheck(code) }, Modifier.fillMaxWidth(), height = 52.dp, enabled = code.isNotBlank())
    }
}

/** Requester records cash or a bKash/Nagad TrxID; the runner countersigns. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentSheetContent(amount: Long, onSave: (PayMethod, String) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var method by remember { mutableStateOf(PayMethod.CASH) }
    var trx by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.task_pay_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text("৳$amount", style = HarkaraTheme.type.amount, color = c.moneyOut)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PayMethod.CASH to R.string.task_pay_cash, PayMethod.BKASH to R.string.task_pay_bkash, PayMethod.NAGAD to R.string.task_pay_nagad).forEach { (m, l) ->
                Chip(stringResource(l), selected = method == m, onClick = { method = m })
            }
        }
        if (method != PayMethod.CASH) HTextField(trx, { trx = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(20) }, stringResource(R.string.task_pay_trxid), mono = true)
        HButton(stringResource(R.string.task_pay_save), { onSave(method, trx) }, Modifier.fillMaxWidth(), height = 52.dp, enabled = method == PayMethod.CASH || trx.length >= 6)
    }
}

@Composable
fun RateSheetContent(onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var stars by remember { mutableIntStateOf(5) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.task_rate_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n -> Chip(stringResource(R.string.task_rate_star, n), selected = stars == n, onClick = { stars = n }) }
        }
        HButton(stringResource(R.string.task_rate_send), { onRate(stars) }, Modifier.fillMaxWidth(), height = 52.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisputeSheetContent(onSend: (DisputeReason, String) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var reason by remember { mutableStateOf(DisputeReason.DELIVERY_ISSUE) }
    var note by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.task_dispute_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.task_dispute_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DisputeReason.ITEM_ISSUE to R.string.task_dispute_item, DisputeReason.PAYMENT_ISSUE to R.string.task_dispute_payment,
                DisputeReason.DELIVERY_ISSUE to R.string.task_dispute_delivery, DisputeReason.SAFETY_OR_POLICY to R.string.task_dispute_safety,
                DisputeReason.OTHER to R.string.task_dispute_other,
            ).forEach { (r, l) -> Chip(stringResource(l), selected = reason == r, onClick = { reason = r }) }
        }
        HTextField(note, { note = it.take(300) }, stringResource(R.string.task_dispute_note), singleLine = false)
        HButton(stringResource(R.string.task_dispute_send), { onSend(reason, note) }, Modifier.fillMaxWidth(), height = 52.dp)
    }
}

/** Asked only the first time a name is needed (posting or taking a task, adding an IOU). */
@Composable
fun NameSheetContent(onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var name by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.name_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.name_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HTextField(name, { v -> name = v.filterNot(Char::isDigit).take(40) }, stringResource(R.string.name_label))
        HButton(stringResource(R.string.name_save), { onSave(name) }, Modifier.fillMaxWidth(), height = 52.dp, enabled = name.isNotBlank())
    }
}
