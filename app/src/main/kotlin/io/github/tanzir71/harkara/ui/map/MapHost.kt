package io.github.tanzir71.harkara.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tanzir71.harkara.AppContainer
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.feed.FeedPost
import io.github.tanzir71.harkara.core.geo.GeoHash
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.core.geo.Heading
import io.github.tanzir71.harkara.core.geo.LatLng
import io.github.tanzir71.harkara.core.location.LivePerson
import io.github.tanzir71.harkara.core.location.LocationCardData
import io.github.tanzir71.harkara.core.map.LabelLanguage
import io.github.tanzir71.harkara.core.map.MapLayer
import io.github.tanzir71.harkara.core.map.MapLayers
import io.github.tanzir71.harkara.core.map.MapMarker
import io.github.tanzir71.harkara.core.map.MarkerKind
import io.github.tanzir71.harkara.core.pins.PinVisibility
import io.github.tanzir71.harkara.core.tasks.TaskOffer
import io.github.tanzir71.harkara.map.LocalMapSurface
import io.github.tanzir71.harkara.map.MapCamera
import io.github.tanzir71.harkara.map.MapStyleSpec
import io.github.tanzir71.harkara.map.MapViewState
import io.github.tanzir71.harkara.map.PackState
import io.github.tanzir71.harkara.map.mapColors
import io.github.tanzir71.harkara.places.PinView
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.settings.SettingsState
import io.github.tanzir71.harkara.ui.PlatformActions
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.PinCard
import io.github.tanzir71.harkara.ui.components.PinUi
import io.github.tanzir71.harkara.ui.screens.shortKey
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private enum class MapSheet { None, PinEditor, PinDetail, LocationCard, Live, CheckIn }

/** Minutes left in a live share, at least 1 while it runs. */
fun minutesLeft(end: Long, now: Long): Long = ((end - now + 59) / 60).coerceAtLeast(0)

/** A pin as the shared PinCard shows it. */
@Composable
fun PinView.toUi(): PinUi = PinUi(pin.title, pin.note, visibilityLabel(pin.visibility), tally.yes, tally.no, pin.color)

