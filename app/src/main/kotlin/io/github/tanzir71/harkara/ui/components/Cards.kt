package io.github.tanzir71.harkara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.ui.theme.HarkaraColors
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

/** Category colour token. */
fun HarkaraColors.categoryColor(category: Category): Color = when (category) {
    Category.TRAFFIC -> catTraffic
    Category.FOOD -> catFood
    Category.CROWD -> catCrowd
    Category.STOCK -> catStock
    Category.ALERT -> catAlert
    Category.CHATTER -> catChatter
}

/** "via relays" style line: carrier glyph in its colour plus a quiet label. */
@Composable
fun CarrierLine(carrier: Carrier, label: String, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HIcon(carrierIcon(carrier), tint = c.carrierColor(carrier), size = 14.dp)
        Text(label, style = HarkaraTheme.type.label, color = c.textSecondary, maxLines = 1)
    }
}

/** A Nearby feed post as rendered in a card. */
data class PostUi(
    val id: String,
    val category: Category?,
    val place: String,
    val text: String,
    val detail: String? = null,
    val expiryLeft: String? = null,
    val score: Int = 0,
    val comments: Int = 0,
    val carrier: Carrier = Carrier.ONLINE,
    val carrierLabel: String = "",
)

@Composable
fun PostCard(
    post: PostUi,
    modifier: Modifier = Modifier,
    onVoteUp: () -> Unit = {},
    onVoteDown: () -> Unit = {},
    onMore: () -> Unit = {},
) {
    val c = HarkaraTheme.colors
    val t = HarkaraTheme.type
    HCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.category != null) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.categoryColor(post.category)))
                    Spacer(Modifier.width(6.dp))
                    Text(post.category.label, style = t.label.copy(fontSize = Tokens.Text.caption.sizeSp, fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text(post.category.labelBn, style = t.banglaLabel, color = c.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                Text(post.place, style = t.caption, color = c.textSecondary, maxLines = 1)
            }
            Text(post.text, style = t.body.copy(fontSize = Tokens.Text.headline.sizeSp, lineHeight = Tokens.Text.banglaBody.lineHeightSp), color = c.textPrimary)
            if (post.detail != null || post.expiryLeft != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (post.detail != null) Tag(post.detail, dot = post.category?.let { c.categoryColor(it) })
                    if (post.expiryLeft != null) Tag(post.expiryLeft)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(R.drawable.ic_vote_up, Modifier.clickable(role = Role.Button, onClick = onVoteUp), size = 20.dp, contentDescription = stringResource(R.string.vote_up))
                Spacer(Modifier.width(6.dp))
                Text(post.score.toString(), style = t.bodyStrong.copy(fontSize = Tokens.Text.caption.sizeSp), color = c.textPrimary)
                Spacer(Modifier.width(6.dp))
                HIcon(R.drawable.ic_vote_down, Modifier.clickable(role = Role.Button, onClick = onVoteDown), size = 20.dp, contentDescription = stringResource(R.string.vote_down))
                Spacer(Modifier.width(16.dp))
                HIcon(R.drawable.ic_chats, size = 18.dp, tint = c.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text(post.comments.toString(), style = t.caption, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                CarrierLine(post.carrier, post.carrierLabel)
                Spacer(Modifier.width(12.dp))
                HIcon(R.drawable.ic_more, Modifier.clickable(role = Role.Button, onClick = onMore), tint = c.textTertiary, size = 18.dp, contentDescription = stringResource(R.string.more_actions))
            }
        }
    }
}

/** Schematic map thumbnail drawn from the map colour tokens (no tiles, no network). */
@Composable
fun MapThumbnail(modifier: Modifier = Modifier, showPin: Boolean = true) {
    val c = HarkaraTheme.colors
    Box(modifier.fillMaxWidth().height(104.dp).background(c.mapLand), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val r = CornerRadius(6.dp.toPx())
            val road = 10.dp.toPx()
            val col1 = w * 0.36f
            val col2 = w * 0.66f
            val row = h * 0.52f
            drawRoundRect(c.mapBlock, Offset(w * 0.06f, h * 0.12f), Size(col1 - w * 0.1f, row - h * 0.2f), r)
            drawRoundRect(c.mapBlock, Offset(w * 0.06f, row + road), Size(col1 - w * 0.1f, h * 0.36f), r)
            drawRoundRect(c.mapPark, Offset(col1 + road, h * 0.1f), Size(col2 - col1 - road * 2, h * 0.8f), r)
            drawRoundRect(c.mapBlock, Offset(col2 + road, h * 0.12f), Size(w - col2 - road * 2, row - h * 0.26f), r)
            drawCircle(c.mapWater, radius = w * 0.26f, center = Offset(w * 1.02f, h * 1.05f))
            drawRect(c.mapRoad, Offset(0f, row - road / 2), Size(w, road))
            drawRect(c.mapRoad, Offset(col1 - road / 2, 0f), Size(road, h))
            drawRect(c.mapRoad, Offset(col2 - road / 2, 0f), Size(road, h))
        }
        if (showPin) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(c.bgInverse), contentAlignment = Alignment.Center) {
                HIcon(R.drawable.ic_pin, tint = c.textInverse, size = 16.dp)
            }
        }
    }
}

