package com.theveloper.pixelplay.presentation.plex.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.database.PlexPlaylistEntity
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlexDashboardScreen(
    viewModel: PlexDashboardViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val downloadCount by viewModel.downloadCount.collectAsStateWithLifecycle()
    val downloadTotalBytes by viewModel.downloadTotalBytes.collectAsStateWithLifecycle()
    val downloadQueueProgress by viewModel.downloadQueueProgress.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    var showAccountSheet by remember { mutableStateOf(false) }
    var showRemoteSheet by remember { mutableStateOf(false) }
    val remotePlayers by viewModel.remotePlayers.collectAsStateWithLifecycle()
    val isLoadingRemotePlayers by viewModel.isLoadingRemotePlayers.collectAsStateWithLifecycle()
    val selectedRemotePlayer by viewModel.selectedRemotePlayer.collectAsStateWithLifecycle()
    val remoteNowPlaying by viewModel.remoteNowPlaying.collectAsStateWithLifecycle()

    val cardShape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = 20.dp, cornerRadiusTL = 20.dp,
        cornerRadiusBR = 20.dp, cornerRadiusBL = 20.dp,
        smoothnessAsPercentTR = 60, smoothnessAsPercentTL = 60,
        smoothnessAsPercentBR = 60, smoothnessAsPercentBL = 60
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.auth_plex_title),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {}
            )
        }
    ) { paddingValues ->
        PlexDashboardContent(
            playlists = playlists,
            isSyncing = isSyncing,
            syncMessage = syncMessage,
            username = activeAccount?.username ?: viewModel.username,
            serverName = activeAccount?.serverName,
            onOpenAccounts = { showAccountSheet = true },
            onOpenRemote = {
                showRemoteSheet = true
                viewModel.loadRemotePlayers()
            },
            downloadCount = downloadCount,
            downloadTotalBytes = downloadTotalBytes,
            downloadQueueProgress = downloadQueueProgress,
            onSyncAll = { viewModel.syncAllPlaylistsAndSongs() },
            onSyncPlaylist = { viewModel.syncPlaylistSongs(it) },
            onDeletePlaylist = { viewModel.deletePlaylist(it) },
            onLoadPlaylistSongs = { viewModel.loadPlaylistSongs(it) },
            onDownloadPlaylist = { viewModel.downloadPlaylist(it) },
            onDownloadLibrary = { viewModel.downloadLibrary() },
            onRemoveAllDownloads = { viewModel.removeAllDownloads() },
            onLogout = {
                viewModel.logout()
                onBack()
            },
            cardShape = cardShape,
            paddingValues = paddingValues
        )

        if (showRemoteSheet) {
            PlexRemoteSheet(
                players = remotePlayers,
                isLoadingPlayers = isLoadingRemotePlayers,
                selectedPlayer = selectedRemotePlayer,
                nowPlaying = remoteNowPlaying,
                onRefresh = { viewModel.loadRemotePlayers() },
                onSelectPlayer = { viewModel.selectRemotePlayer(it) },
                onChangePlayer = { viewModel.clearRemotePlayer() },
                onCommand = { viewModel.sendRemoteCommand(it) },
                onVolume = { viewModel.setRemoteVolume(it) },
                onDismiss = {
                    showRemoteSheet = false
                    viewModel.clearRemotePlayer()
                }
            )
        }

        if (showAccountSheet) {
            PlexAccountsSheet(
                accounts = accounts,
                activeAccountId = activeAccount?.id,
                onDismiss = { showAccountSheet = false },
                onSwitch = { accountId ->
                    showAccountSheet = false
                    viewModel.switchAccount(accountId)
                },
                onRemove = { accountId -> viewModel.removeAccount(accountId) }
            )
        }
    }
}

@Composable
private fun PlexDashboardContent(
    playlists: List<PlexPlaylistEntity>,
    isSyncing: Boolean,
    syncMessage: String?,
    username: String?,
    serverName: String?,
    onOpenAccounts: () -> Unit,
    onOpenRemote: () -> Unit,
    downloadCount: Int,
    downloadTotalBytes: Long,
    downloadQueueProgress: com.theveloper.pixelplay.data.plex.PlexDownloadManager.QueueProgress?,
    onSyncAll: () -> Unit,
    onSyncPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onLoadPlaylistSongs: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onDownloadLibrary: () -> Unit,
    onRemoveAllDownloads: () -> Unit,
    onLogout: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape,
    paddingValues: PaddingValues
) {
    // One LazyColumn for the entire dashboard so the whole screen scrolls
    // as a unit instead of the playlist list scrolling inside a fixed layout.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Sync status banner
        item(key = "sync_banner") {
        AnimatedVisibility(
            visible = syncMessage != null,
            enter = slideInVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            ) + fadeIn(),
            exit = fadeOut()
        ) {
            syncMessage?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.contains("failed"))
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded
                        )
                    }
                }
            }
        }

        }

        // User info header
        item(key = "user_card") {
        username?.let { name ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5A00D)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_plex),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = serverName?.let { server ->
                                "$server · " + stringResource(
                                    R.string.cloud_dashboard_playlists_synced_count, playlists.size
                                )
                            } ?: stringResource(
                                R.string.cloud_dashboard_playlists_synced_count, playlists.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onOpenAccounts,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Rounded.SwapHoriz,
                            contentDescription = stringResource(R.string.plex_accounts_switch),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        }

        item(key = "menu_card") {
        PlexMenuCard(
            isSyncing = isSyncing,
            onSyncAll = onSyncAll,
            onLogout = onLogout,
            onOpenRemote = onOpenRemote,
            cardShape = cardShape
        )
        }

        item(key = "downloads_card") {
        PlexDownloadsCard(
            downloadCount = downloadCount,
            downloadTotalBytes = downloadTotalBytes,
            queueProgress = downloadQueueProgress,
            onDownloadLibrary = onDownloadLibrary,
            onRemoveAllDownloads = onRemoveAllDownloads,
            cardShape = cardShape
        )
        }

        item(key = "playlists_header") {
        Column {
        Spacer(modifier = Modifier.height(8.dp))

        // Playlists header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.cloud_dashboard_title_playlists),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
            if (playlists.isEmpty()) {
                TextButton(onClick = onSyncAll) {
                    Icon(
                        Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cloud_dashboard_action_sync), fontFamily = GoogleSansRounded, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        }
        }

        // Playlist list
        if (playlists.isEmpty() && !isSyncing) {
            item(key = "playlists_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_plex),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.cloud_dashboard_playlists_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.plex_dashboard_playlists_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        } else {
            items(
                items = playlists,
                key = { it.id }
            ) { playlist ->
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    PlexPlaylistCard(
                        playlist = playlist,
                        onSyncClick = { onSyncPlaylist(playlist.id) },
                        onDownloadClick = { onDownloadPlaylist(playlist.id) },
                        onDeleteClick = { onDeletePlaylist(playlist.id) },
                        onClick = { onLoadPlaylistSongs(playlist.id) },
                        cardShape = cardShape,
                        isSyncing = isSyncing
                    )
                }
            }
        }
    }
}

