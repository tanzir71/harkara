package io.github.tanzir71.harkara.ui.chats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.chat.ChatRepository
import io.github.tanzir71.harkara.chat.SendResult
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.core.chat.ChatCard
import io.github.tanzir71.harkara.core.chat.LocationPrecision
import io.github.tanzir71.harkara.core.chat.LocationShare
import io.github.tanzir71.harkara.core.chat.VoiceClip
import io.github.tanzir71.harkara.core.chat.VoiceNotes
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.ui.PlatformActions
import io.github.tanzir71.harkara.ui.screens.PillActions
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ListSheet { None, NewChat }

private enum class ThreadSheet { None, Actions, Attach, Location, Name }

/** The Chats tab wired to the repository: rooms, conversations and the "New chat" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsRoute(
    container: AppContainer,
    platform: PlatformActions,
    connectivity: ConnectivityState,
    pill: PillActions,
    onOpen: (conversationId: String) -> Unit,
    say: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = container.chatRepository
    val context = LocalContext.current
    val presenter = rememberChatPresenter()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val me by container.identity.profilePubkey.collectAsStateWithLifecycle()
    val summaries by remember { repo.conversations() }.collectAsStateWithLifecycle(emptyList())
    val contacts by remember { repo.contacts() }.collectAsStateWithLifecycle(emptyList())
    val wifiPeers by container.lanRoom.peerCount.collectAsStateWithLifecycle()
    val center = remember(settings) { container.areaCenter(settings) }
    val area = remember(center) { GeoHash.encode(center.lat, center.lng, 5) }
    var sheet by remember { mutableStateOf(ListSheet.None) }

    val unreadOf = { id: String -> summaries.firstOrNull { it.conversation.id == id }?.conversation?.unread ?: 0 }
    val rows = summaries.filter { it.conversation.type == "DM" || it.conversation.type == "GROUP" }.map { presenter.row(it, me, settings.areaName) }
    ChatsListScreen(
        rooms = presenter.rooms(wifiPeers, settings.areaName, unreadOf(ChatRepository.WIFI_ID), unreadOf(ChatRepository.AREA_PREFIX + area)),
        conversations = rows,
        connectivity = connectivity,
        modifier = modifier,
        pill = pill,
        onOpenRoom = { kind ->
            // The room is made in the app scope (this screen leaves as the thread opens); its id is known now.
            container.scope.launch { if (kind == ChatKind.WIFI) repo.wifiRoom() else repo.areaChannel(area) }
            onOpen(if (kind == ChatKind.WIFI) ChatRepository.WIFI_ID else ChatRepository.AREA_PREFIX + area)
        },
        onOpen = onOpen,
        onNewChat = { sheet = ListSheet.NewChat },
    )

    if (sheet != ListSheet.None) {
        ModalBottomSheet(
            onDismissRequest = { sheet = ListSheet.None },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = HarkaraTheme.colors.bgRaised,
        ) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                val names = contacts.associate { it.pubkey to (it.petName ?: it.theirName ?: presenter.personName(it.pubkey, emptyMap())) }
                NewChatSheetContent(
                    npub = me?.let(Bech32::encodeNpub),
                    contacts = names.toList(),
                    onStartDm = { key, name ->
                        val id = repo.dmRoomId(key)
                        if (id != null) {
                            container.scope.launch { repo.startDm(key, name.ifBlank { null }) }
                            sheet = ListSheet.None
                            onOpen(id)
                        }
                        id != null
                    },
                    onCreateGroup = { name, members ->
                        repo.groupRoomId(members)?.let { id ->
                            container.scope.launch { repo.createGroup(name, members) }
                            sheet = ListSheet.None
                            onOpen(id)
                        }
                    },
                    onCopyKey = {
                        me?.let { platform.copyToClipboard("Harkara key", Bech32.encodeNpub(it)) }
                        say(context.getString(R.string.chats_key_copied))
                    },
                )
            }
        }
    }
}

/** One thread wired to the repository: sending, cards, voice notes, receipts and the long-press menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadRoute(
    container: AppContainer,
    platform: PlatformActions,
    conversationId: String,
    connectivity: ConnectivityState,
    pill: PillActions,
    onBack: () -> Unit,
    onMakeTask: (text: String) -> Unit,
    say: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = container.chatRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presenter = rememberChatPresenter()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val me by container.identity.profilePubkey.collectAsStateWithLifecycle()
    val conversation by remember(conversationId) { repo.conversation(conversationId) }.collectAsStateWithLifecycle(null)
    val thread by remember(conversationId) { repo.thread(conversationId) }.collectAsStateWithLifecycle(emptyList())
    val contacts by remember { repo.contacts() }.collectAsStateWithLifecycle(emptyList())
    val wifiPeers by container.lanRoom.peerCount.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(ThreadSheet.None) }
    var selected by remember { mutableStateOf<ThreadItemUi.Message?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var recordingSince by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var recorded by remember { mutableStateOf<VoiceClip?>(null) }

    BackHandler(onBack = onBack)
    LaunchedEffect(conversationId, thread.size) { repo.markRead(conversationId) }

    val c = conversation ?: return
    val names = contacts.mapNotNull { k -> (k.petName ?: k.theirName)?.let { k.pubkey to it } }.toMap()
    val header = presenter.header(c, names, me, settings.areaName, wifiPeers)
    val kind = header.kind
    val isPrivate = kind == ChatKind.DM || kind == ChatKind.GROUP
    val route = when (kind) {
        ChatKind.WIFI -> if (wifiPeers > 0) Carrier.NODE else Carrier.SAVED
        ChatKind.AREA -> if (Carrier.ONLINE in connectivity.available) Carrier.ONLINE else Carrier.SAVED
        else -> connectivity.best
    }

    fun report(result: SendResult) {
        val message = when (result) {
            SendResult.SENT -> null
            SendResult.EMPTY -> null
            SendResult.TOO_LONG -> R.string.thread_too_long
            SendResult.PHONE_NUMBER -> R.string.thread_phone
            SendResult.NO_CONVERSATION, SendResult.FAILED -> R.string.thread_failed
        }
        message?.let { say(context.getString(it)) }
    }

    // The name is asked once, the first time anything is sent (§4: no sign-up).
    fun withName(action: () -> Unit) {
        val prefs = repo.prefs
        if (prefs.displayName == null && !prefs.nameAsked) {
            pending = action
            sheet = ThreadSheet.Name
        } else {
            action()
        }
    }

    fun sendCard(card: ChatCard) = withName { container.scope.launch { report(repo.sendCard(conversationId, card)) } }

    LaunchedEffect(recordingSince) {
        if (recordingSince == 0L) return@LaunchedEffect
        while (recorded == null) {
            elapsed = System.currentTimeMillis() - recordingSince
            if (elapsed >= VoiceNotes.MAX_DURATION_MS) {
                recorded = container.voiceRecorder.stop()
                if (recorded == null) recordingSince = 0L
                break
            }
            delay(200)
        }
    }

    ChatThreadScreen(
        header = header,
        items = presenter.items(thread, kind),
        connectivity = connectivity,
        hint = presenter.hint(route),
        modifier = modifier,
        pill = pill,
        pillLabel = if (connectivity.best == Carrier.MESH) context.getString(R.string.pill_mesh_short, connectivity.meshPeers) else null,
        emptyText = context.getString(
            when (kind) {
                ChatKind.AREA -> R.string.thread_empty_area
                ChatKind.WIFI -> R.string.thread_empty_wifi
                else -> R.string.thread_empty_dm
            },
        ),
        canRecord = isPrivate,
        canAttach = isPrivate,
        recording = if (recordingSince != 0L) RecordingUi(ChatPresenter.duration(minOf(elapsed, VoiceNotes.MAX_DURATION_MS) / 1000), recorded != null) else null,
        onBack = onBack,
        onSend = { text -> withName { container.scope.launch { report(repo.sendText(conversationId, text)) } } },
        onAttach = { sheet = ThreadSheet.Attach },
        onMic = {
            platform.requestMicrophone { granted ->
                if (granted && container.voiceRecorder.start()) {
                    recorded = null
                    elapsed = 0
                    recordingSince = System.currentTimeMillis()
                } else {
                    say(context.getString(R.string.thread_mic_denied))
                }
            }
        },
        onRecordingSend = {
            val clip = recorded ?: container.voiceRecorder.stop()
            recordingSince = 0L
            recorded = null
            if (clip != null) withName { container.scope.launch { report(repo.sendVoice(conversationId, clip)) } }
        },
        onRecordingCancel = {
            container.voiceRecorder.cancel()
            recordingSince = 0L
            recorded = null
        },
        onLongPress = { item ->
            selected = item
            sheet = ThreadSheet.Actions
        },
        onPlay = { id -> scope.launch { repo.voiceClip(id)?.let(container.voicePlayer::play) } },
        inputModifier = Modifier.navigationBarsPadding().imePadding(),
    )

    if (sheet != ThreadSheet.None) {
        ModalBottomSheet(
            onDismissRequest = { sheet = ThreadSheet.None },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = HarkaraTheme.colors.bgRaised,
        ) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                when (sheet) {
                    ThreadSheet.Actions -> {
                        val item = selected
                        MessageActionsSheetContent(
                            onCopy = {
                                sheet = ThreadSheet.None
                                item?.let { platform.copyToClipboard("Harkara message", it.copyText) }
                                say(context.getString(R.string.message_copied))
                            },
                            onMakeTask = {
                                sheet = ThreadSheet.None
                                item?.let { onMakeTask(it.copyText) }
                            },
                            onBlock = item?.takeIf { !it.outgoing && it.author != null }?.let { target ->
                                {
                                    sheet = ThreadSheet.None
                                    container.scope.launch { repo.blockAuthor(target.author!!) }
                                }
                            },
                        )
                    }
                    ThreadSheet.Attach -> AttachSheetContent(
                        onLocation = { sheet = ThreadSheet.Location },
                        onCheckIn = {
                            sheet = ThreadSheet.None
                            val now = System.currentTimeMillis() / 1000
                            val detail = listOfNotNull(settings.areaName, presenter.clock(now)).joinToString(" · ")
                            sendCard(ChatCard.CheckIn(context.getString(R.string.checkin_safe), detail))
                        },
                    )
                    ThreadSheet.Location -> LocationShareSheetContent(onShare = { title, place, precision ->
                        sheet = ThreadSheet.None
                        platform.requestLocation { lat, lng ->
                            val point = when {
                                lat != null && lng != null -> lat to lng
                                precision == LocationPrecision.AREA -> container.areaCenter(settings).let { it.lat to it.lng }
                                else -> null
                            }
                            if (point == null) {
                                say(context.getString(R.string.location_unknown))
                            } else {
                                sendCard(LocationShare.card(point.first, point.second, precision, title, place))
                            }
                        }
                    })
                    ThreadSheet.Name -> NamePromptSheetContent(
                        onSave = { name ->
                            repo.prefs.displayName = name
                            repo.prefs.nameAsked = true
                            sheet = ThreadSheet.None
                            pending?.invoke()
                            pending = null
                        },
                        onSkip = {
                            repo.prefs.nameAsked = true
                            sheet = ThreadSheet.None
                            pending?.invoke()
                            pending = null
                        },
                    )
                    ThreadSheet.None -> Unit
                }
            }
        }
    }
}
