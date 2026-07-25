package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.navidrome.JamHost
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.JamViewModel

/**
 * The Jam hub: a switch to let the household control THIS device, and a live list of household
 * phones currently playing that you can control. Control-only — audio stays on the host.
 */
@Composable
fun JamScreen(
    onBack: () -> Unit,
    viewModel: JamViewModel = hiltViewModel()
) {
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val allowControl by viewModel.allowControl.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val selected = hosts.firstOrNull { it.id == selectedId }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.jam_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 64.dp, end = 24.dp, top = 12.dp, bottom = 8.dp)
            )

            if (selected != null) {
                JamControlPanel(host = selected, viewModel = viewModel)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    item(key = "allow") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.jam_allow_title),
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.jam_allow_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(checked = allowControl,
                                    onCheckedChange = { viewModel.setAllowControl(it) })
                            }
                        }
                    }
                    item(key = "hdr") {
                        Text(stringResource(R.string.jam_devices_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                    if (hosts.isEmpty()) {
                        item(key = "empty") {
                            Text(stringResource(R.string.jam_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp))
                        }
                    } else {
                        items(hosts, key = { it.id }) { host ->
                            JamHostRow(host = host, onClick = { viewModel.select(host.id) })
                        }
                    }
                }
            }
        }

        FilledIconButton(
            onClick = { if (selected != null) viewModel.clearSelection() else onBack() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.statusBarsPadding().padding(start = 10.dp, top = 8.dp).clip(CircleShape)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back))
        }
    }
}

@Composable
private fun JamHostRow(host: JamHost, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmartImage(
            model = host.state.coverArt.takeIf { it.isNotBlank() },
            contentDescription = null,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${host.deviceName} · ${host.user}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host.state.title.ifBlank { stringResource(R.string.jam_idle) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun JamControlPanel(host: JamHost, viewModel: JamViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SmartImage(
            model = host.state.coverArt.takeIf { it.isNotBlank() },
            contentDescription = null,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(220.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(host.state.title.ifBlank { stringResource(R.string.jam_idle) },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(host.state.artist, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${host.deviceName} · ${host.user}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { viewModel.previous() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous",
                    modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = { viewModel.playPause() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (host.state.isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next",
                    modifier = Modifier.size(40.dp))
            }
        }
    }
}
