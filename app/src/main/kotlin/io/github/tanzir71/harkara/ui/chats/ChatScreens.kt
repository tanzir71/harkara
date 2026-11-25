package io.github.tanzir71.harkara.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.CheckInCard
import io.github.tanzir71.harkara.ui.components.ConnectivityPill
import io.github.tanzir71.harkara.ui.components.DeliveryTicks
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HFab
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.IouCard
import io.github.tanzir71.harkara.ui.components.LocationCard
import io.github.tanzir71.harkara.ui.components.MessageBubble
import io.github.tanzir71.harkara.ui.components.PinCard
import io.github.tanzir71.harkara.ui.components.SectionHeader
import io.github.tanzir71.harkara.ui.components.TaskCard
import io.github.tanzir71.harkara.ui.components.VoiceNote
import io.github.tanzir71.harkara.ui.components.carrierColor
import io.github.tanzir71.harkara.ui.components.carrierIcon
import io.github.tanzir71.harkara.ui.screens.CalmState
import io.github.tanzir71.harkara.ui.screens.PillActions
import io.github.tanzir71.harkara.ui.screens.RowDivider
import io.github.tanzir71.harkara.ui.screens.TitleBar
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

private val CardWidth = 264.dp
private val BubbleMax = 240.dp

/** The Chats tab: the Wi-Fi room and area channel on top, then DMs and groups, newest first. */
@Composable
fun ChatsListScreen(
    rooms: List<RoomRowUi>,
    conversations: List<ConversationRowUi>,
    connectivity: ConnectivityState,
    modifier: Modifier = Modifier,
    pill: PillActions = PillActions(),
    onOpenRoom: (ChatKind) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onNewChat: () -> Unit = {},
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { TitleBar(stringResource(R.string.tab_chats), connectivity, pill) }
            item { SectionHeader(stringResource(R.string.chats_rooms), Modifier.padding(horizontal = 16.dp)) }
            item {
                HCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(0.dp)) {
                    Column {
                        rooms.forEachIndexed { i, room ->
                            if (i > 0) RowDivider()
                            RoomRow(room, onClick = { onOpenRoom(room.kind) })
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.chats_messages), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
            if (conversations.isEmpty()) {
                item {
                    CalmState(R.drawable.ic_chats, stringResource(R.string.chats_none_title), stringResource(R.string.chats_none_body))
                }
            } else {
                item {
                    HCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(0.dp)) {
                        Column {
                            conversations.forEachIndexed { i, row ->
                                if (i > 0) RowDivider(Modifier.padding(start = 6.dp))
                                ConversationRow(row, onClick = { onOpen(row.id) })
                            }
                        }
                    }
                }
            }
        }
        HFab(
            R.drawable.ic_plus,
            stringResource(R.string.chats_new),
            onNewChat,
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            text = stringResource(R.string.chats_new),
        )
    }
}

@Composable
private fun RoomRow(room: RoomRowUi, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    val wifi = room.kind == ChatKind.WIFI
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
            HIcon(if (wifi) R.drawable.ic_node else R.drawable.ic_nearby, tint = if (wifi) c.stateNode else c.textPrimary, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(room.title, style = HarkaraTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(room.subtitle, style = HarkaraTheme.type.caption, color = c.textSecondary, maxLines = 2)
        }
        if (room.unread > 0) UnreadBadge(room.unread)
        Spacer(Modifier.width(6.dp))
        HIcon(R.drawable.ic_chevron_right, tint = c.textTertiary, size = 18.dp)
    }
}

@Composable
private fun ConversationRow(row: ConversationRowUi, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.initials)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.kind == ChatKind.GROUP) {
                    HIcon(R.drawable.ic_group, tint = c.textSecondary, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    row.title,
                    style = HarkaraTheme.type.bodyStrong.copy(fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Medium),
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(row.time, style = HarkaraTheme.type.micro, color = if (row.unread > 0) c.accentText else c.textTertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.preview, style = HarkaraTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (row.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(row.unread)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val c = HarkaraTheme.colors
    val label = stringResource(R.string.chats_unread, count)
    Box(
        Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(c.accentFill)
            .semantics { contentDescription = label }.padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), style = HarkaraTheme.type.micro, color = c.textOnAccent)
    }
}

/** Screen 04: one chat with bubbles, cards, system lines and the input bar. */
@Composable
fun ChatThreadScreen(
    header: ThreadHeaderUi,
    items: List<ThreadItemUi>,
    connectivity: ConnectivityState,
    hint: String,
    modifier: Modifier = Modifier,
    pill: PillActions = PillActions(),
    pillLabel: String? = null,
    emptyText: String? = null,
    canRecord: Boolean = true,
    canAttach: Boolean = true,
    recording: RecordingUi? = null,
    onBack: () -> Unit = {},
    onSend: (String) -> Unit = {},
    onAttach: () -> Unit = {},
    onMic: () -> Unit = {},
    onRecordingSend: () -> Unit = {},
    onRecordingCancel: () -> Unit = {},
    onLongPress: (ThreadItemUi.Message) -> Unit = {},
    onPlay: (String) -> Unit = {},
    inputModifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxSize().background(c.bgCanvas)) {
        ThreadHeader(header, connectivity, pill, pillLabel, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty() && emptyText != null) {
                Text(
                    emptyText,
                    style = HarkaraTheme.type.caption,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 40.dp, vertical = 24.dp),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                items(items.asReversed(), key = { it.id }) { item ->
                    when (item) {
                        is ThreadItemUi.System -> SystemLine(item.text)
                        is ThreadItemUi.Message -> MessageRow(item, onLongPress, onPlay)
                    }
                }
            }
        }
        InputBar(hint, canRecord, canAttach, recording, onSend, onAttach, onMic, onRecordingSend, onRecordingCancel, inputModifier)
    }
}