/**
 * Nearby → Map: everything the map screen needs from the container (layers, selection, sheets). The
 * device's location is read only while this screen is on screen; the map itself is [LocalMapSurface].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyMapHost(
    container: AppContainer,
    platform: PlatformActions,
    settings: SettingsState,
    center: LatLng,
    profilePubkey: String?,
    feedPosts: List<FeedPost>,
    taskOffers: List<TaskOffer>,
    modeSwitch: @Composable () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenPacks: () -> Unit,
    onOpenCompass: (LatLng?) -> Unit,
    say: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pins by remember(profilePubkey) { if (profilePubkey != null) container.pinRepository.pins() else flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val people by remember(profilePubkey) { if (profilePubkey != null) container.liveShareRepository.people() else flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val outgoing by container.liveShareRepository.outgoing.collectAsStateWithLifecycle()
    val trip by remember(profilePubkey) { if (profilePubkey != null) container.checkInRepository.activeTrip() else flowOf(null) }.collectAsStateWithLifecycle(null)
    val packState by container.mapPackManager.state.collectAsStateWithLifecycle()
    val installed = when (val s = packState) {
        is PackState.Idle -> s.installed
        else -> container.mapPackManager.installed()
    }
    // Location only while this screen is visible (battery rule).
    val fix by remember { container.locationSource.updates(10_000) }.collectAsStateWithLifecycle(container.locationSource.lastKnown())
    val user = fix?.position
    var enabledKeys by rememberSaveable { mutableStateOf(MapLayer.DEFAULT.map { it.key }) }
    val enabled = MapLayer.entries.filter { it.key in enabledKeys }.toSet()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(MapSheet.None) }
    var editing by remember { mutableStateOf<PinView?>(null) }
    var dropAt by remember { mutableStateOf<LatLng?>(null) }
    val now = System.currentTimeMillis() / 1000
    val mapState = remember { MapViewState(MapCamera(center, 15.0)) }

    val markers = remember(pins, people, feedPosts, taskOffers, enabled, profilePubkey) {
        MapLayers.build(enabled, feedPosts, pins.map { it.pin }, profilePubkey, people, taskOffers, emptyList(), now)
    }
    val selected = markers.firstOrNull { it.id == selectedId }
    val surface = LocalMapSurface.current
    val lite = settings.isOn(Module.NO_MAP_LITE) || settings.isOn(Module.LITE_MODE) || !settings.isOn(Module.OFFLINE_MAP) || installed == null
    val bangla = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    val style = installed?.takeIf { !lite }?.let {
        MapStyleSpec(it.path, it.maxZoom, HarkaraTheme.isNight, HarkaraTheme.colors.mapColors(), if (bangla) LabelLanguage.BANGLA else LabelLanguage.ENGLISH)
    }
    val here = user ?: center
    val pinOf: (MapMarker) -> PinView? = { m -> pins.firstOrNull { "pin:${it.pin.address}" == m.id } }
    val personOf: (MapMarker) -> LivePerson? = { m -> people.firstOrNull { "person:${it.owner}" == m.id } }

    NearbyMapContent(
        modeSwitch = modeSwitch,
        surface = if (style != null) surface else null,
        style = style,
        mapState = mapState,
        markers = markers,
        enabled = enabled,
        onToggleLayer = { l -> enabledKeys = if (l.key in enabledKeys) enabledKeys - l.key else enabledKeys + l.key },
        user = user,
        origin = here,
        selectedId = selectedId,
        onSelect = { m ->
            selectedId = m?.id
            if (m != null && m.kind == MarkerKind.TASK) onOpenTask(m.id.removePrefix("task:"))
        },
        personTag = { m -> personOf(m)?.let { p -> context.getString(R.string.map_person_left, m.detail.orEmpty(), minutesLeft(p.end, now)) } ?: m.detail.orEmpty() },
        bottomCard = {
            val m = selected
            when {
                m != null && m.kind == MarkerKind.PIN -> pinOf(m)?.let { pv ->
                    val open = { editing = pv; sheet = MapSheet.PinDetail }
                    Box(
                        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(io.github.tanzir71.harkara.ui.theme.Tokens.Radius.lg))
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.map_more), onClick = open),
                    ) {
                        PinCard(
                            pv.toUi(),
                            Modifier.fillMaxWidth(),
                            onYes = { if (!pv.mine) scope.launch { container.pinRepository.answer(pv.pin, true) } else open() },
                            onNo = { if (!pv.mine) scope.launch { container.pinRepository.answer(pv.pin, false) } else open() },
                        )
                    }
                }
                m != null && m.kind == MarkerKind.PERSON -> personOf(m)?.let { p -> PersonCard(p, here, now) { onOpenCompass(p.position) } }
                m != null && m.kind == MarkerKind.POST -> PostMiniCard(feedPosts.firstOrNull { "post:${it.id}" == m.id }, here)
                else -> MapActionsCard(
                    onDropPin = if (settings.isOn(Module.SAVED_PINS)) { { dropAt = mapState.camera.center.takeIf { style != null } ?: here; editing = null; sheet = MapSheet.PinEditor } } else null,
                    onShareLocation = if (settings.isOn(Module.LOCATION_CARD)) { { sheet = MapSheet.LocationCard } } else null,
                    onCheckIn = if (settings.isOn(Module.CHECK_INS) || settings.isOn(Module.TRIP_PLAN)) { { sheet = MapSheet.CheckIn } } else null,
                    onLiveShare = if (settings.isOn(Module.LIVE_SHARE) || outgoing != null) { { sheet = MapSheet.Live } } else null,
                    liveRunning = outgoing?.let { stringResource(R.string.live_running, it.recipients.size, io.github.tanzir71.harkara.ui.screens.hhmm(it.end)) },
                    onStopLive = { scope.launch { container.liveShareRepository.stop() } },
                )
            }
        },
        onCompass = { if (style != null) mapState.northUp(); onOpenCompass(null) },
        onLocate = {
            platform.requestLocation { lat, lng ->
                if (lat != null && lng != null) mapState.moveTo(LatLng(lat, lng), maxOf(mapState.camera.zoom, 16.0)) else say(context.getString(R.string.map_no_fix))
            }
        },
        modifier = modifier,
        liteBanner = if (installed == null) {
            { GetMapCard(onOpenPacks) }
        } else {
            null
        },
    )

    if (sheet != MapSheet.None) {
        ModalBottomSheet(
            onDismissRequest = { sheet = MapSheet.None },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = HarkaraTheme.colors.bgRaised,
        ) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                when (sheet) {
                    MapSheet.PinEditor -> {
                        val e = editing
                        PinEditorSheetContent(
                            editing = e != null,
                            initialTitle = e?.pin?.title.orEmpty(),
                            initialNote = e?.pin?.note.orEmpty(),
                            initialColor = e?.pin?.color ?: Category.STOCK,
                            initialVisibility = e?.pin?.visibility ?: PinVisibility.PRIVATE,
                            onSave = { title, note, color, visibility, recipients, days ->
                                sheet = MapSheet.None
                                scope.launch {
                                    val at = e?.pin?.position ?: dropAt ?: here
                                    if (e != null && e.pin.visibility != visibility) container.pinRepository.delete(e.pin)
                                    val id = if (e != null && e.pin.visibility == visibility) e.pin.id else java.util.UUID.randomUUID().toString()
                                    val all = if (visibility == PinVisibility.SHARED) (recipients + e?.pin?.recipients.orEmpty()).distinct() else recipients
                                    container.pinRepository.save(title, note, at, color, visibility, all, days?.let { it * 86_400L }, id)
                                    say(context.getString(R.string.pin_saved))
                                }
                            },
                            onDelete = e?.let { pv -> { sheet = MapSheet.None; scope.launch { container.pinRepository.delete(pv.pin) }; selectedId = null } },
                        )
                    }
                    MapSheet.PinDetail -> editing?.let { pv ->
                        PinDetailSheetContent(
                            pv, here,
                            onArrow = { sheet = MapSheet.None; onOpenCompass(pv.pin.position) },
                            onShare = {
                                val card = LocationCardData.make(pv.pin.position, io.github.tanzir71.harkara.core.location.LocationPrecision.EXACT, null, pv.pin.title)
                                platform.shareText(card.shareText())
                            },
                            onEdit = { sheet = MapSheet.PinEditor },
                        )
                    }
                    MapSheet.LocationCard -> LocationCardSheetContent(
                        cardFor = { p ->
                            val at = user ?: (if (settings.areaLat != null) center else null)
                            at?.let { LocationCardData.make(it, p, settings.areaName ?: container.areas.nearest(it.lat, it.lng).name) }
                        },
                        onShare = { card -> sheet = MapSheet.None; platform.shareText(card.shareText()) },
                        onCopy = { card -> platform.copyToClipboard("Location", card.shareText()); say(context.getString(R.string.loc_copied)) },
                    )
                    MapSheet.Live -> LiveShareSheetContent(
                        running = outgoing,
                        onStart = { keys, duration ->
                            platform.requestLiveLocation { granted ->
                                if (!granted) {
                                    say(context.getString(R.string.map_no_fix))
                                } else {
                                    sheet = MapSheet.None
                                    scope.launch {
                                        runCatching { container.liveShareRepository.start(keys, duration) }
                                            .onSuccess { say(context.getString(R.string.live_started)) }
                                    }
                                }
                            }
                        },
                        onStop = { sheet = MapSheet.None; scope.launch { container.liveShareRepository.stop() } },
                    )
                    MapSheet.CheckIn -> CheckInSheetContent(
                        defaultPlace = settings.areaName ?: container.areas.nearest(here.lat, here.lng).name,
                        trip = trip,
                        tripsEnabled = settings.isOn(Module.TRIP_PLAN),
                        onCheckIn = { place, keys, note ->
                            sheet = MapSheet.None
                            scope.launch {
                                runCatching { container.checkInRepository.checkIn(place, keys, GeoHash.encode(here.lat, here.lng, 5), note) }
                                    .onSuccess { say(context.getString(R.string.checkin_sent)) }
                            }
                        },
                        onStartTrip = { destination, minutes, keys ->
                            sheet = MapSheet.None
                            scope.launch {
                                container.checkInRepository.startTrip(destination, System.currentTimeMillis() / 1000 + minutes * 60L, keys, settings.areaName ?: "")
                                say(context.getString(R.string.trip_started))
                            }
                        },
                        onCancelTrip = { sheet = MapSheet.None; scope.launch { container.checkInRepository.cancelTrip() } },
                    )
                    MapSheet.None -> Unit
                }
            }
        }
    }
}

/** Lite list banner while no map pack is on the phone. */
@Composable
fun GetMapCard(onOpenPacks: () -> Unit) {
    val c = HarkaraTheme.colors
    HCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.map_get_title), style = HarkaraTheme.type.headline, color = c.textPrimary)
            Text(stringResource(R.string.map_get_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
            HButton(stringResource(R.string.map_get_button), onOpenPacks, variant = HButtonVariant.Secondary, height = 40.dp, icon = R.drawable.ic_map)
        }
    }
}

