package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.sms.GatewayCard
import io.github.tanzir71.harkara.core.sms.GatewayConfig
import io.github.tanzir71.harkara.core.sms.GatewayMember
import io.github.tanzir71.harkara.core.sms.GatewayPolicy
import io.github.tanzir71.harkara.settings.Module
import io.github.tanzir71.harkara.sms.SmsComposer
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HCard
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.components.QrCode
import io.github.tanzir71.harkara.ui.components.SectionHeader
import io.github.tanzir71.harkara.ui.components.Tag
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

/**
 * "Send by SMS": the message as it will be sent (a Latin line + the HK1 payload), how many SMS it takes,
 * and the default way to send it — the user's own SMS app. With "Send SMS directly" on, a number field
 * sends it as data SMS instead; with a saved volunteer gateway, one tap addresses it to the gateway.
 */
@Composable
fun SendSmsSheetContent(
    draft: SmsComposer.Draft,
    onOpenSmsApp: (String) -> Unit,
    onShowQr: () -> Unit,
    modifier: Modifier = Modifier,
    directOn: Boolean = false,
    onSendDirect: (number: String) -> Unit = {},
    gateway: GatewayCard? = null,
    onSendToGateway: (String) -> Unit = {},
    result: String? = null,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.sms_send_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        when (draft) {
            is SmsComposer.Draft.Ready -> {
                Text(stringResource(R.string.sms_send_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
                HCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(draft.text, style = HarkaraTheme.type.mono, color = c.textPrimary, maxLines = 6)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HIcon(R.drawable.ic_sms, tint = c.stateSms, size = 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sms_send_parts, draft.parts, draft.text.length), style = HarkaraTheme.type.label, color = c.textSecondary)
                        }
                    }
                }
                HButton(stringResource(R.string.sms_open_app), { onOpenSmsApp(draft.text) }, Modifier.fillMaxWidth(), height = 52.dp, icon = R.drawable.ic_sms)
                if (gateway != null) {
                    HButton(stringResource(R.string.sms_send_gateway, gateway.name), { onSendToGateway(draft.text) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Secondary, icon = R.drawable.ic_relay)
                }
                if (directOn) {
                    var number by remember { mutableStateOf("") }
                    SectionHeader(stringResource(R.string.sms_direct_title), Modifier.padding(top = 4.dp))
                    Tag(stringResource(R.string.grey_cost_direct), dot = c.stateSms)
                    HTextField(number, { v -> number = v.filter { it.isDigit() || it == '+' }.take(16) }, stringResource(R.string.sms_direct_number), mono = true)
                    HButton(
                        stringResource(R.string.sms_direct_send), { onSendDirect(number) }, Modifier.fillMaxWidth(),
                        variant = HButtonVariant.Secondary, enabled = GatewayPolicy.normalize(number).length >= 5,
                    )
                }
            }
            SmsComposer.Draft.NotEligible -> Text(stringResource(R.string.sms_not_eligible), style = HarkaraTheme.type.body, color = c.textPrimary)
            is SmsComposer.Draft.TooLong -> Text(stringResource(R.string.sms_too_long), style = HarkaraTheme.type.body, color = c.textPrimary)
        }
        if (result != null) Text(result, style = HarkaraTheme.type.caption, color = c.textSecondary)
        HButton(stringResource(R.string.sms_show_qr), onShowQr, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost, icon = R.drawable.ic_qr)
    }
}

/** Share any event as QR: one code, or "code 2 of 3" for longer events (the reader reassembles them). */
@Composable
fun EventQrSheetContent(codes: List<String>, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    var index by remember(codes) { mutableIntStateOf(0) }
    val code = codes.getOrNull(index) ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.qr_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        QrCode(code, stringResource(R.string.qr_title), Modifier.size(260.dp))
        if (codes.size > 1) {
            Tag(stringResource(R.string.qr_part, index + 1, codes.size))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HButton(stringResource(R.string.qr_prev), { index = (index - 1).coerceAtLeast(0) }, Modifier.weight(1f), variant = HButtonVariant.Secondary, enabled = index > 0)
                HButton(stringResource(R.string.qr_next), { index = (index + 1).coerceAtMost(codes.size - 1) }, Modifier.weight(1f), enabled = index < codes.size - 1)
            }
        }
        Text(stringResource(R.string.qr_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HButton(stringResource(R.string.qr_copy), { onCopy(code) }, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost, icon = R.drawable.ic_drop)
    }
}

/** Scan or paste a link, QR text or forwarded SMS; import remains an explicit action. */
@Composable
fun PasteLinkSheetContent(onRead: (String) -> Unit, modifier: Modifier = Modifier, message: String? = null, scanEnabled: Boolean = true, cameraFactory: io.github.tanzir71.harkara.tools.QrCameraFactory = io.github.tanzir71.harkara.tools.QrCameraFactory.Android) {
    val c = HarkaraTheme.colors
    var text by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.link_paste_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        if (scanEnabled) QrScanControl({ text = it }, cameraFactory = cameraFactory)
        HTextField(text, { text = it.take(8000) }, stringResource(R.string.link_paste_label), mono = true, singleLine = false)
        if (message != null) Text(message, style = HarkaraTheme.type.caption, color = c.textSecondary)
        HButton(stringResource(R.string.link_paste_read), { onRead(text); text = "" }, Modifier.fillMaxWidth(), height = 52.dp, enabled = text.isNotBlank())
    }
}