@Composable
fun LocationCard(title: String, detail: String, plusCode: String, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val t = HarkaraTheme.type
    HCard(modifier.widthIn(max = 320.dp), padding = PaddingValues(0.dp)) {
        Column {
            MapThumbnail()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HIcon(R.drawable.ic_pin, size = 16.dp)
                    Text(title, style = t.headline, color = c.textPrimary)
                }
                Text(detail, style = t.caption, color = c.textSecondary)
                Text(plusCode, style = t.mono, color = c.textSecondary)
            }
        }
    }
}

/** Task card: type, price, title, five-step progress, status and runner. */
data class TaskUi(
    val type: String,
    val price: String,
    val title: String,
    val stepsDone: Int,
    val status: String,
    val runner: String? = null,
)

@Composable
fun TaskCard(task: TaskUi, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val t = HarkaraTheme.type
    HCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(R.drawable.ic_tasks, tint = c.textSecondary, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(task.type, style = t.caption, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(task.price, style = t.title, color = c.textPrimary)
            }
            Text(task.title, style = t.headline.copy(fontWeight = FontWeight.Medium, lineHeight = Tokens.Text.banglaBody.lineHeightSp), color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(5) { i ->
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(CircleShape)
                            .background(if (i < task.stepsDone) c.bgInverse else c.bgSunken),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.status, style = t.caption, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                if (task.runner != null) {
                    HIcon(R.drawable.ic_verified, tint = c.stateOnline, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(task.runner, style = t.label, color = c.textSecondary)
                }
            }
        }
    }
}

/** IOU card; [incoming] picks money/in (they owe you) or money/out. */
data class IouUi(
    val amount: String,
    val incoming: Boolean,
    val what: String,
    val status: String,
    val date: String,
    val agreed: Boolean,
)

@Composable
fun IouCard(iou: IouUi, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val t = HarkaraTheme.type
    HCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(R.drawable.ic_khata, tint = c.textSecondary, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.iou_card_kind), style = t.caption, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(iou.date, style = t.micro, color = c.textTertiary)
            }
            Text(iou.amount, style = t.amount, color = if (iou.incoming) c.moneyIn else c.moneyOut)
            Text(iou.what, style = t.caption, color = c.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HIcon(if (iou.agreed) R.drawable.ic_check else R.drawable.ic_lock, tint = c.textSecondary, size = 14.dp)
                Text(iou.status, style = t.caption, color = c.textSecondary)
            }
        }
    }
}

@Composable
fun CheckInCard(title: String, detail: String, carrier: Carrier, carrierLabel: String, modifier: Modifier = Modifier) {
    val c = HarkaraTheme.colors
    val t = HarkaraTheme.type
    HCard(modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.stateOnline), contentAlignment = Alignment.Center) {
                HIcon(R.drawable.ic_check, tint = c.textInverse, size = 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = t.headline.copy(fontWeight = FontWeight.Medium), color = c.textPrimary)
                Text(detail, style = t.caption, color = c.textSecondary)
            }
            CarrierLine(carrier, carrierLabel)
        }
    }
}

