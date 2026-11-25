package io.github.tanzir71.harkara.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.identity.shortNpub
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.settings.SettingsState
import io.github.tanzir71.harkara.ui.components.Avatar
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HToggle
import io.github.tanzir71.harkara.ui.components.LogoMark
import io.github.tanzir71.harkara.ui.components.SectionHeader
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.update.LatestRelease

/** A tool on the Tools shelf; tools become live in M11. */
data class ToolItem(@DrawableRes val icon: Int, @StringRes val label: Int, val module: Module?)

val DefaultTools = listOf(
    ToolItem(R.drawable.ic_compass, R.string.tool_compass, Module.COMPASS),
    ToolItem(R.drawable.ic_qibla, R.string.tool_qibla, Module.QIBLA_PRAYER),
    ToolItem(R.drawable.ic_flashlight, R.string.tool_torch, Module.TORCH_TOOLS),
    ToolItem(R.drawable.ic_pin, R.string.tool_pins, Module.SAVED_PINS),
    ToolItem(R.drawable.ic_radar, R.string.tool_radar, Module.RADAR),
    ToolItem(R.drawable.ic_send, R.string.tool_send_file, Module.FILE_DROP),
    ToolItem(R.drawable.ic_handbook, R.string.tool_handbook, Module.HANDBOOK),
    ToolItem(R.drawable.ic_map, R.string.tool_map_packs, Module.OFFLINE_MAP),
)

/** Modules surfaced on the Me screen (screen 07); the full list lives in Settings → Modules. */
val MeScreenModules = listOf(Module.OFFLINE_MAP, Module.MESH, Module.QIBLA_PRAYER, Module.SMS_DIRECT, Module.LITE_MODE)

/** Screen 07: profile and keys, the Tools shelf, modules and settings. */
@Composable
fun MeScreen(
    settings: SettingsState,
    connectivity: ConnectivityState,
    modifier: Modifier = Modifier,
    pill: PillActions = PillActions(),
    update: LatestRelease? = null,
    onGetUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onTool: (ToolItem) -> Unit = {},
    onToggleModule: (Module, Boolean) -> Unit = { _, _ -> },
    onAllModules: () -> Unit = {},
    onSettings: () -> Unit = {},
    onLogoTripleTap: () -> Unit = {},
    npub: String? = null,
    onBackup: () -> Unit = {},
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TitleBar(stringResource(R.string.tab_me), connectivity, pill, leading = { LogoMark(size = 28.dp, onTripleTap = onLogoTripleTap) })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (update != null) UpdateCard(update, onGetUpdate, onDismissUpdate)
            HCard(Modifier.fillMaxWidth(), padding = PaddingValues(top = 16.dp, bottom = 4.dp)) {
                Column {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar("?", size = 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.me_anonymous), style = HarkaraTheme.type.headline, color = c.textPrimary)
                            Text(stringResource(R.string.me_anonymous_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
                            if (npub != null) {
                                Text(shortNpub(npub), style = HarkaraTheme.type.mono, color = c.textSecondary, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(c.borderSubtle))
                    SettingsRow(
                        R.drawable.ic_lock,
                        stringResource(R.string.me_backup),
                        subtitle = stringResource(R.string.me_backup_sub),
                        onClick = onBackup,
                        trailing = { HIcon(R.drawable.ic_chevron_right, tint = c.textTertiary, size = 18.dp) },
                    )
                }
            }
            SectionHeader(stringResource(R.string.me_tools), Modifier.padding(top = 8.dp), trailing = stringResource(R.string.me_tools_note))
            ToolGrid(DefaultTools, onTool)
            SectionHeader(
                stringResource(R.string.me_modules),
                Modifier.padding(top = 8.dp),
                trailing = stringResource(R.string.me_modules_note),
                trailingIcon = R.drawable.ic_battery,
            )
            HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                Column {
                    MeScreenModules.forEachIndexed { i, module ->
                        if (i > 0) RowDivider()
                        ModuleRow(module, settings.isOn(module), onToggleModule)
                    }
                    RowDivider()
                    SettingsRow(R.drawable.ic_sliders, stringResource(R.string.me_all_modules), onClick = onAllModules, trailing = {
                        HIcon(R.drawable.ic_chevron_right, tint = c.textTertiary, size = 18.dp)
                    })
                }
            }
            HCard(Modifier.fillMaxWidth().padding(top = 8.dp), padding = PaddingValues(vertical = 4.dp)) {
                SettingsRow(R.drawable.ic_more, stringResource(R.string.me_settings), onClick = onSettings, trailing = {
                    HIcon(R.drawable.ic_chevron_right, tint = c.textTertiary, size = 18.dp)
                })
            }
        }
    }
}

@Composable
private fun ToolGrid(tools: List<ToolItem>, onTool: (ToolItem) -> Unit) {
    val c = HarkaraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tools.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { tool ->
                    val label = stringResource(tool.label)
                    Column(
                        Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = label) { onTool(tool) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(c.bgSurface).border(1.dp, c.borderSubtle, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            HIcon(tool.icon, size = 22.dp)
                        }
                        Text(label, style = HarkaraTheme.type.label, color = c.textSecondary, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** One module switch row; modules whose milestone hasn't landed carry a "Coming soon" tag. */
@Composable
fun ModuleRow(module: Module, on: Boolean, onToggle: (Module, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle(module, it) })
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HIcon(module.icon, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(module.title), style = HarkaraTheme.type.body.copy(fontSize = HarkaraTheme.type.headline.fontSize), color = c.textPrimary)
            Text(stringResource(module.subtitle), style = HarkaraTheme.type.caption, color = c.textSecondary)
            if (!module.ready) ComingSoonTag(Modifier.padding(top = 2.dp))
        }
        HToggle(on, onCheckedChange = null)
    }
}

@Composable
private fun ComingSoonTag(modifier: Modifier) {
    Row(modifier) { ComingSoonTag(stringResource(R.string.coming_soon)) }
}
