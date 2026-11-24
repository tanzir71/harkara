package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    ScreenSurface("Nearby feed", "Posts around your current geohash will appear here.", modifier)
}

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    ScreenSurface("Local chat", "Broadcast channels and encrypted DMs share the same mesh.", modifier)
}

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    ScreenSurface("Peer map", "Approximate opted-in peer positions and pinned bulletins.", modifier)
}

@Composable
fun ComposeScreen(modifier: Modifier = Modifier) {
    ScreenSurface("Compose", "Cast a message, channel post, event, or bulletin nearby.", modifier)
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    ScreenSurface("Settings", "Identity, mesh duty, disaster mode, retention, and panic wipe.", modifier)
}

@Composable
private fun ScreenSurface(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Preview
@Composable
private fun FeedPreview() {
    HarkaraTheme { FeedScreen() }
}
