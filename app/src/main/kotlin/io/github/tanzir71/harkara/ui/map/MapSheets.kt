package io.github.tanzir71.harkara.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.location.LiveDuration
import io.github.tanzir71.harkara.core.location.LocationCardData
import io.github.tanzir71.harkara.core.location.LocationPrecision
import io.github.tanzir71.harkara.core.location.TripPlan
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.core.nostr.Hex
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.places.OutgoingShare
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.components.LocationCard
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.components.categoryColor
import io.github.tanzir71.harkara.ui.screens.hhmm
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

/** People typed as npub1… or hex keys, one per line or separated by spaces/commas. Null when one is not a key. */
fun parseKeys(text: String): List<String>? {
    val parts = text.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
    return parts.map { p ->
        when {
            p.startsWith("npub1") -> runCatching { Bech32.decodeNpub(p) }.getOrNull() ?: return null
            Hex.isLowerHex(p.lowercase(), 32) -> p.lowercase()
            else -> return null
        }
    }.distinct()
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = HarkaraTheme.type.title, color = HarkaraTheme.colors.textPrimary, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Note(text: String) {
    Text(text, style = HarkaraTheme.type.caption, color = HarkaraTheme.colors.textSecondary)
}

/** People field: npubs, checked as you type. */
@Composable
private fun PeopleField(value: String, onChange: (String) -> Unit, label: String = stringResource(R.string.map_people_keys)): List<String>? {
    val keys = parseKeys(value)
    HTextField(
        value, { onChange(it.take(2_000)) }, label, mono = true, singleLine = false,
        error = if (value.isNotBlank() && keys == null) stringResource(R.string.map_people_bad) else null,
    )
    return keys
}

/** New pin or a new version of one of ours. */
@Composable
fun PinEditorSheetContent(
    editing: Boolean,
    onSave: (title: String, note: String, color: Category, visibility: PinVisibility, recipients: List<String>, ttlDays: Int?) -> Unit,
    modifier: Modifier = Modifier,
    initialTitle: String = "",
    initialNote: String = "",
    initialColor: Category = Category.STOCK,
    initialVisibility: PinVisibility = PinVisibility.PRIVATE,
    onDelete: (() -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    var title by remember { mutableStateOf(initialTitle) }
    var note by remember { mutableStateOf(initialNote) }
    var color by remember { mutableStateOf(initialColor) }
    var visibility by remember { mutableStateOf(initialVisibility) }
    var people by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(7) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitle(stringResource(if (editing) R.string.pin_edit_title else R.string.pin_new_title))
        HTextField(title, { title = it.take(80) }, stringResource(R.string.pin_field_title))
        HTextField(note, { note = it.take(400) }, stringResource(R.string.pin_field_note), singleLine = false)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { cat -> Chip(cat.label, selected = color == cat, dot = c.categoryColor(cat), onClick = { color = cat }) }
        }
        Segmented(
            listOf(stringResource(R.string.pin_private), stringResource(R.string.pin_shared), stringResource(R.string.pin_public)),
            visibility.ordinal,
            { visibility = PinVisibility.entries[it] },
        )
        Note(
            stringResource(
                when (visibility) {
                    PinVisibility.PRIVATE -> R.string.pin_private_note
                    PinVisibility.SHARED -> R.string.pin_shared_note
                    PinVisibility.PUBLIC -> R.string.pin_public_note
                },
            ),
        )
        var keys: List<String>? = emptyList()
        if (visibility == PinVisibility.SHARED) keys = PeopleField(people, { people = it })
        if (visibility == PinVisibility.PUBLIC) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to R.string.pin_ttl_day, 7 to R.string.pin_ttl_week, 30 to R.string.pin_ttl_month).forEach { (d, label) ->
                    Chip(stringResource(label), selected = days == d, onClick = { days = d })
                }
            }
        }
        val ok = title.isNotBlank() && (visibility != PinVisibility.SHARED || !keys.isNullOrEmpty())
        HButton(
            stringResource(R.string.pin_save),
            { onSave(title, note, color, visibility, keys.orEmpty(), if (visibility == PinVisibility.PUBLIC) days else null) },
            Modifier.fillMaxWidth(), height = 52.dp, enabled = ok,
        )
        if (onDelete != null) HButton(stringResource(R.string.pin_delete), onDelete, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost)
    }
}

