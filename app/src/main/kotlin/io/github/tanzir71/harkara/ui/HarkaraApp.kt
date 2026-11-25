package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.background
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
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.screens.BackupSheet
import io.github.tanzir71.harkara.ui.screens.RestoreSheet
import io.github.tanzir71.harkara.core.feed.ComposeVerdict
import io.github.tanzir71.harkara.feed.rememberFeedPresenter
import io.github.tanzir71.harkara.relay.RelaySyncWorker
import io.github.tanzir71.harkara.ui.screens.CardActionsSheetContent
import io.github.tanzir71.harkara.ui.screens.ChatsScreen
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

private enum class Page { Tabs, Modules, Settings, Relays, TaskDetail, SmsRestricted, SmsGateway }
private enum class Sheet {
    None, Connectivity, PanicWipe, Backup, Restore, Compose, CardActions, TaskCompose, HandoffEntry, Payment, Rate, Dispute, Name, NewIou, IouDetail, IouQr, IouPaste, IouSettle,
    SendSms, EventQr, PasteLink, GreyArea, GatewayQr,
}

const val REPO_URL = "https://github.com/tanzir71/harkara"

/** App root: onboarding until an area is chosen, then the five tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarkaraApp(container: AppContainer, platform: PlatformActions) {
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

    val comingSoon: () -> Unit = { scope.launch { snackbar.showSnackbar(context.getString(R.string.coming_soon)) } }
    val say: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val pill = PillActions(onClick = { sheet = Sheet.Connectivity })

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
            container.settings.setModule(m, on)
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
                        onShare = { platform.shareText(context.getString(R.string.settings_share_text, UpdateChecker.DOWNLOAD_PAGE)) },
                        onCopyCrash = {
                            platform.copyToClipboard("Harkara crash report", container.crashReporter.report())
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_crash_copied)) }
                        },
                        onPanicWipe = { sheet = Sheet.PanicWipe },
                        onOpenSource = { platform.openUrl(REPO_URL) },
                        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                        onRelays = { page = Page.Relays },
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
                                onMessage = { comingSoon() },
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
                                onSearch = comingSoon,
                                onVote = { id, up -> feedItems.firstOrNull { it.post.id == id }?.let { scope.launch { container.feedRepository.vote(it.post, up) } } },
                                onMore = { id -> sheetPostId = id; sheet = Sheet.CardActions },
                            )
                            HarkaraTab.Chats -> ChatsScreen(connectivity, content, pill)
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
                                onTool = { comingSoon() },
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
                        Sheet.Connectivity -> ConnectivitySheetContent(connectivity, settings.meshMode, container.settings::setMeshMode)
                        Sheet.PanicWipe -> PanicWipeSheetContent(
                            onWipe = {
                                sheet = Sheet.None
                                page = Page.Tabs
                                tab = HarkaraTab.Nearby
                                scope.launch { container.panicWipe() }
                            },
                            onCancel = { sheet = Sheet.None },
                        )
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
                            EventQrSheetContent(remember(e) { QrTransport.encode(e) }, onCopy = { platform.copyToClipboard("Harkara", it); notify(R.string.qr_copied) })
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