/** Turning on a grey-area switch: what it does and what it costs, then the permission prompt. */
@Composable
fun GreyAreaSheetContent(module: Module, onTurnOn: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val gateway = module == Module.SMS_GATEWAY
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(module.icon, tint = c.stateSms, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (gateway) R.string.grey_gateway_title else R.string.grey_direct_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        }
        Tag(stringResource(if (gateway) R.string.grey_cost_gateway else R.string.grey_cost_direct), dot = c.stateSms)
        Text(stringResource(if (gateway) R.string.grey_gateway_body else R.string.grey_direct_body), style = HarkaraTheme.type.body, color = c.textPrimary)
        Text(stringResource(R.string.grey_permission_note), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HButton(stringResource(R.string.grey_turn_on), onTurnOn, Modifier.fillMaxWidth(), height = 52.dp)
        HButton(stringResource(R.string.grey_not_now), onCancel, Modifier.fillMaxWidth(), variant = HButtonVariant.Ghost)
    }
}

/** Android 13+ blocks SMS permissions for sideloaded apps until the user allows restricted settings. */
@Composable
fun RestrictedSettingsScreen(onOpenAppInfo: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.restricted_title), onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.restricted_body), style = HarkaraTheme.type.body, color = c.textPrimary)
            HCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(R.string.restricted_step1, R.string.restricted_step2, R.string.restricted_step3, R.string.restricted_step4).forEachIndexed { i, step ->
                        Row {
                            Text("%02d".format(i + 1), style = HarkaraTheme.type.mono, color = c.accentText)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(step), style = HarkaraTheme.type.body, color = c.textPrimary)
                        }
                    }
                }
            }
            Text(stringResource(R.string.restricted_no_menu), style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
        HButton(stringResource(R.string.restricted_open), onOpenAppInfo, Modifier.fillMaxWidth().padding(16.dp), height = 52.dp)
    }
}

/** The volunteer's gateway: today's count, who it relays for, and the QR that shares the number. */
@Composable
fun SmsGatewayScreen(
    config: GatewayConfig,
    sentToday: Int,
    onSave: (GatewayConfig) -> Unit,
    onShowCard: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var group by remember(config.groupName) { mutableStateOf(config.groupName) }
    var own by remember(config.ownNumber) { mutableStateOf(config.ownNumber) }
    Column(modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.gateway_title), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tag(stringResource(R.string.grey_cost_gateway), dot = c.stateSms)
            Text(stringResource(R.string.gateway_today, sentToday, GatewayPolicy.DAILY_CAP), style = HarkaraTheme.type.headline, color = c.textPrimary)
            HTextField(group, { group = it.take(40) }, stringResource(R.string.gateway_group))
            HTextField(own, { v -> own = v.filter { it.isDigit() || it == '+' }.take(16) }, stringResource(R.string.gateway_own_number), mono = true)
            if (group != config.groupName || own != config.ownNumber) {
                HButton(stringResource(R.string.gateway_save_group), { onSave(config.copy(groupName = group.trim(), ownNumber = own)) }, variant = HButtonVariant.Secondary)
            }
            SectionHeader(stringResource(R.string.gateway_members))
            if (config.members.isEmpty()) {
                Text(stringResource(R.string.gateway_empty), style = HarkaraTheme.type.caption, color = c.textSecondary)
            } else {
                HCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                    Column {
                        config.members.forEachIndexed { i, m ->
                            if (i > 0) RowDivider()
                            SettingsRow(R.drawable.ic_me, m.name, subtitle = m.number, trailing = {
                                HButton(stringResource(R.string.gateway_remove), { onSave(config.copy(members = config.members - m)) }, variant = HButtonVariant.Ghost, height = 40.dp)
                            })
                        }
                    }
                }
            }
            HTextField(name, { v -> name = v.filterNot(Char::isDigit).take(40) }, stringResource(R.string.gateway_add_name))
            HTextField(number, { v -> number = v.filter { it.isDigit() || it == '+' }.take(16) }, stringResource(R.string.gateway_add_number), mono = true)
            HTextField(key, { key = it.trim().take(100) }, stringResource(R.string.gateway_add_key), mono = true)
            HButton(
                stringResource(R.string.gateway_add),
                {
                    val pubkey = key.takeIf { it.startsWith("npub1") }?.let { runCatching { io.github.tanzir71.harkara.core.nostr.Bech32.decodeNpub(it) }.getOrNull() }
                    onSave(config.copy(members = config.members + GatewayMember(name.trim(), number, pubkey)))
                    name = ""; number = ""; key = ""
                },
                Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && GatewayPolicy.normalize(number).length >= 5,
            )
        }
        HButton(
            stringResource(R.string.gateway_card), onShowCard, Modifier.fillMaxWidth().padding(16.dp), height = 52.dp, icon = R.drawable.ic_qr,
            enabled = GatewayPolicy.normalize(config.ownNumber).length >= 5,
        )
    }
}

/** The volunteer's gateway card as a QR: the only way anyone learns the number. */
@Composable
fun GatewayCardSheetContent(card: GatewayCard, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.gateway_card), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
        QrCode(card.toLink(), stringResource(R.string.gateway_card), Modifier.size(240.dp))
        Text(stringResource(R.string.gateway_card_body), style = HarkaraTheme.type.caption, color = c.textSecondary)
    }
}
