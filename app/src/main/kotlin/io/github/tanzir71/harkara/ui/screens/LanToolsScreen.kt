package io.github.tanzir71.harkara.ui.screens

import kotlinx.coroutines.CoroutineScope

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.tools.LocalSendDevice
import io.github.tanzir71.harkara.core.tools.LocalSendFile
import io.github.tanzir71.harkara.core.tools.LocalSendLimits
import io.github.tanzir71.harkara.core.tools.LocalSendOffer
import io.github.tanzir71.harkara.core.tools.LocalSendReceived
import io.github.tanzir71.harkara.core.tools.LocalSendServer
import io.github.tanzir71.harkara.core.tools.PttReceiver
import io.github.tanzir71.harkara.tools.lan.AndroidPttMicrophone
import io.github.tanzir71.harkara.tools.lan.AndroidPttSpeaker
import io.github.tanzir71.harkara.tools.lan.HttpPttTransport
import io.github.tanzir71.harkara.tools.lan.LanPushToTalk
import io.github.tanzir71.harkara.tools.lan.LanToolPeer
import io.github.tanzir71.harkara.tools.lan.LocalSendClient
import io.github.tanzir71.harkara.tools.lan.OutgoingLanFile
import io.github.tanzir71.harkara.ui.PlatformActions
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Numeric IPv4 only, deliberately without a DNS lookup or arbitrary URL input. */
internal fun parseLanToolsPeer(address: String, port: String): LanToolPeer? = runCatching {
    val parts = address.trim().split('.')
    require(parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } })
    val octets = parts.map { it.toInt().also { n -> require(n in 0..255) }.toByte() }.toByteArray()
    LanToolPeer(InetAddress.getByAddress(octets), port.toInt())
}.getOrNull()

internal data class LanToolsConsent(val offer: LocalSendOffer, val decision: CompletableFuture<Set<String>>)
internal data class LanToolsInboxFile(val metadata: LocalSendFile, val file: File)
internal data class LanToolsUiState(
    val port: Int = -1,
    val addresses: String = "",
    val token: String = "",
    val offers: List<LanToolsConsent> = emptyList(),
    val inbox: List<LanToolsInboxFile> = emptyList(),
    val busy: Boolean = false,
    val talking: Boolean = false,
    val message: Int = 0,
)

/** Screen-owned resources. Construction is inert; even returning to foreground never starts a server. */
internal class LanToolsSession(private val context: Context) {
    val state = MutableStateFlow(LanToolsUiState())
    private val directory get() = File(context.filesDir, "lan-tools-inbox")
    private val self = LocalSendDevice(context.getString(R.string.lan_tools_alias), fingerprint = UUID.randomUUID().toString())
    private var foreground = false
    private var generation = 0L
    private var server: LocalSendServer? = null
    private var receiver: PttReceiver? = null
    private var speaker: AndroidPttSpeaker? = null
    private var sender: LanPushToTalk? = null
    private var client: LocalSendClient? = null

    @Synchronized fun foreground(value: Boolean) {
        foreground = value
        if (!value) stop()
    }

    @Synchronized fun generation(): Long = generation

    @Synchronized fun start(ptt: Boolean, peer: LanToolPeer?, port: Int, expectedGeneration: Long = generation()) {
        if (!foreground || server != null || expectedGeneration != generation) return
        require(port in 0..65535 && (!ptt || peer != null))
        var candidate: LocalSendServer? = null
        try {
            loadInbox()
            val audio = if (ptt) AndroidPttSpeaker().also { speaker = it } else null
            val voice = audio?.let { PttReceiver(it::play).also { receiver = it } }
            val token = voice?.enable(peer!!.address.hostAddress!!) ?: ""
            candidate = LocalSendServer(self, directory, consent = { offer ->
                if (ptt) CompletableFuture.completedFuture(emptySet()) else if (
                    state.value.inbox.size + offer.files.size > 32 ||
                    state.value.inbox.sumOf { it.metadata.size } + offer.files.values.sumOf { it.size } > 100L * 1024 * 1024
                ) {
                    state.update { it.copy(message = R.string.lan_tools_inbox_full) }
                    CompletableFuture.completedFuture(emptySet())
                } else {
                    val decision = CompletableFuture<Set<String>>()
                    val pending = LanToolsConsent(offer, decision)
                    state.update { it.copy(offers = it.offers + pending) }
                    decision.whenComplete { _, _ -> state.update { it.copy(offers = it.offers - pending) } }
                    decision
                }
            }, onReceived = ::received, ptt = voice)
            val bound = candidate.start(port = port)
            server = candidate
            val addresses = runCatching {
                NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                    .filter { it is Inet4Address && !it.isLoopbackAddress && (it.isSiteLocalAddress || it.isLinkLocalAddress) }
                    .mapNotNull { it.hostAddress }.distinct().joinToString(", ")
            }.getOrDefault("")
            state.update { it.copy(port = bound, token = token, addresses = addresses, message = 0) }
        } catch (e: Exception) {
            candidate?.close()
            receiver?.disable()
            speaker?.close()
            receiver = null
            speaker = null
            state.update { it.copy(message = R.string.lan_tools_start_failed) }
        }
    }

