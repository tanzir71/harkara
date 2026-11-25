package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.settings.AppLanguage
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.settings.SettingsState
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.SectionHeader
import io.github.tanzir71.harkara.ui.components.Segmented
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

/** Settings → Modules: every optional module from §4.1, persisted. */
@Composable
fun ModulesScreen(
    settings: SettingsState,
    onToggle: (Module, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.me_modules), onBack)
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item { SectionHeader(stringResource(R.string.me_modules_note), trailingIcon = R.drawable.ic_battery) }
            item {
                HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                    Column {
                        Module.entries.forEachIndexed { i, module ->
                            if (i > 0) RowDivider()
                            ModuleRow(module, settings.isOn(module), onToggle)
                        }
                    }
                }
            }
        }
    }
}

/** Settings: language, sharing, crash report, panic wipe, about. */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    versionName: String,
    onBack: () -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onShare: () -> Unit,
    onCopyCrash: () -> Unit,
    onPanicWipe: () -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
    onRelays: () -> Unit = {},
    onEmergencyAlerts: (Boolean) -> Unit = {},
    /** Paste a link, QR text or SMS (M10). */
    onPasteLink: (() -> Unit)? = null,
    /** The volunteer SMS gateway page, shown while that switch is on (M10). */
    onSmsGateway: (() -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackBar(stringResource(R.string.me_settings), onBack)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.settings_language))
            Segmented(
                listOf(stringResource(R.string.lang_system), stringResource(R.string.lang_en), stringResource(R.string.lang_bn)),
                AppLanguage.entries.indexOf(settings.language),
                { onLanguage(AppLanguage.entries[it]) },
            )
            HCard(Modifier.fillMaxWidth().padding(top = 8.dp), padding = PaddingValues(vertical = 4.dp)) {
                Column {
                    SettingsRow(R.drawable.ic_sos, stringResource(R.string.settings_emergency_alerts), subtitle = stringResource(R.string.settings_emergency_alerts_body), onClick = { onEmergencyAlerts(!settings.emergencyAlerts) }, trailing = { io.github.tanzir71.harkara.ui.components.HToggle(settings.emergencyAlerts, onCheckedChange = null) })
                    RowDivider()
                    SettingsRow(R.drawable.ic_relay, stringResource(R.string.relays_title), subtitle = stringResource(R.string.relays_body), onClick = onRelays)
                    RowDivider()
                    SettingsRow(R.drawable.ic_drop, stringResource(R.string.settings_share), subtitle = stringResource(R.string.settings_share_body), onClick = onShare)
                    RowDivider()
                    SettingsRow(R.drawable.ic_report, stringResource(R.string.settings_crash), subtitle = stringResource(R.string.settings_crash_body), onClick = onCopyCrash)
                    RowDivider()
                    SettingsRow(
                        R.drawable.ic_sos,
                        stringResource(R.string.settings_panic),
                        subtitle = stringResource(R.string.settings_panic_body),
                        onClick = onPanicWipe,
                        titleColor = c.stateSos,
                    )
                }
            }
            if (onPasteLink != null || onSmsGateway != null) {
                HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                    Column {
                        onPasteLink?.let { SettingsRow(R.drawable.ic_qr, stringResource(R.string.settings_paste_link), subtitle = stringResource(R.string.settings_paste_link_sub), onClick = it) }
                        if (onPasteLink != null && onSmsGateway != null) RowDivider()
                        onSmsGateway?.let { SettingsRow(R.drawable.ic_relay, stringResource(R.string.gateway_title), subtitle = stringResource(R.string.grey_cost_gateway), onClick = it) }
                    }
                }
            }
            HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                SettingsRow(
                    R.drawable.ic_verified,
                    stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.settings_version, versionName) + " · " + stringResource(R.string.settings_licence),
                    onClick = onOpenSource,
                )
            }
        }
    }
}

