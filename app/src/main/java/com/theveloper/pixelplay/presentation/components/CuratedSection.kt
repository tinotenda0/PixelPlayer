package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.model.Song

/**
 * A server-curated home row (Your Mix, Discover, Top Charts, "<Artist> Radio", …): a title
 * followed by a horizontal carousel of song cards. Tapping a card plays the whole row starting
 * from that song. Purely additive — the caller only renders it when [songs] is non-empty.
 */
@Composable
fun CuratedSection(
    title: String,
    songs: List<Song>,
    currentSongId: String?,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(songs, key = { it.id }) { song ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSongClick(song) }
                        .padding(4.dp)
                ) {
                    SmartImage(
                        model = song.albumArtUriString,
                        contentDescription = song.title,
                        shape = RoundedCornerShape(14.dp),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(132.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (song.id == currentSongId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