private val waveform = listOf(6, 10, 14, 8, 16, 12, 18, 9, 14, 6, 12, 16, 10, 7, 13, 9, 11, 6)

@Composable
fun VoiceNote(duration: String, modifier: Modifier = Modifier, progress: Float = 0.3f, onPlay: () -> Unit = {}) {
    val c = HarkaraTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(c.bgSurface)
            .border(1.dp, c.borderSubtle, CircleShape)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(c.bgInverse).clickable(role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            HIcon(R.drawable.ic_play, tint = c.textInverse, size = 16.dp, contentDescription = stringResource(R.string.voice_play))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            val played = (waveform.size * progress).toInt()
            waveform.forEachIndexed { i, hgt ->
                Box(Modifier.size(width = 2.dp, height = hgt.dp).clip(CircleShape).background(if (i < played) c.textPrimary else c.borderStrong))
            }
        }
        Text(duration, style = HarkaraTheme.type.label, color = c.textSecondary)
    }
}

/** Pin card with the "still there?" tally. */
data class PinUi(
    val title: String,
    val detail: String,
    val visibility: String,
    val yes: Int,
    val no: Int,
    val color: Category = Category.STOCK,
)

@Composable
fun PinCard(pin: PinUi, modifier: Modifier = Modifier, onYes: () -> Unit = {}, onNo: () -> Unit = {}) {
    val c = HarkaraTheme.colors
    val t = HarkaraTheme.type
    HCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.categoryColor(pin.color)))
                Spacer(Modifier.width(8.dp))
                Text(pin.title, style = t.headline.copy(fontWeight = FontWeight.Medium), color = c.textPrimary, modifier = Modifier.weight(1f))
                Tag(pin.visibility)
            }
            Text(pin.detail, style = t.caption, color = c.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pin_still_there), style = t.label.copy(fontSize = Tokens.Text.caption.sizeSp), color = c.textPrimary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pin_tally, pin.yes, pin.no), style = t.micro, color = c.textTertiary)
                Spacer(Modifier.weight(1f))
                SmallPillButton(stringResource(R.string.yes), onYes)
                Spacer(Modifier.width(6.dp))
                SmallPillButton(stringResource(R.string.no), onNo)
            }
        }
    }
}

@Composable
private fun SmallPillButton(label: String, onClick: () -> Unit) {
    val c = HarkaraTheme.colors
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .border(1.dp, c.borderDefault, CircleShape)
            .background(c.bgSurface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = HarkaraTheme.type.label.copy(fontSize = Tokens.Text.caption.sizeSp), color = c.textPrimary)
    }
}

/** Chat bubble. Incoming = surface, outgoing = accent/soft; footer shows carrier, time and ticks. */
@Composable
fun MessageBubble(
    text: String,
    time: String,
    outgoing: Boolean,
    carrier: Carrier,
    modifier: Modifier = Modifier,
    delivery: Delivery? = null,
) {
    val c = HarkaraTheme.colors
    val shape = RoundedCornerShape(
        topStart = Tokens.Radius.lg,
        topEnd = Tokens.Radius.lg,
        bottomStart = if (outgoing) Tokens.Radius.lg else 4.dp,
        bottomEnd = if (outgoing) 4.dp else Tokens.Radius.lg,
    )
    Column(
        modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(if (outgoing) c.accentSoft else c.bgSurface)
            .then(if (outgoing) Modifier else Modifier.border(1.dp, c.borderSubtle, shape))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(text, style = HarkaraTheme.type.body.copy(fontSize = Tokens.Text.headline.sizeSp, lineHeight = Tokens.Text.banglaBody.lineHeightSp), color = c.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HIcon(carrierIcon(carrier), tint = c.carrierColor(carrier), size = 12.dp)
            Text(time, style = HarkaraTheme.type.micro, color = c.textSecondary)
            if (outgoing && delivery != null) DeliveryTicks(delivery)
        }
    }
}
