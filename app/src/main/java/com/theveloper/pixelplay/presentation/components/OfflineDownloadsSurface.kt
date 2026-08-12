package com.theveloper.pixelplay.presentation.components

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
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

/**
 * The Full-restrict offline surface (Spotify/Tidal style): a banner plus the user's downloads,
 * which are the only things playable without a connection. Mounted as an early branch by Home,
 * Search and Library whenever [PlayerViewModel.effectiveOffline] is true, so the normal online
 * content is completely bypassed while offline.
 *
 * @param enableSearch when true, shows a filter box over the downloads (used by the Search tab).
 * @param topInset when true, pads for the status bar (Home/Search render edge-to-edge; Library has
 *                 its own top bar and passes false).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun OfflineDownloadsSurface(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    enableSearch: Boolean = false,
    topInset: Boolean = true,
    modifier: Modifier = Modifier,
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

    // The surface only shows while effectiveOffline is true. If we ALSO have a connection, the only
    // way we got here is the user pinning Offline mode — so offer a one-tap way back out.
    val isOnline by playerViewModel.isOnline.collectAsStateWithLifecycle()
    val pinnedWhileOnline = isOnline

    val queueName = stringResource(R.string.downloads_queue_name)
    var query by remember { mutableStateOf("") }

    val visibleSongs = remember(uiState.songs, query, enableSearch) {
        if (!enableSearch || query.isBlank()) uiState.songs
        else uiState.songs.filter { song ->
            val q = query.trim()
            song.title.contains(q, ignoreCase = true) ||
                song.artist.contains(q, ignoreCase = true) ||
                song.album.contains(q, ignoreCase = true)
        }.toImmutableList()
    }
    val queueSongs = remember(visibleSongs) { visibleSongs.toImmutableList() }

    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val bottomBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val lazyListState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Pinned top bar — ALWAYS visible so Offline mode is never a dead-end: the Settings gear
        // reaches Settings (where the toggle lives), and "Turn off" exits directly when we're online.
        OfflineTopBar(
            topInset = topInset,
            showTurnOff = pinnedWhileOnline,
            onTurnOff = { downloadsViewModel.setOfflineMode(false) },
            onOpenSettings = { navController.navigateSafely(Screen.Settings.route) }
        )
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                bottom = MiniPlayerHeight + bottomBarHeightDp + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "offline_banner") {
                OfflineBanner(
                    pinnedWhileOnline = pinnedWhileOnline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (enableSearch && uiState.songs.isNotEmpty()) {
                item(key = "offline_search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = null)
                                }
                            }
                        },
                        placeholder = { Text(stringResource(R.string.downloads_search_placeholder)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = RoundedCornerShape(28.dp)
                    )
                }
            }

            if (uiState.songs.isEmpty()) {
                item(key = "offline_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp, start = 32.dp, end = 32.dp),
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
                }
            } else {
                item(key = "offline_actions") {
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
                    items = visibleSongs,
                    key = { song -> song.id },
                    contentType = { "offline_download_song" }
                ) { song ->
                    EnhancedSongListItem(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        song = song,
                        isCurrentSong = currentSongId == song.id,
                        isPlaying = currentSongId == song.id && isPlaying,
                        onClick = { playerViewModel.playSongs(queueSongs, song, queueName) },
                        onMoreOptionsClick = { s ->
                            playerViewModel.selectSongForInfo(s)
                            showSongInfoBottomSheet = true
                        }
                    )
                }
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
                    if (queueSongs.isNotEmpty()) playerViewModel.playSongs(queueSongs, song, queueName)
                },
                onAddToQueue = { playerViewModel.addSongToQueue(song) },
                onAddNextToQueue = { playerViewModel.addSongNextToQueue(song) },
                onAddToPlayList = { showPlaylistBottomSheet = true },
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
                        song, newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre,
                        newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb,
                        replayGainAlbumGainDb, coverArtUpdate
                    )
                }
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
    }
}

@Composable
private fun OfflineBanner(pinnedWhileOnline: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_cloud_off_24),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(
                        if (pinnedWhileOnline) R.string.offline_banner_pinned_title
                        else R.string.offline_banner_title
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(
                        if (pinnedWhileOnline) R.string.offline_banner_pinned_subtitle
                        else R.string.offline_banner_subtitle
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun OfflineTopBar(
    topInset: Boolean,
    showTurnOff: Boolean,
    onTurnOff: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.downloads_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        if (showTurnOff) {
            TextButton(onClick = onTurnOff) {
                Text(stringResource(R.string.offline_turn_off))
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.rounded_settings_24),
                contentDescription = stringResource(R.string.common_settings)
            )
        }
    }
}