@Composable
private fun ThreadHeader(header: ThreadHeaderUi, connectivity: ConnectivityState, pill: PillActions, pillLabel: String?, onBack: () -> Unit) {
    val c = HarkaraTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgCanvas).padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 40.dp, height = 48.dp).clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            HIcon(R.drawable.ic_arrow_left, size = 22.dp, contentDescription = stringResource(R.string.back))
        }
        Avatar(header.initials, size = 40.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                header.title,
                style = HarkaraTheme.type.headline.copy(fontSize = Tokens.Text.headline.sizeSp * 1.06f),
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(header.subtitle, style = HarkaraTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        ConnectivityPill(
            connectivity.best,
            meshPeers = connectivity.meshPeers,
            onClick = pill.onClick,
            onLongClick = pill.onLongClick,
            labelOverride = pillLabel,
            compact = true,
        )
    }
}

@Composable
private fun SystemLine(text: String) {
    val c = HarkaraTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        HIcon(R.drawable.ic_tasks, tint = c.textTertiary, size = 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = HarkaraTheme.type.micro, color = c.textSecondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(item: ThreadItemUi.Message, onLongPress: (ThreadItemUi.Message) -> Unit, onPlay: (String) -> Unit) {
    val c = HarkaraTheme.colors
    val press = Modifier.combinedClickable(onClick = {}, onLongClick = { onLongPress(item) })
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (item.outgoing) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (item.sender != null && !item.outgoing) {
            Text(item.sender, style = HarkaraTheme.type.label, color = c.textSecondary, maxLines = 1)
        }
        when (val body = item.body) {
            is ChatBody.Text -> MessageBubble(
                body.text, item.time, item.outgoing, item.carrier,
                Modifier.widthIn(max = BubbleMax).clip(RoundedCornerShape(Tokens.Radius.lg)).then(press),
                delivery = item.delivery,
            )
            is ChatBody.Voice -> {
                VoiceNote(body.duration, press, progress = 0f, onPlay = { onPlay(item.id) })
                Meta(item)
            }
            is ChatBody.Location -> {
                LocationCard(body.title, body.detail, body.plusCode, Modifier.width(CardWidth).then(press))
                Meta(item)
            }
            is ChatBody.Task -> {
                TaskCard(body.task, Modifier.width(CardWidth).then(press))
                Meta(item)
            }
            is ChatBody.Iou -> {
                IouCard(body.iou, Modifier.width(CardWidth).then(press))
                Meta(item)
            }
            is ChatBody.CheckIn -> {
                CheckInCard(body.title, body.detail, item.carrier, body.carrierLabel, Modifier.width(CardWidth).then(press))
                Meta(item)
            }
            is ChatBody.Pin -> {
                PinCard(body.pin, Modifier.width(CardWidth).then(press))
                Meta(item)
            }
        }
    }
}

/** Carrier glyph, time and ticks under cards and voice notes (bubbles carry their own). */
@Composable
private fun Meta(item: ThreadItemUi.Message) {
    if (item.time.isEmpty()) return
    val c = HarkaraTheme.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HIcon(carrierIcon(item.carrier), tint = c.carrierColor(item.carrier), size = 12.dp)
        Text(item.time, style = HarkaraTheme.type.micro, color = c.textSecondary)
        if (item.outgoing && item.delivery != null) DeliveryTicks(item.delivery)
    }
}

@Composable
private fun InputBar(
    hint: String,
    canRecord: Boolean,
    canAttach: Boolean,
    recording: RecordingUi?,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onRecordingSend: () -> Unit,
    onRecordingCancel: () -> Unit,
    modifier: Modifier,
) {
    val c = HarkaraTheme.colors
    var text by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().background(c.bgCanvas)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recording != null) {
                RoundButton(R.drawable.ic_close, stringResource(R.string.thread_cancel_recording), onRecordingCancel, filled = false)
                Row(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(CircleShape).background(c.bgSunken).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (recording.done) c.textTertiary else c.stateSos))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (recording.done) stringResource(R.string.thread_recorded, recording.elapsed) else stringResource(R.string.thread_recording, recording.elapsed),
                        style = HarkaraTheme.type.body,
                        color = c.textPrimary,
                        maxLines = 1,
                    )
                }
                RoundButton(R.drawable.ic_send, stringResource(R.string.thread_send), onRecordingSend, filled = true)
            } else {
                if (canAttach) RoundButton(R.drawable.ic_plus, stringResource(R.string.thread_attach), onAttach, filled = false)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = HarkaraTheme.type.body.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.textPrimary),
                    maxLines = 5,
                    modifier = Modifier.weight(1f).semantics { contentDescription = hint },
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(c.bgSunken)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) Text(hint, style = HarkaraTheme.type.body, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                if (text.isNotBlank() || !canRecord) {
                    RoundButton(R.drawable.ic_send, stringResource(R.string.thread_send), {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    }, filled = true)
                } else {
                    RoundButton(R.drawable.ic_mic, stringResource(R.string.thread_record), onMic, filled = true)
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: Int, description: String, onClick: () -> Unit, filled: Boolean) {
    val c = HarkaraTheme.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (filled) c.bgInverse else c.bgSurface)
            .then(if (filled) Modifier else Modifier.border(1.dp, c.borderDefault, CircleShape))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        HIcon(icon, tint = if (filled) c.textInverse else c.textPrimary, size = 20.dp)
    }
}
