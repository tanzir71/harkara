package io.github.tanzir71.harkara.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.ConnectivityState

/** A tab whose milestone has not landed yet: title, pill and a calm description of what is coming. */
@Composable
fun UpcomingTabScreen(
    @StringRes title: Int,
    @DrawableRes icon: Int,
    @StringRes body: Int,
    connectivity: ConnectivityState,
    modifier: Modifier = Modifier,
    bangla: String? = null,
    pill: PillActions = PillActions(),
) {
    Column(modifier.fillMaxSize()) {
        val titleText = stringResource(title)
        TitleBar(titleText, connectivity, pill, bangla = bangla?.takeIf { it != titleText })
        CalmState(icon, stringResource(title), stringResource(body), tag = stringResource(R.string.coming_soon))
    }
}

@Composable
fun ChatsScreen(connectivity: ConnectivityState, modifier: Modifier = Modifier, pill: PillActions = PillActions()) =
    UpcomingTabScreen(R.string.tab_chats, R.drawable.ic_chats, R.string.chats_empty_body, connectivity, modifier, pill = pill)



