package io.github.tanzir71.harkara.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.ui.theme.HarkaraColors
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

/** One of the 46 handover icons, tinted (defaults to primary ink). */
@Composable
fun HIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = HarkaraTheme.colors.textPrimary,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** Colour of the state token for a carrier. */
fun HarkaraColors.carrierColor(carrier: Carrier): Color = when (carrier) {
    Carrier.ONLINE -> stateOnline
    Carrier.BRIDGE -> stateBridge
    Carrier.NODE -> stateNode
    Carrier.MESH -> stateMesh
    Carrier.SMS -> stateSms
    Carrier.QR -> textPrimary
    Carrier.SAVED -> stateQueued
}

/** Glyph for a carrier. */
@DrawableRes
fun carrierIcon(carrier: Carrier): Int = when (carrier) {
    Carrier.ONLINE -> R.drawable.ic_relay
    Carrier.BRIDGE -> R.drawable.ic_bridge
    Carrier.NODE -> R.drawable.ic_node
    Carrier.MESH -> R.drawable.ic_mesh
    Carrier.SMS -> R.drawable.ic_sms
    Carrier.QR -> R.drawable.ic_qr
    Carrier.SAVED -> R.drawable.ic_queued
}

/** Pill label for a carrier, e.g. "Nearby only · 12". */
@Composable
fun carrierPillLabel(carrier: Carrier, meshPeers: Int = 0): String = when (carrier) {
    Carrier.ONLINE -> stringResource(R.string.pill_online)
    Carrier.BRIDGE -> stringResource(R.string.pill_bridge)
    Carrier.NODE -> stringResource(R.string.pill_node)
    Carrier.MESH -> stringResource(R.string.pill_mesh, meshPeers)
    Carrier.SMS -> stringResource(R.string.pill_sms)
    Carrier.QR -> stringResource(R.string.pill_qr)
    Carrier.SAVED -> stringResource(R.string.pill_saved)
}

/** Connectivity pill: always names the highest rung that can reach people now. */
@Composable
fun ConnectivityPill(
    carrier: Carrier,
    modifier: Modifier = Modifier,
    meshPeers: Int = 0,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    val label = carrierPillLabel(carrier, meshPeers)
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(c.bgSurface)
            .border(1.dp, c.borderDefault, CircleShape)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = label }
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.carrierColor(carrier)))
        HIcon(carrierIcon(carrier), tint = c.carrierColor(carrier), size = 16.dp)
        Text(label, style = HarkaraTheme.type.label.copy(fontSize = Tokens.Text.caption.sizeSp), color = c.textPrimary, maxLines = 1)
    }
}

/** Filter/choice chip: off = surface with hairline, on = ink. Optional category dot. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = HarkaraTheme.colors
    val bg = if (selected) c.bgInverse else c.bgSurface
    val fg = if (selected) c.textInverse else c.textPrimary
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) c.bgInverse else c.borderDefault, CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(label, style = HarkaraTheme.type.label.copy(fontSize = Tokens.Text.caption.sizeSp), color = fg, maxLines = 1)
    }
}

/** Small tag inside cards, e.g. "2 h 48 m left". */
@Composable
fun Tag(label: String, modifier: Modifier = Modifier, dot: Color? = null) {
    val c = HarkaraTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(c.bgSunken)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(label, style = HarkaraTheme.type.label, color = c.textSecondary, maxLines = 1)
    }
}

/** Button variants from the components board. */
enum class HButtonVariant { Primary, Secondary, Accent, Ghost }

/** Harkara button. Primary actions live in the bottom third of the screen. */
@Composable
fun HButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: HButtonVariant = HButtonVariant.Primary,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val c = HarkaraTheme.colors
    val (bg, fg, border) = when (variant) {
        HButtonVariant.Primary -> Triple(c.bgInverse, c.textInverse, null)
        HButtonVariant.Secondary -> Triple(c.bgSurface, c.textPrimary, c.borderDefault)
        HButtonVariant.Accent -> Triple(c.accentFill, c.textOnAccent, null)
        HButtonVariant.Ghost -> Triple(Color.Transparent, c.textPrimary, null)
    }
    val shape = RoundedCornerShape(Tokens.Radius.md)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = height)
            .clip(shape)
            .background(if (enabled) bg else c.bgSunken)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) HIcon(icon, tint = if (enabled) fg else c.textTertiary, size = 20.dp)
        Text(
            text,
            style = HarkaraTheme.type.bodyStrong,
            color = if (enabled) fg else c.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Round icon button (search, locate, compass). */
@Composable
fun HIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    background: Color = HarkaraTheme.colors.bgSurface,
    tint: Color = HarkaraTheme.colors.textPrimary,
) {
    val c = HarkaraTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, c.borderSubtle, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        HIcon(icon, tint = tint, size = 22.dp)
    }
}

