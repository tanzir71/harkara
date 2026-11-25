package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.BuildConfig
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.ui.chats.ChatThreadRoute
import io.github.tanzir71.harkara.ui.chats.ChatsRoute
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.screens.BackupSheet
import io.github.tanzir71.harkara.ui.screens.RestoreSheet
import io.github.tanzir71.harkara.core.feed.ComposeVerdict
import io.github.tanzir71.harkara.feed.rememberFeedPresenter
import io.github.tanzir71.harkara.relay.RelaySyncWorker
import io.github.tanzir71.harkara.ui.screens.CardActionsSheetContent
import io.github.tanzir71.harkara.ui.screens.ComposeSheetContent
import io.github.tanzir71.harkara.ui.screens.RelaysScreen
import io.github.tanzir71.harkara.ui.screens.ConnectivitySheetContent
import io.github.tanzir71.harkara.ui.screens.KhataScreen
import io.github.tanzir71.harkara.ui.screens.toRow
import io.github.tanzir71.harkara.ui.screens.MeScreen
import io.github.tanzir71.harkara.ui.screens.ModulesScreen
import io.github.tanzir71.harkara.ui.screens.NearbyScreen
import io.github.tanzir71.harkara.ui.screens.OnboardingScreen
import io.github.tanzir71.harkara.ui.screens.PanicWipeSheetContent
import io.github.tanzir71.harkara.ui.screens.PillActions
import io.github.tanzir71.harkara.ui.screens.SettingsScreen
import io.github.tanzir71.harkara.ui.screens.TasksScreen
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.links.HarkaraLinks
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.sms.GatewayConfig
import io.github.tanzir71.harkara.core.wire.QrTransport
import io.github.tanzir71.harkara.links.DeepLinkRouter
import io.github.tanzir71.harkara.links.LinkInbox
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.sms.GreyAreaSms
import io.github.tanzir71.harkara.sms.SmsComposer
import io.github.tanzir71.harkara.ui.screens.EventQrSheetContent
import io.github.tanzir71.harkara.ui.screens.GatewayCardSheetContent
import io.github.tanzir71.harkara.ui.screens.GreyAreaSheetContent
import io.github.tanzir71.harkara.ui.screens.PasteLinkSheetContent
import io.github.tanzir71.harkara.ui.screens.RestrictedSettingsScreen
import io.github.tanzir71.harkara.ui.screens.SendSmsSheetContent
import io.github.tanzir71.harkara.ui.screens.SmsGatewayScreen
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Things only the Android host can do; faked in tests. */
interface PlatformActions {
    fun afterPanicWipe() {}
    fun openUrl(url: String)
    fun shareText(text: String)
    fun copyToClipboard(label: String, text: String)
    /** Asks for coarse location once; reports the last known fix, or null if denied/unknown. */
    fun requestLocation(onResult: (lat: Double?, lng: Double?) -> Unit)
    /** Re-applies the language override (recreates the activity). */
    fun applyLanguage()
    /** Lets the user pick where to save [text] (Storage Access Framework); reports whether it was written. */
    fun saveTextDocument(suggestedName: String, text: String, onResult: (saved: Boolean) -> Unit)
    /** Lets the user pick a small text file to open; reports its text, or null when cancelled or unreadable. */
    fun openTextDocument(onResult: (text: String?) -> Unit)
    /** Opens the SMS app with [body] prefilled and no recipient; needs no permission and stores no number. */
    fun composeSms(body: String) {}
    /** Opens the SMS app with [body] prefilled for [number] (a volunteer gateway the user scanned). */
    fun composeSmsTo(number: String, body: String) = composeSms(body)
    /** Asks for SEND_SMS + RECEIVE_SMS, only when a grey-area switch is being turned on. */
    fun requestSmsPermissions(onResult: (granted: Boolean) -> Unit) = onResult(false)
    /** Opens this app's App info screen (restricted settings, permissions). */
    fun openAppInfo() {}
    /** Asks for the microphone (voice notes) once; reports whether it may be used. */
    fun requestMicrophone(onResult: (granted: Boolean) -> Unit) = onResult(false)
    fun requestMesh(onResult: (granted: Boolean) -> Unit) = onResult(false)
    /** Asks for precise (or approximate) location before a user-started live share; reports whether any was granted. */
    fun requestLiveLocation(onResult: (granted: Boolean) -> Unit) = onResult(false)
    /** Lets the user pick a file (map pack, GPX/KML/GeoJSON); reports a private copy and its display name. */
    fun openFile(mimeTypes: Array<String>, onResult: (file: java.io.File?, name: String?) -> Unit) = onResult(null, null)

    object None : PlatformActions {
        override fun openUrl(url: String) = Unit
        override fun shareText(text: String) = Unit
        override fun copyToClipboard(label: String, text: String) = Unit
        override fun requestLocation(onResult: (lat: Double?, lng: Double?) -> Unit) = onResult(null, null)
        override fun applyLanguage() = Unit
        override fun saveTextDocument(suggestedName: String, text: String, onResult: (saved: Boolean) -> Unit) = onResult(false)
        override fun openTextDocument(onResult: (text: String?) -> Unit) = onResult(null)
    }
}

private enum class Page { Tabs, Modules, Settings, Relays, TaskDetail, Prayer, Torch, Handbook, HandbookPage, ChatThread, LanTools, SmsRestricted, SmsGateway, MyPins, MapPacks, Compass }
private enum class Sheet {
    None, Connectivity, PanicWipe, Backup, Restore, Compose, CardActions, TaskCompose, HandoffEntry, Payment, Rate, Dispute, Name, NewIou, IouDetail, IouQr, IouPaste, IouSettle, Sos, ShareApp, Radar,
    SendSms, EventQr, PasteLink, GreyArea, GatewayQr, Area, Search,
}

const val REPO_URL = "https://github.com/tanzir71/harkara"

