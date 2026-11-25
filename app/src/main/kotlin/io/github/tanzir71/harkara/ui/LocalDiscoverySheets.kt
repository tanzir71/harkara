package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.search.LocalSearch
import io.github.tanzir71.harkara.ui.components.Chip
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AreaPickerSheet(areas: List<String>, onPick: (String) -> Unit, onLocation: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .7f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.area_choose), style = HarkaraTheme.type.title, color = HarkaraTheme.colors.textPrimary)
        Text(stringResource(R.string.area_choose_note), style = HarkaraTheme.type.body, color = HarkaraTheme.colors.textSecondary)
        HButton(stringResource(R.string.onboarding_use_location), onLocation)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            areas.forEach { name -> Chip(name, false, onClick = { onPick(name) }) }
        }
    }
}

@Composable
internal fun LocalSearchSheet(search: LocalSearch, onDirection: (LocalSearch.Result) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<LocalSearch.Result>()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(200)
        try { results = search.search(query); failed = false }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { results = emptyList(); failed = true }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .7f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.local_search_title), style = HarkaraTheme.type.title, color = HarkaraTheme.colors.textPrimary)
        Text(stringResource(R.string.local_search_note), style = HarkaraTheme.type.caption, color = HarkaraTheme.colors.textSecondary)
        HTextField(query, { query = it.take(256) }, stringResource(R.string.local_search_query), Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.local_search_failed), color = HarkaraTheme.colors.textSecondary)
        else if (query.isNotBlank() && results.isEmpty()) Text(stringResource(R.string.local_search_empty), color = HarkaraTheme.colors.textSecondary)
        results.forEach { result ->
            if (result.title.isNotBlank()) Text(result.title, style = HarkaraTheme.type.headline, color = HarkaraTheme.colors.textPrimary)
            Text(result.text.take(500), style = HarkaraTheme.type.body, color = HarkaraTheme.colors.textPrimary)
            if (result.position != null) HButton(stringResource(R.string.local_search_direction), { onDirection(result) }, icon = R.drawable.ic_compass)
        }
    }
}
