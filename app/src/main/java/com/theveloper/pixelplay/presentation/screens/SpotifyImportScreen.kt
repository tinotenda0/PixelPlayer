package com.theveloper.pixelplay.presentation.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.navidrome.SpotifyPlaylistOption
import com.theveloper.pixelplay.presentation.viewmodel.SpotifyImportViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SpotifyImportViewModel.Phase

/**
 * Import a Spotify account into the gateway (re-matched to YouTube Music). The user approves access
 * on Spotify's own page in a browser, then picks — Tidal-style — which playlists / liked songs /
 * taste / history to copy across. Nothing streams from Spotify.
 */
@Composable
fun SpotifyImportScreen(
    onBack: () -> Unit,
    viewModel: SpotifyImportViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.openUrl.collect { url -> openUrl(context, url) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (ui.phase == Phase.SELECTING) {
            SelectionContent(ui, viewModel)
        } else {
            CenteredContent(ui, viewModel, onBack)
        }

        FilledIconButton(
            onClick = onBack,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 8.dp)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back)
            )
        }
    }
}

@Composable
private fun CenteredContent(
    ui: SpotifyImportViewModel.UiState,
    viewModel: SpotifyImportViewModel,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.rounded_download_24),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.spotify_import_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        when (ui.phase) {
            Phase.LOADING -> CircularProgressIndicator()

            Phase.UNCONFIGURED -> Body(stringResource(R.string.spotify_import_unconfigured))

            Phase.NOT_LINKED -> {
                Body(stringResource(R.string.spotify_import_intro))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { viewModel.startLink() }, enabled = !ui.busy) {
                    Text(stringResource(R.string.spotify_import_connect))
                }
            }

            Phase.AWAITING_APPROVAL -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Body(stringResource(R.string.spotify_import_awaiting))
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { viewModel.reopenConsent() }) {
                    Text(stringResource(R.string.spotify_import_reopen))
                }
            }

            // LINKED is transient: either loading the preview, or the preview failed to load.
            Phase.LINKED -> {
                if (ui.busy) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Body(stringResource(R.string.spotify_import_loading_library))
                } else {
                    Body(ui.message.ifBlank {
                        stringResource(R.string.spotify_import_loading_library)
                    })
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { viewModel.loadPreview() }) {
                        Text(stringResource(R.string.spotify_import_retry))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.unlink() }) {
                        Text(stringResource(R.string.spotify_import_unlink))
                    }
                }
            }

            Phase.IMPORTING -> {
                val p = ui.progress
                val total = p?.total ?: 0
                val done = p?.done ?: 0
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Body("$done / $total " + stringResource(R.string.spotify_import_songs_matched))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                Body(p?.message ?: stringResource(R.string.spotify_import_working))
            }

            Phase.DONE -> {
                val p = ui.progress
                Body(
                    stringResource(
                        R.string.spotify_import_done_summary,
                        p?.matched ?: 0, p?.playlists ?: 0
                    )
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.common_done)) }
            }

            Phase.ERROR -> {
                Body(ui.message.ifBlank { stringResource(R.string.spotify_import_error) })
                Spacer(Modifier.height(24.dp))
                Button(onClick = { viewModel.refresh() }) {
                    Text(stringResource(R.string.spotify_import_retry))
                }
            }

            Phase.SELECTING -> Unit // handled by SelectionContent
        }
    }
}

@Composable
private fun SelectionContent(
    ui: SpotifyImportViewModel.UiState,
    viewModel: SpotifyImportViewModel
) {
    val preview = ui.preview ?: return
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val allSelected = preview.playlists.isNotEmpty() &&
        ui.selectedPlaylistIds.size == preview.playlists.size

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.spotify_import_choose_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 64.dp, end = 24.dp, top = 12.dp, bottom = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            item(key = "sec_header") {
                SectionLabel(stringResource(R.string.spotify_import_section_your_stuff))
            }
            item(key = "liked") {
                CheckRow(
                    title = stringResource(R.string.spotify_import_liked),
                    subtitle = "${preview.likedCount} " + stringResource(R.string.spotify_import_songs),
                    checked = ui.likedSelected,
                    enabled = preview.likedCount > 0,
                    onToggle = { viewModel.toggleLiked() }
                )
            }
            item(key = "artists") {
                CheckRow(
                    title = stringResource(R.string.spotify_import_top_artists),
                    subtitle = stringResource(R.string.spotify_import_top_artists_sub),
                    checked = ui.artistsSelected,
                    enabled = preview.topArtistsCount > 0,
                    onToggle = { viewModel.toggleArtists() }
                )
            }
            item(key = "history") {
                CheckRow(
                    title = stringResource(R.string.spotify_import_history),
                    subtitle = stringResource(R.string.spotify_import_history_sub),
                    checked = ui.historySelected,
                    enabled = true,
                    onToggle = { viewModel.toggleHistory() }
                )
            }

            if (preview.playlists.isNotEmpty()) {
                item(key = "pl_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel(
                            stringResource(R.string.spotify_import_section_playlists),
                            modifier = Modifier.weight(1f)
                        )
                        if (preview.playlistsAvailable) {
                            TextButton(onClick = { viewModel.setAllPlaylists(!allSelected) }) {
                                Text(stringResource(
                                    if (allSelected) R.string.spotify_import_select_none
                                    else R.string.spotify_import_select_all
                                ))
                            }
                        }
                    }
                }
                if (!preview.playlistsAvailable) {
                    item(key = "pl_blocked") {
                        Text(
                            text = stringResource(R.string.spotify_import_playlists_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                items(preview.playlists, key = { it.id }) { pl: SpotifyPlaylistOption ->
                    CheckRow(
                        title = pl.name,
                        subtitle = if (pl.count > 0) {
                            "${pl.count} " + stringResource(R.string.spotify_import_songs)
                        } else "",
                        checked = pl.id in ui.selectedPlaylistIds,
                        enabled = preview.playlistsAvailable,
                        onToggle = { viewModel.togglePlaylist(pl.id) }
                    )
                }
            }
        }
        Button(
            onClick = { viewModel.startImport() },
            enabled = ui.hasSelection && !ui.busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = bottomInset + 16.dp, top = 8.dp)
        ) {
            Text(stringResource(R.string.spotify_import_do_selected))
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