/** A person sharing with us: time left, distance and the meet-up arrow. */
@Composable
fun PersonCard(person: LivePerson, here: LatLng, now: Long, onArrow: () -> Unit) {
    val c = HarkaraTheme.colors
    val name = person.name ?: shortKey(person.owner)
    HCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(MapLayers.initials(name), size = 40.dp, live = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = HarkaraTheme.type.headline, color = c.textPrimary)
                    val distance = person.position?.let { GeoMath.distanceLabel(Heading.between(here, it).distanceMeters) }
                    Text(
                        listOfNotNull(stringResource(R.string.map_min_left, minutesLeft(person.end, now)), distance).joinToString(" · "),
                        style = HarkaraTheme.type.caption, color = c.textSecondary,
                    )
                }
            }
            HButton(stringResource(R.string.map_arrow), onArrow, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, icon = R.drawable.ic_bearing, height = 40.dp)
        }
    }
}

@Composable
private fun PostMiniCard(post: FeedPost?, here: LatLng) {
    val c = HarkaraTheme.colors
    post ?: return
    HCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(post.category?.label ?: stringResource(R.string.map_layer_posts), style = HarkaraTheme.type.label, color = c.textSecondary)
            Text(post.text, style = HarkaraTheme.type.body, color = c.textPrimary, maxLines = 3)
            Text(GeoMath.distanceLabel(GeoMath.distanceMeters(here, post.location)), style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
    }
}

