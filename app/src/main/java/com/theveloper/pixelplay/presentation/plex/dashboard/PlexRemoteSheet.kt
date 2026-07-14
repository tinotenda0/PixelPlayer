package com.theveloper.pixelplay.presentation.plex.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.plex.model.PlexPlayerDevice
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import java.util.Locale

/**
 * Plex Companion remote: pick another player on the account (Plexamp on the
 * Mac/PC/iPad…) and control its playback from PixelPlayer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlexRemoteSheet(
    players: List<PlexPlayerDevice>,
    isLoadingPlayers: Boolean,
    selectedPlayer: PlexPlayerDevice?,
    nowPlaying: PlexDashboardViewModel.RemoteNowPlaying?,
    onRefresh: () -> Unit,
    onSelectPlayer: (PlexPlayerDevice) -> Unit,
    onChangePlayer: () -> Unit,
    onCommand: (String) -> Unit,
    onVolume: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.plex_remote_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = selectedPlayer?.name
                            ?: stringResource(R.string.plex_remote_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selectedPlayer == null) {
                    FilledTonalIconButton(onClick = onRefresh, enabled = !isLoadingPlayers) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.plex_remote_refresh_cd),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    FilledTonalIconButton(onClick = onChangePlayer) {
                        Icon(
                            Icons.Rounded.SwapHoriz,
                            contentDescription = stringResource(R.string.plex_remote_change_player),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (selectedPlayer == null) {
                // ── Player picker ──
                when {
                    isLoadingPlayers -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                    players.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.plex_remote_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                    else -> {
                        players.forEach { player ->
                            ListItem(
                                headlineContent = {
                                    Text(player.name, fontFamily = GoogleSansRounded)
                                },
                                supportingContent = {
                                    Text(player.product, fontFamily = GoogleSansRounded)
                                },
                                leadingContent = {
                                    Icon(Icons.Rounded.Speaker, contentDescription = null)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onSelectPlayer(player) },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            } else {
                // ── Remote transport ──
                if (nowPlaying == null || nowPlaying.state == "stopped") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.MusicOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.plex_remote_nothing_playing),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = nowPlaying.title
                            ?: stringResource(R.string.plex_remote_unknown_track),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    nowPlaying.artist?.let { artist ->
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${formatRemoteTime(nowPlaying.positionMs)} / ${formatRemoteTime(nowPlaying.durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { onCommand("skipPrevious") },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = null)
                    }
                    Spacer(Modifier.width(16.dp))
                    FilledIconButton(
                        onClick = {
                            onCommand(if (nowPlaying?.state == "playing") "pause" else "play")
                        },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (nowPlaying?.state == "playing") {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = null,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    FilledTonalIconButton(
                        onClick = { onCommand("skipNext") },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = null)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Volume ──
                var isDraggingVolume by remember { mutableStateOf(false) }
                var volumePosition by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(nowPlaying?.volume) {
                    if (!isDraggingVolume) {
                        nowPlaying?.volume?.let { volumePosition = it.toFloat() }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.VolumeDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = volumePosition,
                        onValueChange = {
                            isDraggingVolume = true
                            volumePosition = it
                        },
                        onValueChangeFinished = {
                            isDraggingVolume = false
                            onVolume(volumePosition.toInt())
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )
                    Icon(
                        Icons.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(6.dp))

                TextButton(
                    onClick = onChangePlayer,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        stringResource(R.string.plex_remote_change_player),
                        fontFamily = GoogleSansRounded
                    )
                }
            }
        }
    }
}

private fun formatRemoteTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