/** One-off location card: pick how precise, see what is shared, then share or copy. */
@Composable
fun LocationCardSheetContent(
    cardFor: (LocationPrecision) -> LocationCardData?,
    onShare: (LocationCardData) -> Unit,
    onCopy: (LocationCardData) -> Unit,
    modifier: Modifier = Modifier,
) {
    var precision by remember { mutableStateOf(LocationPrecision.HUNDRED_METERS) }
    val card = cardFor(precision)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitle(stringResource(R.string.loc_title))
        Segmented(
            listOf(stringResource(R.string.loc_exact), stringResource(R.string.loc_100m), stringResource(R.string.loc_area)),
            precision.ordinal,
            { precision = LocationPrecision.entries[it] },
        )
        if (card == null) {
            Note(stringResource(R.string.loc_no_fix))
        } else {
            LocationCard(stringResource(R.string.loc_card_title), locationDetail(card), card.shortPlusCode(), Modifier.fillMaxWidth())
            Note(stringResource(R.string.loc_note))
            HButton(stringResource(R.string.loc_share), { onShare(card) }, Modifier.fillMaxWidth(), height = 52.dp, icon = R.drawable.ic_send)
            HButton(stringResource(R.string.loc_copy), { onCopy(card) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
        }
    }
}

@Composable
fun locationDetail(card: LocationCardData): String = listOf(
    card.label,
    stringResource(
        when (card.precision) {
            LocationPrecision.EXACT -> R.string.loc_detail_exact
            LocationPrecision.HUNDRED_METERS -> R.string.loc_detail_100m
            LocationPrecision.AREA -> R.string.loc_detail_area
        },
    ),
).filter { it.isNotBlank() }.joinToString(" · ")

/** Live share: who, how long; or the running share with Stop. */
@Composable
fun LiveShareSheetContent(
    running: OutgoingShare?,
    onStart: (recipients: List<String>, duration: LiveDuration) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    var people by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(LiveDuration.HOUR_1) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitle(stringResource(R.string.live_title))
        if (running != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.stateOnline))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.live_running, running.recipients.size, hhmm(running.end)), style = HarkaraTheme.type.body, color = c.textPrimary)
            }
            Note(stringResource(R.string.live_sent, running.sent))
            HButton(stringResource(R.string.live_stop), onStop, Modifier.fillMaxWidth(), height = 52.dp)
        } else {
            val keys = PeopleField(people, { people = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.live_15m), duration == LiveDuration.MIN_15, onClick = { duration = LiveDuration.MIN_15 })
                Chip(stringResource(R.string.live_1h), duration == LiveDuration.HOUR_1, onClick = { duration = LiveDuration.HOUR_1 })
                Chip(stringResource(R.string.live_8h), duration == LiveDuration.HOUR_8, onClick = { duration = LiveDuration.HOUR_8 })
            }
            Note(stringResource(R.string.live_note))
            HButton(stringResource(R.string.live_start), { onStart(keys.orEmpty(), duration) }, Modifier.fillMaxWidth(), height = 52.dp, enabled = !keys.isNullOrEmpty())
        }
    }
}

/** "I got here safe" and trip plans. */
@Composable
fun CheckInSheetContent(
    defaultPlace: String,
    trip: TripPlan?,
    tripsEnabled: Boolean,
    onCheckIn: (place: String, recipients: List<String>, note: String) -> Unit,
    onStartTrip: (destination: String, minutes: Int, recipients: List<String>) -> Unit,
    onCancelTrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    var place by remember { mutableStateOf(defaultPlace) }
    var note by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(60) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitle(stringResource(R.string.checkin_title))
        if (tripsEnabled) Segmented(listOf(stringResource(R.string.checkin_tab), stringResource(R.string.trip_tab)), tab, { tab = it })
        if (trip != null) Note(stringResource(R.string.trip_active, trip.destination, hhmm(trip.due)))
        if (tab == 0 || !tripsEnabled) {
            HTextField(place, { place = it.take(60) }, stringResource(R.string.checkin_place))
            HTextField(note, { note = it.take(140) }, stringResource(R.string.checkin_note))
            val keys = PeopleField(people, { people = it })
            val watchers = trip?.watchers.orEmpty()
            HButton(
                stringResource(R.string.checkin_send), { onCheckIn(place, keys.orEmpty(), note) }, Modifier.fillMaxWidth(), height = 52.dp,
                enabled = place.isNotBlank() && keys != null && (keys.isNotEmpty() || watchers.isNotEmpty()), icon = R.drawable.ic_check,
            )
            Note(stringResource(R.string.checkin_private))
        } else if (trip != null) {
            HButton(stringResource(R.string.trip_cancel), onCancelTrip, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
        } else {
            HTextField(destination, { destination = it.take(60) }, stringResource(R.string.trip_destination))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 240).forEach { m -> Chip(tripLabel(m), minutes == m, onClick = { minutes = m }) }
            }
            val keys = PeopleField(people, { people = it })
            Note(stringResource(R.string.trip_note))
            HButton(
                stringResource(R.string.trip_start), { onStartTrip(destination, minutes, keys.orEmpty()) }, Modifier.fillMaxWidth(), height = 52.dp,
                enabled = destination.isNotBlank() && !keys.isNullOrEmpty(),
            )
        }
    }
}

@Composable
private fun tripLabel(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.trip_minutes, minutes) else stringResource(R.string.trip_hours, minutes / 60)
