package io.github.tanzir71.harkara.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.chat.ChatRepository
import io.github.tanzir71.harkara.core.chat.ChatProtocol
import io.github.tanzir71.harkara.core.chat.ChatVerdict
import io.github.tanzir71.harkara.core.chat.LocationPrecision
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.identity.shortNpub
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.components.QrCode
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.screens.RowDivider
import io.github.tanzir71.harkara.ui.screens.SettingsRow
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

/** "New chat": message someone by key, or make a group from people you already chat with. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewChatSheetContent(
    npub: String?,
    contacts: List<Pair<String, String>>,
    onStartDm: (key: String, name: String) -> Boolean,
    onCreateGroup: (name: String, members: List<String>) -> Unit,
    onCopyKey: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    initialQr: Boolean = false,
) {
    val c = HarkaraTheme.colors
    var tab by remember { mutableIntStateOf(initialTab) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.chats_new), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Segmented(listOf(stringResource(R.string.chats_new_dm), stringResource(R.string.chats_new_group)), tab, { tab = it })
        if (tab == 0) {
            var key by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            var bad by remember { mutableStateOf(false) }
            var showQr by remember { mutableStateOf(initialQr) }
            HTextField(key, { key = it; bad = false }, stringResource(R.string.chats_their_key), mono = true, error = if (bad) stringResource(R.string.chats_bad_key) else null)
            HTextField(name, { name = it }, stringResource(R.string.chats_pet_name), imeAction = ImeAction.Done)
            HButton(stringResource(R.string.chats_start), { bad = !onStartDm(key, name) }, Modifier.fillMaxWidth(), enabled = key.isNotBlank())
            if (npub != null) {
                HCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.chats_your_key), style = HarkaraTheme.type.headline, color = c.textPrimary)
                        Text(stringResource(R.string.chats_your_key_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
                        Text(npub, style = HarkaraTheme.type.mono, color = c.textPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HButton(stringResource(R.string.chats_copy_key), onCopyKey, variant = HButtonVariant.Secondary, height = 40.dp, icon = R.drawable.ic_list)
                            HButton(stringResource(R.string.chats_show_qr), { showQr = !showQr }, variant = HButtonVariant.Ghost, height = 40.dp, icon = R.drawable.ic_qr)
                        }
                        if (showQr) QrCode(npub, stringResource(R.string.chats_key_qr_description), Modifier.width(220.dp).align(Alignment.CenterHorizontally))
                    }
                }
            }
        } else {
            var groupName by remember { mutableStateOf("") }
            var key by remember { mutableStateOf("") }
            var bad by remember { mutableStateOf(false) }
            val people = remember { mutableStateListOf<Pair<String, String>>().also { it.addAll(contacts) } }
            val selected = remember { mutableStateListOf<String>() }
            HTextField(groupName, { groupName = it }, stringResource(R.string.chats_group_name))
            Text(stringResource(R.string.chats_group_people), style = HarkaraTheme.type.label, color = c.textSecondary)
            if (people.isEmpty()) {
                Text(stringResource(R.string.chats_group_none), style = HarkaraTheme.type.caption, color = c.textSecondary)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { (pubkey, label) ->
                        Chip(label, selected = pubkey in selected, onClick = { if (pubkey in selected) selected.remove(pubkey) else selected.add(pubkey) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HTextField(
                    key, { key = it; bad = false }, stringResource(R.string.chats_group_add_key), Modifier.weight(1f), mono = true,
                    error = if (bad) stringResource(R.string.chats_bad_key) else null,
                )
                HButton(stringResource(R.string.chats_group_add), {
                    val pubkey = ChatRepository.parsePubkey(key)
                    if (pubkey == null) {
                        bad = true
                    } else {
                        if (people.none { it.first == pubkey }) people.add(pubkey to shortNpub(Bech32.encodeNpub(pubkey)))
                        if (pubkey !in selected) selected.add(pubkey)
                        key = ""
                    }
                }, variant = HButtonVariant.Secondary, enabled = key.isNotBlank())
            }
            Text(stringResource(R.string.chats_group_hint), style = HarkaraTheme.type.caption, color = c.textSecondary)
            HButton(
                stringResource(R.string.chats_group_create),
                { onCreateGroup(groupName.trim(), selected.toList()) },
                Modifier.fillMaxWidth(),
                enabled = groupName.isNotBlank() && selected.size >= 2,
            )
        }
    }
}

/** Long-press on a message: copy, make it a task, block the sender. */
@Composable
fun MessageActionsSheetContent(
    onCopy: () -> Unit,
    onMakeTask: () -> Unit,
    onBlock: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.message_actions), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
            Column {
                SettingsRow(R.drawable.ic_list, stringResource(R.string.message_copy), onClick = onCopy)
                RowDivider()
                SettingsRow(R.drawable.ic_tasks, stringResource(R.string.message_make_task), subtitle = stringResource(R.string.message_make_task_body), onClick = onMakeTask)
                if (onBlock != null) {
                    RowDivider()
                    SettingsRow(R.drawable.ic_close, stringResource(R.string.message_block), subtitle = stringResource(R.string.message_block_body), onClick = onBlock)
                }
            }
        }
    }
}