/** Floating accent action, e.g. "+" on Nearby or "New IOU" on Khata. */
@Composable
fun HFab(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val c = HarkaraTheme.colors
    val shape = RoundedCornerShape(Tokens.Radius.lg)
    Row(
        modifier = modifier
            .shadow(Tokens.Elevation.float, shape, ambientColor = c.bgInverse, spotColor = c.bgInverse)
            .clip(shape)
            .background(c.accentFill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .heightIn(min = 56.dp)
            .padding(horizontal = if (text == null) 16.dp else 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HIcon(icon, tint = c.textOnAccent, size = 24.dp)
        if (text != null) Text(text, style = HarkaraTheme.type.headline, color = c.textOnAccent)
    }
}

/** Switch: off = sunken track, on = ink track with a mustard knob. */
@Composable
fun HToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = HarkaraTheme.colors
    val knobOffset by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 48.dp)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 24.dp)
                .clip(CircleShape)
                .background(if (checked) c.bgInverse else c.bgSunken)
                .border(1.dp, if (checked) c.bgInverse else c.borderDefault, CircleShape)
                .padding(2.dp),
        ) {
            Box(
                Modifier
                    .offset(x = knobOffset)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) c.accentFill else c.bgSurface),
            )
        }
    }
}

/** "Sumon Ahmed" → "SA", "Rafi" → "RA". */
fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (words.size >= 2) "${words[0].first()}${words[1].first()}" else name.trim().take(2)
}

/** Initials avatar; a live ring in state/online when the person is sharing location. */
@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: Dp = 40.dp, live: Boolean = false) {
    val c = HarkaraTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(c.accentSoft)
            .then(if (live) Modifier.border(2.dp, c.stateOnline, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials.take(2).uppercase(),
            style = HarkaraTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
            color = c.accentText,
        )
    }
}

/** Two- or three-way segmented control (Feed | Map, Saver | Event). */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(c.bgSunken)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) c.bgSurface else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = HarkaraTheme.type.label.copy(fontSize = Tokens.Text.caption.sizeSp),
                    color = if (isSelected) c.textPrimary else c.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Delivery states shown on every message. */
enum class Delivery { Saved, LeftPhone, ReachedNetwork, Delivered, Read }

/** Delivery ticks: clock → one tick → tick in carrier colour → double tick → double tick in online. */
@Composable
fun DeliveryTicks(state: Delivery, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val (icon, tint, label) = when (state) {
        Delivery.Saved -> Triple(R.drawable.ic_queued, c.stateQueued, R.string.delivery_saved)
        Delivery.LeftPhone -> Triple(R.drawable.ic_check, c.textTertiary, R.string.delivery_left)
        Delivery.ReachedNetwork -> Triple(R.drawable.ic_check, c.textSecondary, R.string.delivery_network)
        Delivery.Delivered -> Triple(R.drawable.ic_check_double, c.textSecondary, R.string.delivery_delivered)
        Delivery.Read -> Triple(R.drawable.ic_check_double, c.stateOnline, R.string.delivery_read)
    }
    HIcon(icon, modifier = modifier, tint = tint, size = 14.dp, contentDescription = stringResource(label))
}

/** Bottom tab destinations. */
enum class HarkaraTab(@DrawableRes val icon: Int, val label: Int) {
    Nearby(R.drawable.ic_nearby, R.string.tab_nearby),
    Chats(R.drawable.ic_chats, R.string.tab_chats),
    Tasks(R.drawable.ic_tasks, R.string.tab_tasks),
    Khata(R.drawable.ic_khata, R.string.tab_khata),
    Me(R.drawable.ic_me, R.string.tab_me),
}

/** Five-tab bar; the active tab sits in an accent/soft pill. */
@Composable
fun TabBar(selected: HarkaraTab, onSelect: (HarkaraTab) -> Unit, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth().background(c.bgSurface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            HarkaraTab.entries.forEach { tab ->
                val active = tab == selected
                val label = stringResource(tab.label)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 52.dp, height = 30.dp)
                            .clip(CircleShape)
                            .background(if (active) c.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        HIcon(tab.icon, tint = if (active) c.textPrimary else c.textSecondary, size = 22.dp)
                    }
                    Text(
                        label,
                        style = HarkaraTheme.type.label.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (active) c.textPrimary else c.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Bottom sheet surface: rounded top, grab handle, surface colour. Used inside modal sheets and screenshots. */
@Composable
fun SheetSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = HarkaraTheme.colors
    val shape = RoundedCornerShape(topStart = Tokens.Radius.xl, topEnd = Tokens.Radius.xl)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(Tokens.Elevation.sheet, shape)
            .clip(shape)
            .background(c.bgRaised)
            .padding(PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.borderStrong))
        }
        content()
    }
}

/** Card container shared by every card component. */
@Composable
fun HCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Tokens.Space.s4),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = HarkaraTheme.colors
    val shape = RoundedCornerShape(Tokens.Radius.lg)
    Box(
        modifier
            .shadow(Tokens.Elevation.card, shape, ambientColor = ShadowInk, spotColor = ShadowInk)
            .clip(shape)
            .background(c.bgSurface)
            .border(BorderStroke(1.dp, c.borderSubtle), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
    ) { content() }
}

/** rgba(15,18,20) from the elevation tokens. */
private val ShadowInk = Color(0xFF0F1214)

/** Section header with an optional trailing note, e.g. "Tools · Work with no signal". */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null, @DrawableRes trailingIcon: Int? = null) {
    val c = HarkaraTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = HarkaraTheme.type.bodyStrong.copy(fontSize = Tokens.Text.caption.sizeSp), color = c.textSecondary)
        Spacer(Modifier.weight(1f))
        if (trailingIcon != null) {
            HIcon(trailingIcon, tint = c.textTertiary, size = 14.dp)
            Spacer(Modifier.width(4.dp))
        }
        if (trailing != null) Text(trailing, style = HarkaraTheme.type.label, color = c.textTertiary)
    }
}
