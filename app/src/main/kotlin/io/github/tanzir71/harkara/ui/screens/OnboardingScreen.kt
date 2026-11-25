package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.Wordmark
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme

/**
 * Onboarding without sign-up: one tap on an area chip (or the optional location button) and the
 * Nearby feed opens. Keys are made silently; name and photo are asked only when needed later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    areas: List<String>,
    onUseLocation: () -> Unit,
    onPickArea: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRestore: () -> Unit = {},
) {
    val c = HarkaraTheme.colors
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Wordmark()
        Text(
            stringResource(R.string.onboarding_title),
            style = HarkaraTheme.type.display,
            color = c.textPrimary,
            modifier = Modifier.padding(top = 24.dp).semantics { heading() },
        )
        Text(stringResource(R.string.onboarding_body), style = HarkaraTheme.type.body, color = c.textSecondary)
        HButton(
            stringResource(R.string.onboarding_use_location),
            onUseLocation,
            Modifier.fillMaxWidth(),
            variant = HButtonVariant.Primary,
            icon = R.drawable.ic_locate,
            height = 52.dp,
        )
        Text(stringResource(R.string.onboarding_location_note), style = HarkaraTheme.type.caption, color = c.textTertiary)
        Text(stringResource(R.string.onboarding_pick_area), style = HarkaraTheme.type.headline, color = c.textPrimary, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            areas.forEach { area -> Chip(area, selected = false, onClick = { onPickArea(area) }) }
        }
        HButton(
            stringResource(R.string.onboarding_restore),
            onRestore,
            Modifier.padding(top = 8.dp),
            variant = HButtonVariant.Ghost,
            icon = R.drawable.ic_lock,
            height = 40.dp,
        )
    }
}
