package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.core.feed.ComposeVerdict
import io.github.tanzir71.harkara.feed.DetailLabels
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.carrierColor
import io.github.tanzir71.harkara.ui.components.carrierIcon
import io.github.tanzir71.harkara.ui.components.categoryColor
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.theme.Tokens

const val MAX_POST_LENGTH = 400

/** Error text for a composer verdict, or null when it posted. */
@Composable
fun verdictMessage(verdict: ComposeVerdict?): String? = when (verdict) {
    null, ComposeVerdict.OK -> null
    ComposeVerdict.EMPTY -> stringResource(R.string.compose_err_empty)
    ComposeVerdict.TOO_LONG -> stringResource(R.string.compose_err_long)
    ComposeVerdict.BLOCKED_WORDS -> stringResource(R.string.compose_err_words)
    ComposeVerdict.SPAM -> stringResource(R.string.compose_err_spam)
    ComposeVerdict.TOO_MANY_LINKS -> stringResource(R.string.compose_err_links)
    ComposeVerdict.PHONE_NUMBER -> stringResource(R.string.compose_err_phone)
    ComposeVerdict.RATE_LIMITED -> stringResource(R.string.compose_err_rate)
}

/**
 * Screen 03: post nearby. Only the text is required; category and details are optional chips.
 * The footer tells the truth about where the post will go right now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeSheetContent(
    areaLabel: String,
    connectivity: ConnectivityState,
    onPost: (text: String, category: Category?, details: Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    posting: Boolean = false,
    verdict: ComposeVerdict? = null,
    initialText: String = "",
    initialCategory: Category? = null,
) {
    val c = HarkaraTheme.colors
    var text by remember { mutableStateOf(initialText) }
    var category by remember { mutableStateOf(initialCategory) }
    val details = remember { mutableStateMapOf<String, String>() }
    val hint = stringResource(R.string.compose_hint)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.compose_title), style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(c.bgSunken).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HIcon(R.drawable.ic_locate, tint = c.stateNode, size = 14.dp)
                Text(areaLabel, style = HarkaraTheme.type.label, color = c.textPrimary)
            }
        }
        val shape = RoundedCornerShape(Tokens.Radius.lg)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(c.bgSurface).border(1.dp, c.borderDefault, shape).padding(14.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_POST_LENGTH) text = it },
                textStyle = HarkaraTheme.type.body.copy(fontSize = Tokens.Text.headline.sizeSp, lineHeight = Tokens.Text.banglaBody.lineHeightSp, color = c.textPrimary),
                cursorBrush = SolidColor(c.textPrimary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { contentDescription = hint },
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(hint, style = HarkaraTheme.type.body.copy(fontSize = Tokens.Text.headline.sizeSp), color = c.textTertiary)
                    inner()
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.compose_counter, text.length, MAX_POST_LENGTH), style = HarkaraTheme.type.label, color = c.textTertiary)
            }
        }
        Text(stringResource(R.string.compose_category), style = HarkaraTheme.type.label.copy(fontSize = Tokens.Text.caption.sizeSp), color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { cat ->
                Chip(stringResource(DetailLabels.categoryLabel(cat)), selected = category == cat, dot = c.categoryColor(cat), onClick = {
                    category = if (category == cat) null else cat
                    details.clear()
                })
            }
        }
        DetailLabels.groupsFor(category).forEachIndexed { index, (tag, options) ->
            if (index == 0) {
                Text(
                    stringResource(R.string.compose_details, stringResource(DetailLabels.categoryLabel(category!!))),
                    style = HarkaraTheme.type.label.copy(fontSize = Tokens.Text.caption.sizeSp),
                    color = c.textSecondary,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, label) ->
                    Chip(stringResource(label), selected = details[tag] == value, onClick = {
                        if (details[tag] == value) details.remove(tag) else details[tag] = value
                    })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(R.drawable.ic_queued, tint = c.textSecondary, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            val cat = category ?: Category.CHATTER
            val catName = stringResource(DetailLabels.categoryLabel(cat))
            Text(
                cat.expiry?.let { stringResource(R.string.compose_lasts, catName, it.toHours().toInt()) } ?: stringResource(R.string.compose_never_expires, catName),
                style = HarkaraTheme.type.caption,
                color = c.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            HIcon(R.drawable.ic_lock, tint = c.textSecondary, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.compose_anonymous), style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
        verdictMessage(verdict)?.let { Text(it, style = HarkaraTheme.type.caption, color = c.stateSos) }
        HButton(
            if (posting) stringResource(R.string.compose_posting) else stringResource(R.string.compose_post),
            { onPost(text, category, details.toMap()) },
            Modifier.fillMaxWidth(),
            enabled = !posting && text.isNotBlank(),
            height = 52.dp,
        )
        val route = connectivity.best
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            HIcon(carrierIcon(route), tint = c.carrierColor(route), size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (route == Carrier.SAVED) R.string.compose_route_saved else R.string.compose_route_online),
                style = HarkaraTheme.type.label,
                color = c.textSecondary,
            )
        }
    }
}