    @Synchronized fun stop() {
        generation++
        sender?.close()
        sender = null
        client?.close()
        client = null
        server?.close()
        server = null
        receiver?.disable()
        receiver = null
        speaker?.close()
        speaker = null
        state.value.offers.forEach { it.decision.complete(emptySet()) }
        state.update { it.copy(port = -1, token = "", offers = emptyList(), busy = false, talking = false) }
    }

    @Synchronized fun press(peer: LanToolPeer, token: String): Boolean {
        if (!foreground || server == null || receiver == null || token.length !in 16..128) return false
        sender?.close()
        val hold = LanPushToTalk({ AndroidPttMicrophone(context) }, { HttpPttTransport(peer, token) }, {
            state.update { it.copy(talking = false, message = R.string.lan_tools_talk_failed) }
        })
        sender = hold
        hold.enable()
        return hold.press().also { started -> state.update { it.copy(talking = started) } }
    }

    @Synchronized fun release() {
        sender?.close()
        sender = null
        state.update { it.copy(talking = false) }
    }

    /** A bounded equivalent of holding the control for switch access and screen readers. */
    @Synchronized fun speakBriefly(peer: LanToolPeer, token: String, scope: CoroutineScope): Boolean {
        if (!press(peer, token)) return false
        val active = sender
        scope.launch {
            try { kotlinx.coroutines.delay(2_000) }
            finally { synchronized(this@LanToolsSession) { if (sender === active) release() } }
        }
        return true
    }

    fun send(peer: LanToolPeer, file: OutgoingLanFile, expectedGeneration: Long = generation()) {
        val outgoing = synchronized(this) {
            if (!foreground || client != null || expectedGeneration != generation) return
            LocalSendClient().also { client = it; state.update { s -> s.copy(busy = true, message = 0) } }
        }
        try {
            val result = outgoing.send(peer, self, listOf(file))
            state.update { it.copy(message = if (result.sent.isNotEmpty()) R.string.lan_tools_sent else R.string.lan_tools_declined) }
        } catch (_: Exception) {
            state.update { it.copy(message = R.string.lan_tools_send_failed) }
        } finally {
            outgoing.close()
            synchronized(this) { if (client === outgoing) { client = null; state.update { it.copy(busy = false) } } }
        }
    }

    /** Private metadata sidecars preserve the visible inbox across screen stops and recreation. */
    @Synchronized fun loadInbox() {
        val files = directory.listFiles().orEmpty().filter { it.name.matches(Regex("drop-[A-Za-z0-9_-]+\\.part\\.json")) }.take(32)
        val inbox = files.mapNotNull { sidecar -> runCatching {
            require(sidecar.length() <= 4096)
            val metadata = LocalSendServer.wireJson.decodeFromString<LocalSendFile>(sidecar.readText())
            LocalSendLimits().validate(mapOf(metadata.id to metadata))
            val file = File(directory, sidecar.name.removeSuffix(".json"))
            require(file.isFile && file.length() == metadata.size)
            LanToolsInboxFile(metadata, file)
        }.getOrNull() }
        state.update { it.copy(inbox = inbox) }
    }

    @Synchronized private fun received(received: LocalSendReceived) {
        val current = state.value.inbox
        check(current.size < 32 && current.sumOf { it.metadata.size } + received.metadata.size <= 100L * 1024 * 1024)
        File(received.file.path + ".json").writeText(LocalSendServer.wireJson.encodeToString(received.metadata))
        state.update { it.copy(inbox = it.inbox + LanToolsInboxFile(received.metadata, received.file), message = R.string.lan_tools_received) }
    }

