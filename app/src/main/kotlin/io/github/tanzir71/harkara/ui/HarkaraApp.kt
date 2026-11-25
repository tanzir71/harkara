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
import io.github.tanzir71.harkara.ui.screens.MeScreen
import io.github.tanzir71.harkara.ui.screens.ModulesScreen
import io.github.tanzir71.harkara.ui.screens.NearbyScreen
import io.github.tanzir71.harkara.ui.screens.OnboardingScreen
import io.github.tanzir71.harkara.ui.screens.PanicWipeSheetContent
import io.github.tanzir71.harkara.ui.screens.PillActions
import io.github.tanzir71.harkara.ui.screens.SettingsScreen
import io.github.tanzir71.harkara.ui.screens.TasksScreen
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
    /** Asks for the microphone (voice notes) once; reports whether it may be used. */
    fun requestMicrophone(onResult: (granted: Boolean) -> Unit) = onResult(false)

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

private enum class Page { Tabs, Modules, Settings, Relays }
private enum class Sheet { None, Connectivity, PanicWipe, Backup, Restore, Compose, CardActions }

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
    val connectedRelays = relayStatus.values.count { it == io.github.tanzir71.harkara.relay.RelayStatus.CONNECTED }
    LaunchedEffect(center, settings.onboarded, relayStatus.size) { if (settings.onboarded) container.feedRepository.subscribeArea(center) }
    LaunchedEffect(center, settings.onboarded, connectedRelays) { if (settings.onboarded && connectedRelays > 0) container.feedRepository.catchUp(center) }
    LaunchedEffect(feedItems.map { it.post.id }) { container.feedRepository.subscribeReactions(feedItems.map { it.post.id }) }

    val comingSoon: () -> Unit = { scope.launch { snackbar.showSnackbar(context.getString(R.string.coming_soon)) } }
    val say: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val pill = PillActions(onClick = { sheet = Sheet.Connectivity })

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
                    Page.Modules -> ModulesScreen(
                        settings,
                        onToggle = container.settings::setModule,
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
                            HarkaraTab.Tasks -> TasksScreen(connectivity, content, pill)
                            HarkaraTab.Khata -> KhataScreen(connectivity, content, pill)
                            HarkaraTab.Me -> MeScreen(
                                settings = settings,
                                connectivity = connectivity,
                                modifier = content,
                                pill = pill,
                                update = visibleUpdate,
                                onGetUpdate = { platform.openUrl(UpdateChecker.DOWNLOAD_PAGE) },
                                onDismissUpdate = { visibleUpdate?.let { container.settings.dismissUpdate(it.versionCode) } },
                                onTool = { comingSoon() },
                                onToggleModule = container.settings::setModule,
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
                            )
                        }
                        Sheet.None -> Unit
                    }
                }
            }
        }
    }

}
