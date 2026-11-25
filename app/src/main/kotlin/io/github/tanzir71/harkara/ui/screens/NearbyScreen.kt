package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.ConnectivityPill
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HFab
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HIconButton
import io.github.tanzir71.harkara.ui.components.PostCard
import io.github.tanzir71.harkara.ui.components.PostUi
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.components.categoryColor
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.update.LatestRelease

/**
 * Screen 01/02: the Nearby feed with its Feed ⇄ Map switch. [mapContent] draws Map mode below the header
 * (it gets the Feed/Map switch to place over the map); without it Map mode shows a note above the list.
 */
@Composable
fun NearbyScreen(
    areaName: String?,
    areaAuto: Boolean,
    connectivity: ConnectivityState,
    posts: List<PostUi>,
    modifier: Modifier = Modifier,
    pill: PillActions = PillActions(),
    update: LatestRelease? = null,
    onGetUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onCompose: () -> Unit = {},
    onChangeArea: () -> Unit = {},
    onSearch: () -> Unit = {},
    onVote: (postId: String, up: Boolean) -> Unit = { _, _ -> },
    onMore: (postId: String) -> Unit = {},
    alerts: (@Composable () -> Unit)? = null,
    initialMode: Int = 0,
    mapContent: (@Composable (modifier: Modifier, modeSwitch: @Composable () -> Unit) -> Unit)? = null,
    onReply: ((postId: String) -> Unit)? = null,
    onMakeTask: ((postId: String) -> Unit)? = null,
    onShare: ((postId: String) -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    var mode by rememberSaveable { mutableIntStateOf(initialMode) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = posts.filter { filter == null || it.category?.tag == filter }

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onChangeArea)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        areaName ?: stringResource(R.string.nearby_near_you),
                        style = HarkaraTheme.type.title.copy(fontSize = HarkaraTheme.type.amount.fontSize),
                        color = c.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.width(6.dp))
                    HIcon(R.drawable.ic_chevron_down, size = 20.dp, contentDescription = stringResource(R.string.nearby_change_area))
                }
                Text(
                    stringResource(R.string.nearby_within, stringResource(if (areaAuto) R.string.nearby_auto else R.string.nearby_chosen)),
                    style = HarkaraTheme.type.caption,
                    color = c.textSecondary,
                )
            }
            ConnectivityPill(connectivity.best, meshPeers = connectivity.meshPeers, onClick = pill.onClick, onLongClick = pill.onLongClick)
        }
    }
    val modeRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf(stringResource(R.string.nearby_feed), stringResource(R.string.nearby_map)), mode, { mode = it })
            Spacer(Modifier.weight(1f))
            HIconButton(R.drawable.ic_search, stringResource(R.string.nearby_search), onSearch)
        }
    }

    if (mode == 1 && mapContent != null) {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.padding(bottom = 10.dp)) { header() }
            if (alerts != null) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { alerts() }
            if (update != null) UpdateCard(update, onGetUpdate, onDismissUpdate, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            mapContent(Modifier.weight(1f), modeRow)
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { header() }
            item { modeRow() }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(stringResource(R.string.nearby_all), selected = filter == null, onClick = { filter = null })
                    Category.entries.forEach { cat ->
                        Chip(cat.label, selected = filter == cat.tag, dot = c.categoryColor(cat), onClick = { filter = if (filter == cat.tag) null else cat.tag })
                    }
                }
            }
            if (alerts != null) item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { alerts() } }
            if (update != null) {
                item { UpdateCard(update, onGetUpdate, onDismissUpdate, Modifier.padding(horizontal = 16.dp)) }
            }
            if (mode == 1) {
                item {
                    HCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HIcon(R.drawable.ic_map, tint = c.textSecondary, size = 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.nearby_map_fallback), style = HarkaraTheme.type.caption, color = c.textSecondary)
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item { CalmState(R.drawable.ic_nearby, stringResource(R.string.nearby_empty_title), stringResource(R.string.nearby_empty_body)) }
            } else {
                items(shown, key = { it.id }) { post ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val reply = if (onReply != null) Modifier.clickable(role = Role.Button) { onReply(post.id) } else Modifier
                        PostCard(post, Modifier.padding(horizontal = 16.dp).then(reply), onVoteUp = { onVote(post.id, true) }, onVoteDown = { onVote(post.id, false) }, onMore = { onMore(post.id) })
                        if (onMakeTask != null || onShare != null) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (onMakeTask != null) HButton(stringResource(R.string.message_make_task), { onMakeTask(post.id) }, Modifier.weight(1f), variant = HButtonVariant.Ghost, icon = R.drawable.ic_tasks)
                                if (onShare != null) HButton(stringResource(R.string.location_share), { onShare(post.id) }, Modifier.weight(1f), variant = HButtonVariant.Ghost)
                            }
                        }
                    }
                }
            }
        }
        HFab(
            R.drawable.ic_plus,
            stringResource(R.string.nearby_post),
            onCompose,
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}
