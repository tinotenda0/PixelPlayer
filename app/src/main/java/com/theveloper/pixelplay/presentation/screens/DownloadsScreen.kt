package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.compose.material3.CircularProgressIndicator
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.NavidromeDownloadManager
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.navigation.ArtistNavigation
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.DownloadsViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by downloadsViewModel.uiState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

    val currentSongId by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val queueName = stringResource(R.string.downloads_queue_name)
    val queueSongs = remember(uiState.songs) { uiState.songs.toImmutableList() }

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    val bottomBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    if (uiState.songs.isNotEmpty()) {
                        TextButton(onClick = { showClearAllDialog = true }) {
                            Text(stringResource(R.string.downloads_clear_all))
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        if (uiState.songs.isEmpty() && uiState.active.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_download_24),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.downloads_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.downloads_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    bottom = MiniPlayerHeight + bottomBarHeightDp + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (uiState.active.isNotEmpty()) {
                    item(key = "downloading_header") {
                        Text(
                            text = stringResource(R.string.downloads_downloading, uiState.active.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    items(
                        items = uiState.active,
                        key = { "active_${it.id}" },
                        contentType = { "active_download" }
                    ) { ad -> ActiveDownloadRow(ad) }
                }
                if (uiState.songs.isNotEmpty()) {
                    item(key = "downloads_summary") {
                        Text(
                            text = stringResource(
                                R.string.downloads_summary,
                                uiState.songs.size,
                                formatSize(uiState.totalSizeBytes)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                if (uiState.songs.isNotEmpty()) item(key = "downloads_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val first = queueSongs.firstOrNull() ?: return@Button
                                playerViewModel.playSongs(queueSongs, first, queueName)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.common_play))
                        }
                        FilledTonalButton(
                            onClick = {
                                playerViewModel.playSongsShuffled(
                                    songsToPlay = queueSongs,
                                    queueName = queueName,
                                    startAtZero = true
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.common_shuffle))
                        }
                    }
                }
                items(
                    items = uiState.songs,
                    key = { song -> song.id },
                    contentType = { "download_song" }
                ) { song ->
                    EnhancedSongListItem(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        song = song,
                        isCurrentSong = currentSongId == song.id,
                        isPlaying = currentSongId == song.id && isPlaying,
                        onClick = {
                            playerViewModel.playSongs(queueSongs, song, queueName)
                        },
                        onMoreOptionsClick = { s ->
                            playerViewModel.selectSongForInfo(s)
                            showSongInfoBottomSheet = true
                        }
                    )
                }
            }
        }

        if (showSongInfoBottomSheet && selectedSongForInfo != null) {
            val song = selectedSongForInfo!!
            SongInfoBottomSheet(
                song = song,
                isFavorite = favoriteSongIds.contains(song.id),
                onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(song) },
                onDismiss = {
                    showSongInfoBottomSheet = false
                    showPlaylistBottomSheet = false
                },
                onPlaySong = {
                    if (queueSongs.isNotEmpty()) {
                        playerViewModel.playSongs(queueSongs, song, queueName)
                    }
                },
                onAddToQueue = { playerViewModel.addSongToQueue(song) },
                onAddNextToQueue = { playerViewModel.addSongNextToQueue(song) },
                onAddToPlayList = { showPlaylistBottomSheet = true },
                onDeleteFromDevice = playerViewModel::deleteFromDevice,
                onNavigateToAlbum = {
                    navController.navigateSafely(Screen.AlbumDetail.createRoute(song.albumId))
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtist = {
                    navController.navigateSafely(ArtistNavigation.routeFor(song))
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtistById = { artistRef ->
                    navController.navigateSafely(ArtistNavigation.routeForRef(artistRef))
                    showSongInfoBottomSheet = false
                },
                onNavigateToGenre = {
                    song.genre?.let {
                        navController.navigateSafely(
                            Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8"))
                        )
                    }
                    showSongInfoBottomSheet = false
                },
                onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                    playerViewModel.editSongMetadata(
                        song,
                        newTitle,
                        newArtist,
                        newAlbum,
                        newAlbumArtist,
                        newComposer,
                        newGenre,
                        newLyrics,
                        newTrackNumber,
                        newDiscNumber,
                        replayGainTrackGainDb,
                        replayGainAlbumGainDb,
                        coverArtUpdate
                    )
                },
                removeFromListTrigger = {}
            )

            if (showPlaylistBottomSheet) {
                PlaylistBottomSheet(
                    playlistUiState = playlistUiState,
                    songs = listOf(song),
                    onDismiss = { showPlaylistBottomSheet = false },
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel,
                )
            }
        }

        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                title = { Text(stringResource(R.string.downloads_clear_all_title)) },
                text = { Text(stringResource(R.string.downloads_clear_all_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        downloadsViewModel.removeAll()
                        showClearAllDialog = false
                    }) { Text(stringResource(R.string.downloads_clear_all)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun ActiveDownloadRow(ad: NavidromeDownloadManager.ActiveDownload) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (ad.downloading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painter = painterResource(R.drawable.rounded_download_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ad.song?.title ?: stringResource(R.string.downloads_downloading_item),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = ad.song?.displayArtist?.takeIf { it.isNotBlank() }
                    ?: if (ad.downloading) stringResource(R.string.downloads_downloading_now)
                       else stringResource(R.string.downloads_queued),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!ad.downloading) {
            Text(
                text = stringResource(R.string.downloads_queued),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}