@Composable
private fun PlexMenuCard(
    isSyncing: Boolean,
    onSyncAll: () -> Unit,
    onLogout: () -> Unit,
    onOpenRemote: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.cloud_dashboard_quick_actions),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.plex_dashboard_quick_actions_subtitle),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onSyncAll,
                    enabled = !isSyncing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_sync_status_syncing), fontFamily = GoogleSansRounded)
                    } else {
                        Icon(
                            Icons.Rounded.CloudSync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_dashboard_action_sync_library), fontFamily = GoogleSansRounded)
                    }
                }

                FilledTonalButton(
                    onClick = onLogout,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_dashboard_action_disconnect), fontFamily = GoogleSansRounded)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = onOpenRemote,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    Icons.Rounded.SettingsRemote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.plex_remote_button), fontFamily = GoogleSansRounded)
            }
        }
    }
}

@Composable
private fun PlexDownloadsCard(
    downloadCount: Int,
    downloadTotalBytes: Long,
    queueProgress: com.theveloper.pixelplay.data.plex.PlexDownloadManager.QueueProgress?,
    onDownloadLibrary: () -> Unit,
    onRemoveAllDownloads: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape
) {
    val isQueueActive = queueProgress?.isActive == true

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.plex_downloads_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    isQueueActive && queueProgress != null -> stringResource(
                        R.string.plex_downloads_progress,
                        queueProgress.completed + queueProgress.failed,
                        queueProgress.total
                    )
                    downloadCount > 0 -> stringResource(
                        R.string.plex_downloads_summary,
                        downloadCount,
                        formatByteSize(downloadTotalBytes)
                    )
                    else -> stringResource(R.string.plex_downloads_empty_subtitle)
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onDownloadLibrary,
                    enabled = !isQueueActive,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    if (isQueueActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(
                            Icons.Rounded.DownloadForOffline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.plex_downloads_action_all),
                        fontFamily = GoogleSansRounded,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = onRemoveAllDownloads,
                    enabled = downloadCount > 0 || isQueueActive,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.plex_downloads_action_remove),
                        fontFamily = GoogleSansRounded,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format(java.util.Locale.getDefault(), "%.1f GB", mb / 1024)
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    }
}

@Composable
private fun PlexPlaylistCard(
    playlist: PlexPlaylistEntity,
    onSyncClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onClick: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape,
    isSyncing: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Playlist cover
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.cloud_dashboard_song_count, playlist.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalIconButton(
                onClick = onSyncClick,
                enabled = !isSyncing,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    Icons.Rounded.Sync,
                    contentDescription = stringResource(R.string.cloud_cd_sync),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            FilledTonalIconButton(
                onClick = onDownloadClick,
                enabled = !isSyncing,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    Icons.Rounded.DownloadForOffline,
                    contentDescription = stringResource(R.string.plex_cd_download_playlist),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            FilledTonalIconButton(
                onClick = onDeleteClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlexAccountsSheet(
    accounts: List<com.theveloper.pixelplay.data.plex.model.PlexAccount>,
    activeAccountId: String?,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

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
            Text(
                text = stringResource(R.string.plex_accounts_title),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(14.dp))

            accounts.forEach { account ->
                val isActive = account.id == activeAccountId
                ListItem(
                    headlineContent = {
                        Text(
                            account.username,
                            fontFamily = GoogleSansRounded,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    supportingContent = {
                        Text(
                            account.serverName ?: account.serverUrl,
                            fontFamily = GoogleSansRounded,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = if (isActive) Color(0xFFE5A00D) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isActive) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.plex_accounts_active),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                FilledTonalIconButton(
                                    onClick = { onRemove(account.id) },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.plex_accounts_remove_cd),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !isActive) { onSwitch(account.id) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isActive) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    )
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(6.dp))

            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.plex_accounts_add),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Medium
                    )
                },
                leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        onDismiss()
                        context.startActivity(
                            android.content.Intent(
                                context,
                                com.theveloper.pixelplay.presentation.plex.auth.PlexLoginActivity::class.java
                            )
                        )
                    },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    }
}