/** A pin's actions: meet-up arrow, share as a card, edit (own pins). */
@Composable
fun PinDetailSheetContent(pv: PinView, here: LatLng, onArrow: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val h = Heading.between(here, pv.pin.position)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PinCard(pv.toUi(), Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.compass_to_target, GeoMath.distanceLabel(h.distanceMeters), h.cardinal, io.github.tanzir71.harkara.core.geo.Bearings.degrees(h.bearing)),
            style = HarkaraTheme.type.caption, color = c.textSecondary,
        )
        HButton(stringResource(R.string.map_arrow), onArrow, Modifier.fillMaxWidth(), icon = R.drawable.ic_bearing)
        HButton(stringResource(R.string.loc_share), onShare, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, icon = R.drawable.ic_send)
        if (pv.mine) HButton(stringResource(R.string.pin_edit_title), onEdit, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost)
    }
}

/** Me → Tools → My pins. */
@Composable
fun MyPinsHost(container: AppContainer, platform: PlatformActions, profilePubkey: String?, onBack: () -> Unit, say: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pins by remember(profilePubkey) { if (profilePubkey != null) container.pinRepository.pins() else flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val checkIns by remember(profilePubkey) { if (profilePubkey != null) container.checkInRepository.recent() else flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val trip by remember(profilePubkey) { if (profilePubkey != null) container.checkInRepository.activeTrip() else flowOf(null) }.collectAsStateWithLifecycle(null)
    MyPinsScreen(
        pins = pins,
        checkIns = checkIns,
        trip = trip,
        nameOf = { shortKey(it) },
        onBack = onBack,
        onOpen = { pv ->
            val card = LocationCardData.make(pv.pin.position, io.github.tanzir71.harkara.core.location.LocationPrecision.EXACT, null, pv.pin.title)
            platform.shareText(card.shareText())
        },
        onImport = {
            platform.openFile(arrayOf("application/gpx+xml", "application/vnd.google-earth.kml+xml", "application/geo+json", "application/json", "text/xml", "*/*")) { file, name ->
                if (file != null) scope.launch {
                    val text = runCatching { if (file.length() <= 4_000_000) file.readText() else "" }.getOrDefault("")
                    val n = container.pinRepository.importFile(name ?: file.name, text)
                    say(context.getString(R.string.pins_imported, n))
                }
            }
        },
        onExport = { format ->
            scope.launch {
                val text = container.pinRepository.export(format)
                platform.saveTextDocument("harkara-pins.${format.extension}", text) { ok -> if (ok) say(context.getString(R.string.pins_exported)) }
            }
        },
        modifier = modifier,
    )
}

/** Me → Tools → Map packs. Downloading starts only when the user taps a pack. */
@Composable
fun MapPacksHost(container: AppContainer, platform: PlatformActions, settings: SettingsState, onBack: () -> Unit, say: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = container.mapPackManager
    val state by manager.state.collectAsStateWithLifecycle()
    val installed = (state as? PackState.Idle)?.installed ?: manager.installed()
    val recommended = (state as? PackState.Available)?.let { manager.recommended(it.manifest, settings.isOn(Module.LITE_MODE)) }
    MapPacksScreen(
        state = state,
        installed = installed,
        recommended = recommended,
        onBack = onBack,
        onCheck = { scope.launch { manager.check() } },
        onDownload = { pack ->
            val available = state as? PackState.Available ?: return@MapPacksScreen
            scope.launch {
                runCatching { manager.download(available.release, available.manifest, pack) }.onSuccess {
                    container.settings.setModule(Module.OFFLINE_MAP, true)
                    say(context.getString(R.string.packs_ready))
                }
            }
        },
        onImport = {
            platform.openFile(arrayOf("application/octet-stream", "*/*")) { file, name ->
                if (file != null) scope.launch {
                    val ok = runCatching { manager.import(file, name?.lowercase()?.takeIf { it.endsWith(".pmtiles") } ?: io.github.tanzir71.harkara.core.map.MapPacks.DETAILED) }.isSuccess
                    file.delete()
                    if (ok) container.settings.setModule(Module.OFFLINE_MAP, true)
                    say(context.getString(if (ok) R.string.packs_ready else R.string.packs_fail_not_map))
                }
            }
        },
        onDelete = { manager.delete(); container.settings.setModule(Module.OFFLINE_MAP, false) },
        modifier = modifier,
    )
}

/** Me → Tools → Compass, and the meet-up arrow from the map. Sensor and GPS run only while this is open. */
@Composable
fun CompassHost(container: AppContainer, profilePubkey: String?, initialTarget: LatLng?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val fix by remember { container.locationSource.updates(5_000) }.collectAsStateWithLifecycle(container.locationSource.lastKnown())
    val here = fix?.position
    val heading by remember(here == null) { if (container.headingSource.available) container.headingSource.headings(here) else emptyFlow() }
        .collectAsStateWithLifecycle(null)
    val pins by remember(profilePubkey) { if (profilePubkey != null) container.pinRepository.pins() else flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val people by remember(profilePubkey) { if (profilePubkey != null) container.liveShareRepository.people() else flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val targets = people.mapNotNull { p -> p.position?.let { CompassTarget("person:${p.owner}", p.name ?: shortKey(p.owner), it) } } +
        pins.map { CompassTarget("pin:${it.pin.address}", it.pin.title, it.pin.position) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val initial = initialTarget?.let { t -> targets.minByOrNull { GeoMath.distanceMeters(it.position, t) }?.takeIf { GeoMath.distanceMeters(it.position, t) < 30 } }
    val selected = targets.firstOrNull { it.id == selectedId } ?: initial ?: initialTarget?.let { CompassTarget("target", "", it) }
    CompassScreen(heading, container.headingSource.available, here, targets, selected, { selectedId = it.id }, onBack, modifier)
}
