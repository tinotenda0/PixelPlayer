package com.theveloper.pixelplay.presentation.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.SpotifyImportViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SpotifyImportViewModel.Phase

/**
 * Import a Spotify account's playlists, liked songs, top-artist taste and recent history into the
 * gateway (all re-matched to YouTube Music). The user approves access on Spotify's own consent page
 * in a browser — no credentials are typed here — then the gateway runs the import in the background.
 */
@Composable
fun SpotifyImportScreen(
    onBack: () -> Unit,
    viewModel: SpotifyImportViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Open Spotify's consent page when the VM asks (one-shot events).
    LaunchedEffect(Unit) {
        viewModel.openUrl.collect { url -> openUrl(context, url) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
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

                Phase.LINKED -> {
                    Body(
                        if (ui.accountName.isNotBlank()) {
                            stringResource(R.string.spotify_import_connected_as, ui.accountName)
                        } else stringResource(R.string.spotify_import_connected)
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { viewModel.startImport() }, enabled = !ui.busy) {
                        Text(stringResource(R.string.spotify_import_start))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.unlink() }) {
                        Text(stringResource(R.string.spotify_import_unlink))
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
            }
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