    @Synchronized fun delete(file: LanToolsInboxFile) {
        if (file.file.delete() || !file.file.exists()) {
            File(file.file.path + ".json").delete()
            state.update { it.copy(inbox = it.inbox - file) }
        } else state.update { it.copy(message = R.string.lan_tools_file_failed) }
    }

    fun message(value: Int) { state.update { it.copy(message = value) } }
}

/** Standalone optional LAN tool. Parent must only navigate here while the corresponding module is on. */
@Composable
fun LanToolsScreen(ptt: Boolean, platform: PlatformActions, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val session = remember(context, ptt) { LanToolsSession(context.applicationContext) }
    val state by session.state.collectAsState()
    var address by remember { mutableStateOf("") }
    var peerPort by remember { mutableStateOf("53317") }
    var listenPort by remember { mutableStateOf("53317") }
    var remoteToken by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<OutgoingLanFile?>(null) }
    var saving by remember { mutableStateOf<LanToolsInboxFile?>(null) }
    var microphoneAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val peer = parseLanToolsPeer(address, peerPort)
    val c = HarkaraTheme.colors
    val running = state.port >= 0

    DisposableEffect(session, lifecycle) {
        session.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.foreground(false)
            if (event == Lifecycle.Event.ON_START) session.foreground(true)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.foreground(false) }
    }
    LaunchedEffect(session) { withContext(Dispatchers.IO) { session.loadInbox() } }
    LaunchedEffect(state.talking) { if (state.talking) { delay(15_000); session.release() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            selected = withContext(Dispatchers.IO) { runCatching {
                val metadata = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { cursor ->
                    check(cursor.moveToFirst())
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    val sizeColumn = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                    check(!cursor.isNull(sizeColumn))
                    LocalSendFile(UUID.randomUUID().toString(), name, cursor.getLong(sizeColumn), context.contentResolver.getType(uri) ?: "application/octet-stream")
                }
                LocalSendLimits().validate(mapOf(metadata.id to metadata))
                OutgoingLanFile(metadata) { context.contentResolver.openInputStream(uri) ?: error("Document unavailable") }
            }.getOrNull() }
            if (selected == null) session.message(R.string.lan_tools_file_failed)
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = saving
        saving = null
        if (uri != null && file != null) scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output -> file.file.inputStream().use { it.copyTo(output, 16 * 1024) } }
            }.isSuccess }
            session.message(if (success) R.string.lan_tools_saved else R.string.lan_tools_file_failed)
        }
    }

    Column(modifier.fillMaxSize()) {
        BackBar(stringResource(if (ptt) R.string.lan_tools_ptt_title else R.string.lan_tools_drop_title), { session.stop(); onBack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lan_tools_warning), style = HarkaraTheme.type.body, color = c.textSecondary)
            Text(stringResource(R.string.lan_tools_off_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
            HTextField(address, { if (!running) address = it.take(15) }, stringResource(R.string.lan_tools_peer_address), mono = true)
            HTextField(peerPort, { if (!running) peerPort = it.take(5) }, stringResource(R.string.lan_tools_peer_port), mono = true)
            HTextField(listenPort, { if (!running) listenPort = it.take(5) }, stringResource(R.string.lan_tools_listen_port), mono = true)
            if (running) {
                Text(stringResource(R.string.lan_tools_listening, state.addresses.ifEmpty { stringResource(R.string.lan_tools_no_address) }, state.port), style = HarkaraTheme.type.body, color = c.textPrimary)
                HButton(stringResource(R.string.lan_tools_stop), { session.stop() }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
            } else {
                Text(stringResource(R.string.lan_tools_stopped), style = HarkaraTheme.type.caption, color = c.textSecondary)
                HButton(stringResource(R.string.lan_tools_start), {
                    val port = listenPort.toIntOrNull()
                    if (port == null || port !in 0..65535 || (ptt && peer == null)) session.message(R.string.lan_tools_peer_invalid)
                    else {
                        val generation = session.generation()
                        scope.launch { withContext(Dispatchers.IO) { session.start(ptt, peer, port, generation) } }
                    }
                }, Modifier.fillMaxWidth())
            }
            if (state.message != 0) Text(stringResource(state.message), style = HarkaraTheme.type.body, color = c.textPrimary)

            if (ptt) {
                if (state.token.isNotEmpty()) {
                    Text(stringResource(R.string.lan_tools_pairing), style = HarkaraTheme.type.caption, color = c.textSecondary)
                    Text(state.token, style = HarkaraTheme.type.mono, color = c.textPrimary)
                    val label = stringResource(R.string.lan_tools_token_label)
                    HButton(stringResource(R.string.lan_tools_copy_token), { platform.copyToClipboard(label, state.token) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
                }
                HTextField(remoteToken, { session.release(); remoteToken = it.trim().take(128) }, stringResource(R.string.lan_tools_remote_token), mono = true)
                if (!microphoneAllowed) HButton(stringResource(R.string.lan_tools_allow_mic), {
                    platform.requestMicrophone { granted -> microphoneAllowed = granted; if (!granted) session.message(R.string.lan_tools_mic_denied) }
                }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
                val ready = running && microphoneAllowed && peer != null && remoteToken.length in 16..128
                val holdLabel = stringResource(R.string.lan_tools_hold)
                val talkState = stringResource(if (state.talking) R.string.lan_tools_talking else R.string.lan_tools_silent)
                val accessibilityAction = stringResource(if (state.talking) R.string.lan_tools_stop_talking else R.string.lan_tools_speak_briefly)
                Box(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .background(if (ready) c.accentFill else c.bgSunken, RoundedCornerShape(Tokens.Radius.md))
                    .semantics {
                        role = Role.Button; contentDescription = holdLabel; stateDescription = talkState
                        if (!ready) disabled()
                        onClick(accessibilityAction) {
                            if (state.talking) { session.release(); true }
                            else ready && session.speakBriefly(peer!!, remoteToken, scope)
                        }
                    }
                    .onFocusChanged { if (!it.isFocused) session.release() }
                    .onKeyEvent { event ->
                        if (event.key != Key.Spacebar && event.key != Key.Enter) false
                        else {
                            if (event.type == KeyEventType.KeyUp) session.release()
                            else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && ready && !state.talking) session.press(peer!!, remoteToken)
                            true
                        }
                    }.focusable(ready)
                    .pointerInput(ready, peer, remoteToken) {
                        detectTapGestures(onPress = {
                            if (ready && session.press(peer!!, remoteToken)) {
                                try { tryAwaitRelease() } finally { session.release() }
                            }
                        })
                    }.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(if (state.talking) talkState else holdLabel, style = HarkaraTheme.type.bodyStrong, color = if (ready) c.textOnAccent else c.textSecondary)
                }
                Text(stringResource(R.string.lan_tools_hold_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
            } else {
                HButton(stringResource(R.string.lan_tools_choose), { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, enabled = !state.busy)
                selected?.let { file ->
                    Text(stringResource(R.string.lan_tools_file, file.metadata.fileName, file.metadata.size), style = HarkaraTheme.type.body, color = c.textPrimary)
                    HButton(stringResource(if (state.busy) R.string.lan_tools_sending else R.string.lan_tools_send), {
                        if (peer == null) session.message(R.string.lan_tools_peer_invalid)
                        else {
                            val generation = session.generation()
                            scope.launch { withContext(Dispatchers.IO) { session.send(peer, file, generation) } }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !state.busy)
                }
                state.offers.forEach { pending ->
                    HCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.lan_tools_offer, pending.offer.sender.alias, pending.offer.remoteAddress), style = HarkaraTheme.type.headline, color = c.textPrimary)
                            Text(stringResource(R.string.lan_tools_consent_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
                            pending.offer.files.values.forEach { file -> Text(stringResource(R.string.lan_tools_file, file.fileName, file.size), style = HarkaraTheme.type.body, color = c.textPrimary) }
                            HButton(stringResource(R.string.lan_tools_accept), { pending.decision.complete(pending.offer.files.keys.toSet()) }, Modifier.fillMaxWidth())
                            HButton(stringResource(R.string.lan_tools_reject), { pending.decision.complete(emptySet()) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
                        }
                    }
                }
                Text(stringResource(R.string.lan_tools_inbox), style = HarkaraTheme.type.headline, color = c.textPrimary)
                Text(stringResource(R.string.lan_tools_inbox_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
                state.inbox.forEach { file ->
                    HCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.lan_tools_file, file.metadata.fileName, file.metadata.size), style = HarkaraTheme.type.body, color = c.textPrimary)
                            HButton(stringResource(R.string.lan_tools_save), { saving = file; saver.launch(file.metadata.fileName) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary)
                            HButton(stringResource(R.string.lan_tools_delete), { scope.launch { withContext(Dispatchers.IO) { session.delete(file) } } }, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost)
                        }
                    }
                }
            }
        }
    }
}
