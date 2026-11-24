package io.github.tanzir71.harkara.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.data.PostEntity

/** Renders feed posts from Room rows. */
@Composable
fun FeedPostList(posts: List<PostEntity>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(12.dp)) {
        posts.forEach { post ->
            Card(modifier = Modifier.padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(post.displayName)
                    Text(post.text)
                    Text(post.geohash)
                }
            }
        }
    }
}
