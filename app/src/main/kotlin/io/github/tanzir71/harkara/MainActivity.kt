package io.github.tanzir71.harkara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.tanzir71.harkara.ui.ChatScreen
import io.github.tanzir71.harkara.ui.ComposeScreen
import io.github.tanzir71.harkara.ui.FeedScreen
import io.github.tanzir71.harkara.ui.MapScreen
import io.github.tanzir71.harkara.ui.HarkaraTheme
import io.github.tanzir71.harkara.ui.SettingsScreen

/** Main Android activity hosting the Compose shell. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HarkaraApp() }
    }
}

/** Top-level Harkara UI with bottom navigation. */
@Composable
fun HarkaraApp() {
    var tab by remember { mutableStateOf(HarkaraTab.Feed) }
    HarkaraTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        HarkaraTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(item.icon(), contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                when (tab) {
                    HarkaraTab.Feed -> FeedScreen(Modifier.padding(innerPadding))
                    HarkaraTab.Chat -> ChatScreen(Modifier.padding(innerPadding))
                    HarkaraTab.Map -> MapScreen(Modifier.padding(innerPadding))
                    HarkaraTab.Compose -> ComposeScreen(Modifier.padding(innerPadding))
                    HarkaraTab.Settings -> SettingsScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/** Bottom navigation destinations. */
enum class HarkaraTab(val label: String) {
    Feed("Feed"),
    Chat("Chat"),
    Map("Map"),
    Compose("Compose"),
    Settings("Settings");

    /** Icon for the tab. */
    @Composable
    fun icon() = when (this) {
        Feed -> Icons.Default.RssFeed
        Chat -> Icons.AutoMirrored.Filled.Chat
        Map -> Icons.Default.Map
        Compose -> Icons.Default.AddCircle
        Settings -> Icons.Default.Settings
    }
}

@Preview
@Composable
private fun HarkaraAppPreview() {
    HarkaraApp()
}