/** The + menu: share a location card or an "I'm safe" check-in. */
@Composable
fun AttachSheetContent(onLocation: () -> Unit, onCheckIn: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.attach_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
            Column {
                SettingsRow(R.drawable.ic_pin, stringResource(R.string.attach_location), subtitle = stringResource(R.string.attach_location_body), onClick = onLocation)
                RowDivider()
                SettingsRow(R.drawable.ic_check, stringResource(R.string.attach_checkin), subtitle = stringResource(R.string.attach_checkin_body), onClick = onCheckIn)
            }
        }
    }
}

/** One-off location card: a title, an optional place note and how precise to be. */
@Composable
fun LocationShareSheetContent(onShare: (title: String, place: String, precision: LocationPrecision) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val defaultTitle = stringResource(R.string.location_default_title)
    var title by remember { mutableStateOf(defaultTitle) }
    var place by remember { mutableStateOf("") }
    var precision by remember { mutableIntStateOf(1) }
    val options = listOf(LocationPrecision.EXACT, LocationPrecision.APPROX, LocationPrecision.AREA)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.attach_location), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.attach_location_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HTextField(title, { title = it }, stringResource(R.string.location_title_hint))
        HTextField(place, { place = it }, stringResource(R.string.location_place_hint), imeAction = ImeAction.Done)
        Segmented(
            listOf(stringResource(R.string.location_exact), stringResource(R.string.location_100m), stringResource(R.string.location_area)),
            precision,
            { precision = it },
        )
        HButton(
            stringResource(R.string.location_share),
            { onShare(title.trim().ifEmpty { defaultTitle }, place.trim(), options[precision]) },
            Modifier.fillMaxWidth().padding(top = 4.dp),
            icon = R.drawable.ic_pin,
        )
    }
}

/** Asked once, the first time you send: a name for the people you message, or none. */
@Composable
fun NamePromptSheetContent(onSave: (String) -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var name by remember { mutableStateOf("") }
    val verdict = ChatProtocol.checkName(name)
    val error = when {
        name.isBlank() -> null
        verdict == ChatVerdict.PHONE_NUMBER -> stringResource(R.string.name_phone)
        verdict == ChatVerdict.TOO_LONG -> stringResource(R.string.name_long)
        else -> null
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.name_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.name_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HTextField(name, { name = it }, stringResource(R.string.name_hint), error = error, imeAction = ImeAction.Done)
        HButton(stringResource(R.string.name_save), { onSave(name.trim()) }, Modifier.fillMaxWidth(), enabled = verdict == ChatVerdict.OK)
        HButton(stringResource(R.string.name_skip), onSkip, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost)
    }
}
