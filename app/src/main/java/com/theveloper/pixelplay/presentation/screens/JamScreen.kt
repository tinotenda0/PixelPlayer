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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.theveloper.pixelplay.data.navidrome.ActiveSession
import com.theveloper.pixelplay.data.navidrome.DeviceSession
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.JamViewModel

/**
 * The Devices hub: this account's one canonical session - wherever it's currently active - with
 * play-here/remote-control (personal handoff), a send-only list of this account's other devices,
 * and a switch + live list for the household (Jam - control-only, audio stays put).
 */
@Composable
fun JamScreen(
    onBack: () -> Unit,
    viewModel: JamViewModel = hiltViewModel()
) {
    val mySession by viewModel.mySession.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val householdSessions by viewModel.householdSessions.collectAsStateWithLifecycle()
    val allowControl by viewModel.allowControl.collectAsStateWithLifecycle()
    val selectedUser by viewModel.selectedUser.collectAsStateWithLifecycle()
    val personalPanelOpen by viewModel.personalPanelOpen.collectAsStateWithLifecycle()
    val selectedHousehold = householdSessions.firstOrNull { it.user == selectedUser }

    // Worth a banner/Play Here only when the account's session is active somewhere else -
    // if it's already here, or nothing's playing anywhere, there's nothing to pull.
    val myRemoteSession = mySession?.takeIf { it.activeDeviceId != viewModel.mySessionId }
    val otherDevices = devices.filter { it.id != mySession?.activeDeviceId }

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

            if (personalPanelOpen && myRemoteSession != null) {
                PersonalControlPanel(session = myRemoteSession, viewModel = viewModel)
            } else if (selectedHousehold != null) {
                JamControlPanel(session = selectedHousehold, viewModel = viewModel)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    if (myRemoteSession != null) {
                        item(key = "my-session") {
                            MySessionBanner(
                                session = myRemoteSession,
                                onClick = { viewModel.openPersonalPanel() },
                                onPlayHere = { viewModel.playHere() }
                            )
                        }
                    }
                    item(key = "my-hdr") {
                        Text(stringResource(R.string.jam_my_devices_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                    if (otherDevices.isEmpty()) {
                        item(key = "my-empty") {
                            Text(stringResource(R.string.jam_my_devices_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 16.dp))
                        }
                    } else {
                        items(otherDevices, key = { it.id }) { device ->
                            DeviceRow(device = device, onSend = { viewModel.sendTo(device.id) })
                        }
                    }
                    item(key = "allow") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
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
                    if (householdSessions.isEmpty()) {
                        item(key = "empty") {
                            Text(stringResource(R.string.jam_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp))
                        }
                    } else {
                        items(householdSessions, key = { it.user }) { session ->
                            JamHostRow(session = session, onClick = { viewModel.selectHousehold(session.user) })
                        }
                    }
                }
            }
        }

        FilledIconButton(
            onClick = {
                when {
                    personalPanelOpen -> viewModel.closePersonalPanel()
                    selectedHousehold != null -> viewModel.clearHouseholdSelection()
                    else -> onBack()
                }
            },
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

/** This account's session, playing on a different device - tap to open remote control. */
@Composable
private fun MySessionBanner(
    session: ActiveSession,
    onClick: () -> Unit,
    onPlayHere: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmartImage(
                model = session.state.coverArt.takeIf { it.isNotBlank() },
                contentDescription = null,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.jam_playing_on_format, session.deviceName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(session.state.title.ifBlank { stringResource(R.string.jam_idle) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onPlayHere) {
                Icon(Icons.Rounded.PhoneAndroid, contentDescription = stringResource(R.string.jam_play_here),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceSession, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.deviceName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.jam_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.jam_send_here))
        }
    }
}

@Composable
private fun PersonalControlPanel(session: ActiveSession, viewModel: JamViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SmartImage(
            model = session.state.coverArt.takeIf { it.isNotBlank() },
            contentDescription = null,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(220.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(session.state.title.ifBlank { stringResource(R.string.jam_idle) },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(session.state.artist, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(session.deviceName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { viewModel.previousSelf() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous",
                    modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = { viewModel.playPauseSelf() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (session.state.isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { viewModel.nextSelf() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next",
                    modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { viewModel.playHere() }) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.jam_play_here))
        }
    }
}

@Composable
private fun JamHostRow(session: ActiveSession, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmartImage(
            model = session.state.coverArt.takeIf { it.isNotBlank() },
            contentDescription = null,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${session.deviceName} · ${session.user}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(session.state.title.ifBlank { stringResource(R.string.jam_idle) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun JamControlPanel(session: ActiveSession, viewModel: JamViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SmartImage(
            model = session.state.coverArt.takeIf { it.isNotBlank() },
            contentDescription = null,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(220.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(session.state.title.ifBlank { stringResource(R.string.jam_idle) },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(session.state.artist, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${session.deviceName} · ${session.user}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { viewModel.previousHousehold() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous",
                    modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = { viewModel.playPauseHousehold() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (session.state.isPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { viewModel.nextHousehold() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next",
                    modifier = Modifier.size(40.dp))
            }
        }
    }
}