/** App root: onboarding until an area is chosen, then the five tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarkaraApp(container: AppContainer, platform: PlatformActions) {
    val wipeScope = rememberCoroutineScope()
    var wiping by remember(container) { mutableStateOf(false) }
    var wipeFailed by remember(container) { mutableStateOf(false) }
    if (wiping) {
        HarkaraTheme {
            Box(Modifier.fillMaxSize().background(HarkaraTheme.colors.bgCanvas), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (wipeFailed) R.string.panic_wipe_failed else R.string.panic_wiping), color = HarkaraTheme.colors.textPrimary)
            }
        }
        return
    }
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val connectivity by container.connectivity.state.collectAsStateWithLifecycle()
    val update by container.updateChecker.available.collectAsStateWithLifecycle()
    val visibleUpdate = update?.takeIf { it.versionCode > settings.dismissedUpdateCode }
    val profilePubkey by container.identity.profilePubkey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Keys are made silently once an area is chosen: no sign-up step.
    LaunchedEffect(settings.onboarded) {
        if (settings.onboarded) withContext(Dispatchers.Default) { runCatching { container.identity.keyRing() } }
    }
    val notify: (Int) -> Unit = { res -> scope.launch { snackbar.showSnackbar(context.getString(res)) } }

    var tab by rememberSaveable { mutableStateOf(HarkaraTab.Nearby) }
    var page by rememberSaveable { mutableStateOf(Page.Tabs) }
    var sheet by remember { mutableStateOf(Sheet.None) }

    var sheetPostId by remember { mutableStateOf<String?>(null) }
    var posting by remember { mutableStateOf(false) }
    var composeVerdict by remember { mutableStateOf<ComposeVerdict?>(null) }
    val center = remember(settings.areaLat, settings.areaLng, settings.areaName) { container.areaCenter(settings) }
    val feedItems by remember(center, settings.onboarded) {
        if (settings.onboarded) container.feedRepository.feed(center) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val relayStatus by container.relayPool.status.collectAsStateWithLifecycle()
    val presenter = rememberFeedPresenter(container.areas)
    var openTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskDraft by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    var handoffWrong by remember { mutableStateOf(false) }
    var afterName by remember { mutableStateOf<(() -> Unit)?>(null) }
    val taskStates by remember(center, settings.onboarded) {
        if (settings.onboarded) container.taskRepository.tasks(center) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(center, settings.onboarded, relayStatus.size, profilePubkey) {
        if (settings.onboarded && profilePubkey != null) container.taskRepository.subscribeArea(center)
    }
    LaunchedEffect(taskStates.map { it.offer.event.id }) { container.taskRepository.subscribeSteps(taskStates.map { it.offer.event.id }) }
    val openTask = taskStates.firstOrNull { it.offer.event.id == openTaskId }
    var handoff by remember { mutableStateOf<io.github.tanzir71.harkara.tasks.HandoffDisplay?>(null) }
    LaunchedEffect(openTask?.status, openTaskId) { handoff = openTask?.let { container.taskRepository.handoffFor(it.offer) } }
    val trusted = remember(profilePubkey) { setOfNotNull(profilePubkey) }
    val reputation: (String) -> io.github.tanzir71.harkara.core.tasks.Reputation? = { pk ->
        io.github.tanzir71.harkara.core.tasks.ReputationRules.of(pk, taskStates, trusted).takeIf { it.done > 0 || it.ratingCount > 0 }
    }
    /** Runs [action] now if the user has a name, otherwise asks for one first (§4: name only when needed). */
    val withName: (() -> Unit) -> Unit = { action ->
        if (settings.displayName != null) action() else { afterName = action; sheet = Sheet.Name }
    }
    val taskOp: (suspend () -> Unit) -> Unit = { op -> scope.launch { op() } }
    val ownSos by remember(settings.onboarded, profilePubkey) {
        if (settings.onboarded && profilePubkey != null) container.sosRepository.activeOwn() else flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val incomingSos by remember(center, settings.onboarded, profilePubkey) {
        if (settings.onboarded && profilePubkey != null) container.sosRepository.incoming(center) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(center, settings.onboarded, relayStatus.size, profilePubkey) { if (settings.onboarded && profilePubkey != null) container.sosRepository.subscribe(center) }
    LaunchedEffect(incomingSos.firstOrNull()?.event?.id) {
        // Sound only when the user turned emergency alerts on (§4: no sound otherwise).
        if (incomingSos.isNotEmpty() && settings.emergencyAlerts) {
            val tone = io.github.tanzir71.harkara.tools.ToneSiren()
            tone.beep(1500)
            kotlinx.coroutines.delay(1600)
            tone.stop()
        }
    }
    var sosWait by remember { mutableStateOf(0) }
    var handbookSection by remember { mutableStateOf<io.github.tanzir71.harkara.tools.HandbookSection?>(null) }
    var pttTool by rememberSaveable { mutableStateOf(false) }
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val dial999: () -> Unit = { platform.openUrl("tel:999") }
    val setModuleNow: (io.github.tanzir71.harkara.settings.Module, Boolean) -> Unit = { module, on ->
        if (module == Module.OFFLINE_MAP && on && container.mapPackManager.installed() == null) page = Page.MapPacks
        else if (module == io.github.tanzir71.harkara.settings.Module.MESH && on) platform.requestMesh { granted ->
            container.settings.setModule(module, granted)
        } else container.settings.setModule(module, on)
        // Lite mode checks every 60 minutes instead of 30 (§4.1).
        if (module == io.github.tanzir71.harkara.settings.Module.LITE_MODE) RelaySyncWorker.schedule(context, if (on) 60 else 30)
    }
    LaunchedEffect(settings.onboarded, settings.modules) {
        if (settings.onboarded && (settings.isOn(io.github.tanzir71.harkara.settings.Module.NODE) ||
                (settings.isOn(io.github.tanzir71.harkara.settings.Module.MESH) && !settings.isOn(io.github.tanzir71.harkara.settings.Module.LITE_MODE)))) {
            androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, io.github.tanzir71.harkara.mesh.MeshForegroundService::class.java))
        }
    }
    val ious by remember(settings.onboarded) {
        if (settings.onboarded) container.khataRepository.ious() else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(settings.onboarded, relayStatus.size, profilePubkey) { if (settings.onboarded && profilePubkey != null) container.khataRepository.subscribe() }
    var openIouId by remember { mutableStateOf<String?>(null) }
    var iouPasteBad by remember { mutableStateOf(false) }
    val openIou = ious.firstOrNull { it.terms.id == openIouId }
    val khataRows = remember(ious, taskStates, profilePubkey) {
        val me = profilePubkey ?: return@remember emptyList()
        val iouRows = ious.map { s -> s.toRow(me, listOfNotNull(s.terms.note.ifBlank { null }, io.github.tanzir71.harkara.ui.screens.hhmm(s.terms.createdAt)).joinToString(" · ")) }
        // Receipts from paid tasks (settled, read-only).
        val receipts = taskStates.filter { it.status == io.github.tanzir71.harkara.core.tasks.TaskStatus.PAID && (it.requester == me || it.runner == me) }.map { st ->
            val iAmRunner = st.runner == me
            val other = if (iAmRunner) st.requester else st.runner!!
            val method = container.taskRepository.paymentDetails(st)?.method ?: ""
            io.github.tanzir71.harkara.ui.screens.KhataRow(
                "task:" + st.offer.address, io.github.tanzir71.harkara.ui.screens.nameOf(st, other),
                if (iAmRunner) st.offer.price else -st.offer.price,
                listOf(st.offer.title, method).filter { it.isNotBlank() }.joinToString(" · "),
                io.github.tanzir71.harkara.ui.screens.KhataRowStatus.SETTLED,
            )
        }
        iouRows + receipts
    }
    val connectedRelays = relayStatus.values.count { it == io.github.tanzir71.harkara.relay.RelayStatus.CONNECTED }
    LaunchedEffect(center, settings.onboarded, relayStatus.size) { if (settings.onboarded) container.feedRepository.subscribeArea(center) }
    LaunchedEffect(center, settings.onboarded, connectedRelays) { if (settings.onboarded && connectedRelays > 0) container.feedRepository.catchUp(center) }
    LaunchedEffect(feedItems.map { it.post.id }) { container.feedRepository.subscribeReactions(feedItems.map { it.post.id }) }
    var chatId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(center, settings.onboarded) {
        if (settings.onboarded) withContext(Dispatchers.Default) { container.chatRepository.subscribe(GeoHash.encode(center.lat, center.lng, 5)) }
    }
    // M9: pins, check-ins and live location share one gift-wrap inbox.
    LaunchedEffect(center, settings.onboarded, relayStatus.size, profilePubkey) {
        if (settings.onboarded && profilePubkey != null) {
            container.startPlaces()
            container.giftInbox.subscribe(container.relayPool)
            container.pinRepository.subscribe(center)
        }
    }
    var compassTarget by remember { mutableStateOf<io.github.tanzir71.harkara.core.geo.LatLng?>(null) }

    val comingSoon: () -> Unit = { scope.launch { snackbar.showSnackbar(context.getString(R.string.coming_soon)) } }
    val say: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val pill = PillActions(onClick = { sheet = Sheet.Connectivity }, onLongClick = { sheet = Sheet.Sos })

    // M10: send by SMS, share as QR, links in, grey-area switches.
    var smsEvent by remember { mutableStateOf<Event?>(null) }
    var smsResult by remember { mutableStateOf<String?>(null) }
    var greyModule by remember { mutableStateOf<Module?>(null) }
    var pasteMessage by remember { mutableStateOf<String?>(null) }
    var handoffPrefill by remember { mutableStateOf("") }
    var gatewayConfig by remember { mutableStateOf(GatewayConfig()) }
    var savedGateway by remember { mutableStateOf<GatewayCard?>(null) }
    val shareBySms: (Event) -> Unit = { e ->
        smsEvent = e
        smsResult = null
        scope.launch { savedGateway = container.linkInbox.savedGateway() }
        sheet = Sheet.SendSms
    }
    val shareAsQr: (Event) -> Unit = { e -> smsEvent = e; sheet = Sheet.EventQr }
    val toggleModule: (Module, Boolean) -> Unit = { m, on ->
        if (m.greyArea && on) {
            greyModule = m
            sheet = Sheet.GreyArea
        } else {
            setModuleNow(m, on)
            if (m.greyArea) container.greyAreaSms.sync()
        }
    }
    val handleLink: (HarkaraLink) -> Unit = { link ->
        scope.launch {
            when (val o = container.linkInbox.handle(link)) {
                is LinkInbox.Outcome.Received -> {
                    say(context.getString(if (o.isNew) R.string.link_received else R.string.link_received_known))
                    page = Page.Tabs
                    if (sheet == Sheet.PasteLink) sheet = Sheet.None
                    when (o.event.kind) {
                        Kinds.TASK_OFFER, Kinds.TASK_STEP -> tab = HarkaraTab.Tasks
                        Kinds.IOU_SIGN, Kinds.SETTLE -> tab = HarkaraTab.Khata
                    }
                }
                is LinkInbox.Outcome.Progress -> {
                    pasteMessage = context.getString(R.string.link_progress, o.have, o.total)
                    sheet = Sheet.PasteLink
                }
                LinkInbox.Outcome.Unverified -> {
                    pasteMessage = context.getString(R.string.link_unverified)
                    say(context.getString(R.string.link_unverified))
                }
                is LinkInbox.Outcome.OpenKhataImport -> {
                    val st = container.khataRepository.importQr(o.text)
                    if (st == null) say(context.getString(R.string.khata_qr_bad)) else { page = Page.Tabs; tab = HarkaraTab.Khata; openIouId = st.terms.id; sheet = Sheet.IouDetail }
                }
                is LinkInbox.Outcome.OpenIou ->
                    if (o.iouId == null) say(context.getString(R.string.link_not_yours)) else { page = Page.Tabs; tab = HarkaraTab.Khata; openIouId = o.iouId; sheet = Sheet.IouDetail }
                is LinkInbox.Outcome.OpenHandoff -> {
                    val st = taskStates.firstOrNull { it.offer.address == o.offerAddress }
                    if (st == null) say(context.getString(R.string.link_not_yours)) else {
                        openTaskId = st.offer.event.id
                        page = Page.TaskDetail
                        handoffPrefill = o.text
                        handoffWrong = false
                        sheet = Sheet.HandoffEntry
                    }
                }
                is LinkInbox.Outcome.FollowTask -> { page = Page.Tabs; tab = HarkaraTab.Tasks; say(context.getString(R.string.link_following)) }
                is LinkInbox.Outcome.OpenArea -> platform.openUrl(HarkaraLinks.web(HarkaraLinks.areaRest(o.geohash)))
                is LinkInbox.Outcome.SavedGateway -> say(context.getString(R.string.link_gateway_saved, o.card.name))
            }
        }
    }
    val pendingLink by container.pendingLinks.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink, settings.onboarded) {
        val link = pendingLink ?: return@LaunchedEffect
        if (!settings.onboarded) return@LaunchedEffect
        container.pendingLinks.value = null
        handleLink(link)
    }
    LaunchedEffect(settings.isOn(Module.SMS_GATEWAY), connectedRelays) { if (connectedRelays > 0) container.subscribeGateway() }
    LaunchedEffect(settings.onboarded, connectedRelays, ious.size) {
        if (settings.onboarded && profilePubkey != null && connectedRelays > 0) container.khataRepository.subscribeConfirmations()
    }

    HarkaraTheme {
        val c = HarkaraTheme.colors
        Box(Modifier.fillMaxSize().background(c.bgCanvas)) {
            if (!settings.onboarded) {
                OnboardingScreen(
                    areas = container.areas.all.map { it.name },
                    onUseLocation = {
                        platform.requestLocation { lat, lng ->
                            val area = if (lat != null && lng != null) container.areas.nearest(lat, lng).name else null
                            container.settings.completeOnboarding(area, auto = true, lat = lat, lng = lng)
                            RelaySyncWorker.schedule(context)
                        }
                    },
                    onPickArea = {
                        container.settings.completeOnboarding(it, auto = false)
                        RelaySyncWorker.schedule(context)
                    },
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    onRestore = { sheet = Sheet.Restore },
                )
            } else {
                when (page) {
                    Page.SmsRestricted -> RestrictedSettingsScreen(
                        onOpenAppInfo = platform::openAppInfo,
                        onBack = { page = Page.Modules },
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    )
                    Page.SmsGateway -> {
                        LaunchedEffect(Unit) { gatewayConfig = container.gatewayStore.get() }
                        SmsGatewayScreen(
                            config = gatewayConfig,
                            sentToday = container.greyAreaSms.sentToday(),
                            onSave = { cfg ->
                                gatewayConfig = cfg
                                scope.launch { container.gatewayStore.set(cfg); container.subscribeGateway() }
                            },
                            onShowCard = { sheet = Sheet.GatewayQr },
                            onBack = { page = Page.Settings },
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        )
                    }
                    Page.MyPins -> io.github.tanzir71.harkara.ui.map.MyPinsHost(
                        container, platform, profilePubkey, onBack = { page = Page.Tabs }, say = say,
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    )
                    Page.MapPacks -> io.github.tanzir71.harkara.ui.map.MapPacksHost(
                        container, platform, settings, onBack = { page = Page.Tabs }, say = say,
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    )
                    Page.Compass -> io.github.tanzir71.harkara.ui.map.CompassHost(
                        container, profilePubkey, compassTarget, onBack = { page = Page.Tabs },
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    )
                    Page.Modules -> ModulesScreen(
                        settings,
                        onToggle = toggleModule,
                        onBack = { page = Page.Tabs },
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    )
                    Page.Settings -> SettingsScreen(
                        settings,
                        versionName = BuildConfig.VERSION_NAME,
                        onBack = { page = Page.Tabs },
                        onLanguage = {
                            container.settings.setLanguage(it)
                            platform.applyLanguage()
                        },
                        onShare = { sheet = Sheet.ShareApp },
                        onCopyCrash = {
                            platform.copyToClipboard(context.getString(R.string.clipboard_crash_report_label), container.crashReporter.report())
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_crash_copied)) }
                        },
                        onPanicWipe = { sheet = Sheet.PanicWipe },
                        onOpenSource = { platform.openUrl(REPO_URL) },
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        onRelays = { page = Page.Relays },
                        onEmergencyAlerts = container.settings::setEmergencyAlerts,
                        onPasteLink = { pasteMessage = null; sheet = Sheet.PasteLink },
                        onSmsGateway = if (settings.isOn(Module.SMS_GATEWAY)) ({ page = Page.SmsGateway }) else null,
                    )
                    Page.Relays -> RelaysScreen(
                        relays = container.relays(),
                        status = relayStatus,
                        onAdd = { url -> container.settings.setRelays((container.relays() + url).distinct()); container.startRelays() },
                        onRemove = { url -> container.settings.setRelays(container.relays() - url); container.startRelays() },
                        onReset = { container.settings.setRelays(null); container.startRelays() },
                        onBack = { page = Page.Settings },
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    )
                    Page.ChatThread -> chatId?.let { id ->
                        ChatThreadRoute(
                            container, platform, id, connectivity, pill,
                            onBack = { page = Page.Tabs },
                            onMakeTask = { text ->
                                // Long-press → "Make it a task": the task composer, prefilled with the message.
                                taskDraft = text.trim().take(80) to mapOf("items" to text.trim().take(200))
                                withName { sheet = Sheet.TaskCompose }
                            },
                            say = say,
                            onSendSms = shareBySms,
                            modifier = Modifier.statusBarsPadding(),
                        )
                    }
                    Page.Prayer -> {
                        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"))
                        io.github.tanzir71.harkara.ui.screens.PrayerScreen(
                            day = io.github.tanzir71.harkara.core.tools.PrayerTimesCalc.forDay(center.lat, center.lng, today),
                            qibla = io.github.tanzir71.harkara.core.tools.PrayerTimesCalc.qibla(center.lat, center.lng),
                            place = settings.areaName ?: context.getString(R.string.nearby_near_you),
                            now = java.time.Instant.now(),
                            onBack = { page = Page.Tabs },
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        )
                    }
                    Page.Torch -> {
                        val mode by container.lights.mode.collectAsStateWithLifecycle()
                        val lit by container.lights.lit.collectAsStateWithLifecycle()
                        io.github.tanzir71.harkara.ui.screens.TorchScreen(
                            mode = mode, lit = lit, torchAvailable = remember { io.github.tanzir71.harkara.tools.CameraTorch(context).available },
                            onMode = { container.lights.start(it) }, onLeave = { container.lights.stop() }, onBack = { page = Page.Tabs },
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        )
                    }
                    Page.Handbook -> io.github.tanzir71.harkara.ui.screens.HandbookScreen(
                        sections = container.handbook.index.sections,
                        numbers = container.handbook.index.emergencyNumbers,
                        language = language,
                        onOpen = { handbookSection = it; page = Page.HandbookPage },
                        onCall = { n -> platform.openUrl("tel:" + n.filter { it.isDigit() }) },
                        onBack = { page = Page.Tabs },
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    )
                    Page.HandbookPage -> handbookSection?.let { s ->
                        io.github.tanzir71.harkara.ui.screens.HandbookPage(
                            markdown = remember(s, language) { container.handbook.page(s, language) },
                            onBack = { page = Page.Handbook },
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        )
                    }
                    Page.LanTools -> {
                        val enabled = settings.isOn(if (pttTool) io.github.tanzir71.harkara.settings.Module.PUSH_TO_TALK else io.github.tanzir71.harkara.settings.Module.FILE_DROP)
                        if (enabled) io.github.tanzir71.harkara.ui.screens.LanToolsScreen(pttTool, platform, { page = Page.Tabs }, Modifier.statusBarsPadding().navigationBarsPadding())
                        else LaunchedEffect(Unit) { page = Page.Tabs }
                    }
                    Page.TaskDetail -> if (openTask == null) {
                        LaunchedEffect(Unit) { page = Page.Tabs }
                    } else {
                        io.github.tanzir71.harkara.ui.screens.TaskDetailScreen(
                            state = openTask,
                            me = profilePubkey,
                            types = container.taskTypes,
                            connectivity = connectivity,
                            next = container.taskRepository.nextAction(openTask),
                            handoff = handoff,
                            reputation = reputation,
                            pill = pill,
                            modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                            actions = io.github.tanzir71.harkara.ui.screens.TaskActions(
                                onBack = { page = Page.Tabs },
                                onAction = { action ->
                                    when (action) {
                                        io.github.tanzir71.harkara.tasks.TaskAction.ACCEPT -> withName { taskOp { container.taskRepository.accept(openTask.offer) } }
                                        io.github.tanzir71.harkara.tasks.TaskAction.CONFIRM_RUNNER -> taskOp { container.taskRepository.confirm(openTask.offer, openTask.pendingAccepts.first()) }
                                        io.github.tanzir71.harkara.tasks.TaskAction.PICKED_UP -> taskOp { container.taskRepository.pickedUp(openTask.offer) }
                                        io.github.tanzir71.harkara.tasks.TaskAction.ENTER_CODE -> { handoffWrong = false; handoffPrefill = ""; sheet = Sheet.HandoffEntry }
                                        io.github.tanzir71.harkara.tasks.TaskAction.RECORD_PAYMENT -> sheet = Sheet.Payment
                                        io.github.tanzir71.harkara.tasks.TaskAction.CONFIRM_PAYMENT -> taskOp { container.taskRepository.confirmPayment(openTask) }
                                        io.github.tanzir71.harkara.tasks.TaskAction.RATE -> sheet = Sheet.Rate
                                    }
                                },
                                onChoose = { accept -> taskOp { container.taskRepository.confirm(openTask.offer, accept) } },
                                onProblem = { sheet = Sheet.Dispute },
                                onCancel = { taskOp { container.taskRepository.cancel(openTask) } },
                                onDecline = { taskOp { container.taskRepository.decline(openTask) } },
                                onMessage = {
                                    val other = if (openTask.requester == profilePubkey) openTask.runner else openTask.requester
                                    if (other != null) scope.launch {
                                        container.chatRepository.startDm(other, io.github.tanzir71.harkara.ui.screens.nameOf(openTask, other))?.let { id ->
                                            chatId = id
                                            page = Page.ChatThread
                                        }
                                    }
                                },
                                onSendSms = container.taskRepository.lastOwnStep(openTask)?.let { step -> { shareBySms(step) } },
                                onShareQr = { shareAsQr(openTask.offer.event) },
                            ),
                        )
                    }
                    Page.Tabs -> Column(Modifier.fillMaxSize()) {
                        val content = Modifier.weight(1f).statusBarsPadding()
                        when (tab) {
                            HarkaraTab.Nearby -> NearbyScreen(
                                areaName = settings.areaName,
                                areaAuto = settings.areaAuto,
                                connectivity = connectivity,
                                posts = feedItems.map { presenter.toUi(it, System.currentTimeMillis() / 1000) },
                                modifier = content,
                                pill = pill,
                                update = visibleUpdate,
                                onGetUpdate = { platform.openUrl(UpdateChecker.DOWNLOAD_PAGE) },
                                onDismissUpdate = { visibleUpdate?.let { container.settings.dismissUpdate(it.versionCode) } },
                                onCompose = { composeVerdict = null; sheet = Sheet.Compose },
                                onSearch = { sheet = Sheet.Search },
                                onChangeArea = { sheet = Sheet.Area },
                                alerts = if (ownSos == null && incomingSos.isEmpty()) null else ({
                                    ownSos?.let { sos ->
                                        io.github.tanzir71.harkara.ui.screens.ActiveSosBanner(
                                            onResolve = { taskOp { container.sosRepository.resolve(sos, io.github.tanzir71.harkara.core.tools.SosStatus.RESOLVED) } },
                                            onFalseAlarm = { taskOp { container.sosRepository.resolve(sos, io.github.tanzir71.harkara.core.tools.SosStatus.FALSE_ALARM) } },
                                        )
                                    }
                                    incomingSos.take(3).forEach { s ->
                                        io.github.tanzir71.harkara.ui.screens.SosAlertCard(
                                            message = s.event.content.ifBlank { context.getString(R.string.sos_incoming) },
                                            detail = context.getString(
                                                R.string.sos_incoming_detail,
                                                s.event.tagValue("name") ?: io.github.tanzir71.harkara.ui.screens.shortKey(s.event.pubkey),
                                                s.distanceMeters?.let { io.github.tanzir71.harkara.core.geo.GeoMath.distanceLabel(it) } ?: "—",
                                                io.github.tanzir71.harkara.ui.screens.hhmm(s.event.createdAt),
                                            ),
                                            onCall999 = dial999,
                                        )
                                    }
                                }),
                                onVote = { id, up -> feedItems.firstOrNull { it.post.id == id }?.let { scope.launch { container.feedRepository.vote(it.post, up) } } },
                                onMore = { id -> sheetPostId = id; sheet = Sheet.CardActions },
                                onMakeTask = { id -> feedItems.firstOrNull { it.post.id == id }?.let { item ->
                                    taskDraft = item.post.text.take(80) to mapOf("items" to item.post.text.take(200))
                                    withName { sheet = Sheet.TaskCompose }
                                } },
                                onShare = { id -> feedItems.firstOrNull { it.post.id == id }?.let { shareAsQr(it.post.event) } },
                                mapContent = { mapModifier, modeSwitch ->
                                    io.github.tanzir71.harkara.ui.map.NearbyMapHost(
                                        container = container,
                                        platform = platform,
                                        settings = settings,
                                        center = center,
                                        profilePubkey = profilePubkey,
                                        feedPosts = feedItems.map { it.post },
                                        taskOffers = taskStates.filter { it.status == io.github.tanzir71.harkara.core.tasks.TaskStatus.OPEN }.map { it.offer },
                                        modeSwitch = modeSwitch,
                                        onOpenTask = { id -> taskStates.firstOrNull { "${it.offer.address}" == id }?.let { openTaskId = it.offer.event.id; page = Page.TaskDetail } },
                                        onOpenPacks = { page = Page.MapPacks },
                                        onOpenCompass = { target -> compassTarget = target; page = Page.Compass },
                                        say = say,
                                        modifier = mapModifier,
                                    )
                                },
                            )
                            HarkaraTab.Chats -> ChatsRoute(container, platform, connectivity, pill, onOpen = { id -> chatId = id; page = Page.ChatThread }, say = say, modifier = content)
                            HarkaraTab.Tasks -> TasksScreen(
                                states = taskStates,
                                me = profilePubkey,
                                types = container.taskTypes,
                                connectivity = connectivity,
                                modifier = content,
                                pill = pill,
                                reputation = reputation,
                                onOpen = { openTaskId = it.offer.event.id; page = Page.TaskDetail },
                                onNew = { withName { taskDraft = null; sheet = Sheet.TaskCompose } },
                            )
                            HarkaraTab.Khata -> KhataScreen(
                                rows = khataRows,
                                connectivity = connectivity,
                                modifier = content,
                                pill = pill,
                                onOpen = { row -> if (row.iou != null) { openIouId = row.iou.terms.id; sheet = Sheet.IouDetail } },
                                onNew = { withName { sheet = Sheet.NewIou } },
                                onExport = {
                                    val me = profilePubkey ?: ""
                                    val csv = io.github.tanzir71.harkara.core.khata.KhataExport.csv(ious, me)
                                    platform.saveTextDocument("harkara-khata.csv", csv) { saved ->
                                        if (saved) {
                                            platform.saveTextDocument("harkara-khata.json", io.github.tanzir71.harkara.core.khata.KhataExport.json(ious)) { ok ->
                                                if (ok) say(context.getString(R.string.khata_export_done))
                                            }
                                        }
                                    }
                                },
                            )
                            HarkaraTab.Me -> MeScreen(
                                settings = settings,
                                connectivity = connectivity,
                                modifier = content,
                                pill = pill,
                                update = visibleUpdate,
                                onGetUpdate = { platform.openUrl(UpdateChecker.DOWNLOAD_PAGE) },
                                onDismissUpdate = { visibleUpdate?.let { container.settings.dismissUpdate(it.versionCode) } },
                                onTool = { tool ->
                                    when (tool.module) {
                                        io.github.tanzir71.harkara.settings.Module.QIBLA_PRAYER -> page = Page.Prayer
                                        io.github.tanzir71.harkara.settings.Module.TORCH_TOOLS -> page = Page.Torch
                                        io.github.tanzir71.harkara.settings.Module.HANDBOOK -> page = Page.Handbook
                                        io.github.tanzir71.harkara.settings.Module.RADAR -> sheet = Sheet.Radar
                                        io.github.tanzir71.harkara.settings.Module.NODE -> sheet = Sheet.Connectivity
                                        io.github.tanzir71.harkara.settings.Module.FILE_DROP -> { pttTool = false; page = Page.LanTools }
                                        io.github.tanzir71.harkara.settings.Module.PUSH_TO_TALK -> { pttTool = true; page = Page.LanTools }
                                        io.github.tanzir71.harkara.settings.Module.SAVED_PINS -> page = Page.MyPins
                                        io.github.tanzir71.harkara.settings.Module.OFFLINE_MAP -> page = Page.MapPacks
                                        io.github.tanzir71.harkara.settings.Module.COMPASS -> { compassTarget = null; page = Page.Compass }
                                        io.github.tanzir71.harkara.settings.Module.QR -> { pasteMessage = null; sheet = Sheet.PasteLink }
                                        else -> comingSoon()
                                    }
                                },
                                onToggleModule = toggleModule,
                                onAllModules = { page = Page.Modules },
                                onSettings = { page = Page.Settings },
                                onLogoTripleTap = { sheet = Sheet.PanicWipe },
                                npub = profilePubkey?.let(Bech32::encodeNpub),
                                onBackup = { sheet = Sheet.Backup },
                            )
                        }
                        TabBar(tab, { tab = it })
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }

        if (sheet != Sheet.None) {
            ModalBottomSheet(
                onDismissRequest = { sheet = Sheet.None },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = c.bgRaised,
            ) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    when (sheet) {
                        Sheet.Connectivity -> androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                            ConnectivitySheetContent(connectivity, settings.meshMode, container.settings::setMeshMode)
                            androidx.compose.material3.Text(context.getString(R.string.mesh_beta_note), color = c.textSecondary, style = HarkaraTheme.type.caption)
                            io.github.tanzir71.harkara.ui.screens.ModuleRow(io.github.tanzir71.harkara.settings.Module.MESH, settings.isOn(io.github.tanzir71.harkara.settings.Module.MESH), toggleModule)
                            io.github.tanzir71.harkara.ui.screens.ModuleRow(io.github.tanzir71.harkara.settings.Module.NODE, settings.isOn(io.github.tanzir71.harkara.settings.Module.NODE), toggleModule)
                            io.github.tanzir71.harkara.ui.components.HButton(context.getString(R.string.disaster_mode), {
                                container.settings.setMeshMode(io.github.tanzir71.harkara.settings.MeshMode.EVENT)
                                toggleModule(Module.MESH, true)
                            })
                        }
                        Sheet.ShareApp -> ShareAppSheet(container, platform)
                        Sheet.Radar -> RadarSheet(container)
                        Sheet.PanicWipe -> PanicWipeSheetContent(
                            onWipe = {
                                sheet = Sheet.None
                                page = Page.Tabs
                                tab = HarkaraTab.Nearby
                                wiping = true
                                wipeScope.launch {
                                    try { container.panicWipe(); platform.afterPanicWipe() }
                                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (_: Exception) { wipeFailed = true }
                                }
                            },
                            onCancel = { sheet = Sheet.None },
                        )
                        Sheet.Area -> AreaPickerSheet(container.areas.all.map { it.name }, onPick = { name ->
                            container.settings.completeOnboarding(name, auto = false)
                            sheet = Sheet.None
                        }, onLocation = {
                            platform.requestLocation { lat, lng ->
                                if (lat != null && lng != null) {
                                    container.settings.completeOnboarding(container.areas.nearest(lat, lng).name, auto = true, lat = lat, lng = lng)
                                    sheet = Sheet.None
                                } else notify(R.string.loc_no_fix)
                            }
                        })
                        Sheet.Search -> LocalSearchSheet(remember(container) { io.github.tanzir71.harkara.search.LocalSearch(container.database) }) { result ->
                            result.position?.let { position ->
                                compassTarget = position
                                sheet = Sheet.None
                                page = Page.Compass
                            }
                        }
                        Sheet.Backup -> BackupSheet(
                            export = container.identity::exportBackup,
                            saveFile = platform::saveTextDocument,
                            onFinished = { saved ->
                                sheet = Sheet.None
                                when (saved) {
                                    true -> notify(R.string.backup_saved)
                                    false -> notify(R.string.backup_save_failed)
                                    null -> Unit
                                }
                            },
                        )
                        Sheet.Restore -> RestoreSheet(
                            restore = { backup, password -> runCatching { container.identity.restore(backup, password) }.isSuccess },
                            openFile = platform::openTextDocument,
                            onRestored = {
                                sheet = Sheet.None
                                notify(R.string.restore_done)
                            },
                        )
                        Sheet.Compose -> ComposeSheetContent(
                            areaLabel = settings.areaName?.let { if (settings.areaAuto) context.getString(R.string.compose_area_auto, it) else it }
                                ?: context.getString(R.string.nearby_near_you),
                            connectivity = connectivity,
                            posting = posting,
                            verdict = composeVerdict,
                            onPost = { text, category, details ->
                                posting = true
                                scope.launch {
                                    val result = container.feedRepository.post(text, category, details, center)
                                    posting = false
                                    composeVerdict = result.verdict
                                    if (result.verdict == ComposeVerdict.OK) sheet = Sheet.None
                                }
                            },
                        )
                        Sheet.CardActions -> {
                            val item = feedItems.firstOrNull { it.post.id == sheetPostId }
                            CardActionsSheetContent(
                                onReport = { reason ->
                                    sheet = Sheet.None
                                    item?.let { scope.launch { container.feedRepository.report(it.post, reason) } }
                                    say(context.getString(R.string.card_reported))
                                },
                                onBlock = {
                                    sheet = Sheet.None
                                    item?.let { scope.launch { container.feedRepository.blockAuthor(it.post.event.pubkey) } }
                                    say(context.getString(R.string.card_blocked))
                                },
                                onMuteWord = { word ->
                                    sheet = Sheet.None
                                    scope.launch { container.feedRepository.muteWord(word) }
                                    say(context.getString(R.string.card_muted, word))
                                },
                                onFetch = item?.takeIf { it.post.category == io.github.tanzir71.harkara.core.Category.STOCK }?.let { stock ->
                                    {
                                        val ui = presenter.toUi(stock, System.currentTimeMillis() / 1000)
                                        val place = container.areas.nearest(stock.post.location.lat, stock.post.location.lng).name
                                        taskDraft = (context.getString(R.string.task_fetch) + ": " + (ui.detail ?: stock.post.text.take(40))) to
                                            mapOf("store" to place, "items" to stock.post.text.take(200))
                                        withName { sheet = Sheet.TaskCompose }
                                    }
                                },
                            )
                        }
                        Sheet.TaskCompose -> io.github.tanzir71.harkara.ui.screens.TaskComposeSheetContent(
                            types = container.taskTypes,
                            initialType = if (taskDraft != null) "buy" else container.taskTypes.types.first().id,
                            initialTitle = taskDraft?.first.orEmpty(),
                            initialFields = taskDraft?.second.orEmpty(),
                            onPost = { type, title, price, fields, description ->
                                sheet = Sheet.None
                                taskOp {
                                    container.taskRepository.post(type, title, price, fields, description, center)
                                    snackbar.showSnackbar(context.getString(R.string.task_posted))
                                }
                                tab = HarkaraTab.Tasks
                            },
                        )
                        Sheet.HandoffEntry -> io.github.tanzir71.harkara.ui.screens.HandoffEntrySheetContent(
                            initial = handoffPrefill,
                            wrong = handoffWrong,
                            onCheck = { entered ->
                                val state = openTask
                                if (state != null) taskOp {
                                    if (container.taskRepository.deliver(state, entered)) {
                                        sheet = Sheet.None
                                        snackbar.showSnackbar(context.getString(R.string.task_delivered_toast))
                                    } else {
                                        handoffWrong = true
                                    }
                                }
                            },
                        )
                        Sheet.Payment -> io.github.tanzir71.harkara.ui.screens.PaymentSheetContent(
                            amount = openTask?.offer?.price ?: 0,
                            onSave = { method, trx -> sheet = Sheet.None; openTask?.let { s -> taskOp { container.taskRepository.recordPayment(s, method, trx) } } },
                        )
                        Sheet.Rate -> io.github.tanzir71.harkara.ui.screens.RateSheetContent(
                            onRate = { stars -> sheet = Sheet.None; openTask?.let { s -> taskOp { container.taskRepository.rate(s, stars) } } },
                        )
                        Sheet.Dispute -> io.github.tanzir71.harkara.ui.screens.DisputeSheetContent(
                            onSend = { reason, note -> sheet = Sheet.None; openTask?.let { s -> taskOp { container.taskRepository.dispute(s, reason, note) } } },
                        )
                        Sheet.Name -> io.github.tanzir71.harkara.ui.screens.NameSheetContent(
                            onSave = { name ->
                                container.settings.setDisplayName(name)
                                sheet = Sheet.None
                                afterName?.invoke()
                                afterName = null
                            },
                        )
                        Sheet.NewIou -> io.github.tanzir71.harkara.ui.screens.NewIouSheetContent(
                            onSave = { direction, name, npub, amount, note ->
                                sheet = Sheet.None
                                val key = npub.takeIf { it.startsWith("npub1") }?.let { runCatching { Bech32.decodeNpub(it) }.getOrNull() }
                                taskOp { container.khataRepository.create(direction, io.github.tanzir71.harkara.core.khata.Party(name.trim(), key), amount, note) }
                            },
                            onSplit = { names, total, note ->
                                sheet = Sheet.None
                                taskOp { container.khataRepository.splitBill(names.map { io.github.tanzir71.harkara.core.khata.Party(it) }, total, note) }
                            },
                        )
                        Sheet.IouDetail -> openIou?.let { s ->
                            io.github.tanzir71.harkara.ui.screens.IouDetailSheetContent(
                                state = s,
                                me = profilePubkey ?: "",
                                onSign = { taskOp { container.khataRepository.sign(s) } },
                                onShowQr = { sheet = Sheet.IouQr },
                                onPasteQr = { iouPasteBad = false; sheet = Sheet.IouPaste },
                                onRemind = {
                                    val what = s.terms.note.filter { it.code < 128 }.trim().ifBlank { "our IOU" }
                                    platform.composeSms(context.getString(R.string.khata_reminder_sms, (settings.displayName ?: "Harkara").filter { it.code < 128 }.ifBlank { "Harkara" }, s.terms.amount.toInt(), what, container.khataRepository.reminderLink(s)))
                                },
                                onSettle = { sheet = Sheet.IouSettle },
                                onConfirmSettle = { taskOp { container.khataRepository.confirmSettlement(s) } },
                                onSendSms = (container.khataRepository.mySettlement(s) ?: container.khataRepository.mySignature(s))
                                    ?.takeIf { s.terms.creditor.pubkey != null && s.terms.debtor.pubkey != null }?.let { e -> { shareBySms(e) } },
                            )
                        }
                        Sheet.IouQr -> openIou?.let { s -> io.github.tanzir71.harkara.ui.screens.IouQrSheetContent(container.khataRepository.qrFor(s)) }
                        Sheet.IouPaste -> io.github.tanzir71.harkara.ui.screens.PasteQrSheetContent(
                            bad = iouPasteBad,
                            onRead = { text ->
                                taskOp {
                                    val st = container.khataRepository.importQr(text)
                                    if (st == null) iouPasteBad = true else { openIouId = st.terms.id; sheet = Sheet.IouDetail }
                                }
                            },
                        )
                        Sheet.IouSettle -> openIou?.let { s ->
                            io.github.tanzir71.harkara.ui.screens.PaymentSheetContent(
                                amount = s.terms.amount,
                                onSave = { method, trx ->
                                    sheet = Sheet.None
                                    taskOp { container.khataRepository.recordSettlement(s, io.github.tanzir71.harkara.core.khata.Settlement(method.wire, s.terms.amount, trx)) }
                                },
                            )
                        }
                        Sheet.Sos -> {
                            LaunchedEffect(Unit) { sosWait = if (profilePubkey != null) container.sosRepository.waitMinutes() else 0 }
                            io.github.tanzir71.harkara.ui.screens.SosSheetContent(
                                waitMinutes = sosWait,
                                onCall999 = dial999,
                                onSend = { reason, alsoSms ->
                                    sheet = Sheet.None
                                    taskOp {
                                        val sent = container.sosRepository.send(reason, center, settings.displayName)
                                        if (sent != null) {
                                            snackbar.showSnackbar(context.getString(R.string.sos_sent))
                                            if (alsoSms) platform.composeSms(context.getString(R.string.sos_sms_text, (settings.displayName ?: "a neighbour").filter { it.code < 128 }.ifBlank { "a neighbour" }, (settings.areaName ?: "Dhaka").filter { it.code < 128 }))
                                        }
                                    }
                                },
                            )
                        }
                        Sheet.SendSms -> smsEvent?.let { e ->
                            SendSmsSheetContent(
                                draft = remember(e) { SmsComposer.draft(e) },
                                onOpenSmsApp = { text -> platform.composeSms(text); sheet = Sheet.None },
                                onShowQr = { sheet = Sheet.EventQr },
                                directOn = settings.isOn(Module.SMS_DIRECT),
                                onSendDirect = { number ->
                                    smsResult = when (val r = container.greyAreaSms.sendDirect(e, number)) {
                                        is GreyAreaSms.Sent.Ok -> context.getString(R.string.sms_direct_sent, r.frames)
                                        else -> context.getString(R.string.sms_direct_failed)
                                    }
                                },
                                gateway = savedGateway,
                                onSendToGateway = { text -> savedGateway?.let { platform.composeSmsTo(it.number, text) }; sheet = Sheet.None },
                                result = smsResult,
                            )
                        }
                        Sheet.EventQr -> smsEvent?.let { e ->
                            EventQrSheetContent(remember(e) { QrTransport.encode(e) }, onCopy = { platform.copyToClipboard(context.getString(R.string.app_name), it); notify(R.string.qr_copied) })
                        }
                        Sheet.PasteLink -> PasteLinkSheetContent(
                            onRead = { text -> DeepLinkRouter.parse(text)?.let(handleLink) ?: run { pasteMessage = context.getString(R.string.link_unverified) } },
                            message = pasteMessage,
                        )
                        Sheet.GreyArea -> greyModule?.let { m ->
                            GreyAreaSheetContent(
                                m,
                                onTurnOn = {
                                    platform.requestSmsPermissions { granted ->
                                        sheet = Sheet.None
                                        if (granted) {
                                            container.settings.setModule(m, true)
                                            container.greyAreaSms.sync()
                                            if (m == Module.SMS_GATEWAY) page = Page.SmsGateway
                                        } else {
                                            page = Page.SmsRestricted
                                        }
                                    }
                                },
                                onCancel = { sheet = Sheet.None },
                            )
                        }
                        Sheet.GatewayQr -> GatewayCardSheetContent(GatewayCard(settings.displayName ?: "Harkara", gatewayConfig.ownNumber, profilePubkey))
                        Sheet.None -> Unit
                    }
                }
            }
        }
    }

}
