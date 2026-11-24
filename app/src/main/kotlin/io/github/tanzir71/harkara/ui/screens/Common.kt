package io.github.tanzir71.harkara.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.ui.components.ConnectivityPill
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HIconButton
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.update.LatestRelease

/** Callbacks for the connectivity pill (tap = sheet, 3 s hold = SOS once that module lands). */
data class PillActions(val onClick: () -> Unit = {}, val onLongClick: (() -> Unit)? = null)

/** Screen title row with an optional Bangla mark and the connectivity pill on the right. */
@Composable
fun TitleBar(
    title: String,
    connectivity: ConnectivityState,
    pill: PillActions,
    modifier: Modifier = Modifier,
    bangla: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = HarkaraTheme.type.display.copy(fontSize = HarkaraTheme.type.title.fontSize * 1.1f), color = c.textPrimary, modifier = Modifier.semantics { heading() })
        if (bangla != null) {
            Spacer(Modifier.width(8.dp))
            Text(bangla, style = HarkaraTheme.type.banglaLabel, color = c.textTertiary)
        }
        Spacer(Modifier.weight(1f))
        ConnectivityPill(connectivity.best, meshPeers = connectivity.meshPeers, onClick = pill.onClick, onLongClick = pill.onLongClick)
    }
}

/** Back-arrow header for pushed screens. */
@Composable
fun BackBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HIconButton(R.drawable.ic_arrow_left, stringResource(R.string.back), onBack, background = c.bgCanvas)
        Spacer(Modifier.width(4.dp))
        Text(title, style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    }
}

/** Calm empty/off state: icon in a sunken disc, title, one line. No nagging. */
@Composable
fun CalmState(@DrawableRes icon: Int, title: String, body: String, modifier: Modifier = Modifier, tag: String? = null) {
    val c = HarkaraTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
            HIcon(icon, tint = c.textSecondary, size = 28.dp)
        }
        Text(title, style = HarkaraTheme.type.headline, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(body, style = HarkaraTheme.type.caption, color = c.textSecondary, textAlign = TextAlign.Center)
        if (tag != null) ComingSoonTag(tag)
    }
}

@Composable
fun ComingSoonTag(text: String = stringResource(R.string.coming_soon), modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Text(
        text,
        style = HarkaraTheme.type.micro,
        color = c.accentText,
        modifier = modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** In-app update card (§5.5). */
@Composable
fun UpdateCard(release: LatestRelease, onGet: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    HCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoDot()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_title, release.version), style = HarkaraTheme.type.headline, color = c.textPrimary)
            }
            Text(stringResource(R.string.update_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton(stringResource(R.string.update_get), onGet, variant = HButtonVariant.Primary, height = 40.dp)
                HButton(stringResource(R.string.update_later), onLater, variant = HButtonVariant.Ghost, height = 40.dp)
            }
        }
    }
}

@Composable
private fun LogoDot() {
    Box(Modifier.size(10.dp).clip(CircleShape).background(HarkaraTheme.colors.accentFill))
}

/** A tappable settings row with icon, title, subtitle and an optional trailing slot. */
@Composable
fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: androidx.compose.ui.graphics.Color = HarkaraTheme.colors.textPrimary,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HIcon(icon, tint = titleColor, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = HarkaraTheme.type.body.copy(fontSize = HarkaraTheme.type.headline.fontSize), color = titleColor)
            if (subtitle != null) Text(subtitle, style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Hairline divider between rows in a grouped card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.padding(start = 50.dp).fillMaxWidth().height(1.dp).background(HarkaraTheme.colors.borderSubtle))
}
