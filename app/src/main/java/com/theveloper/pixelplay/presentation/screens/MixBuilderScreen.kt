@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.viewmodel.MixBuilderViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Custom mix builder: add a few artists, and the gateway blends their catalogues into a playlist
 * that it saves server-side, so it shows up for every device and every account in the household.
 */
@Composable
fun MixBuilderScreen(
    navController: NavController,
    playerViewModel: com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel,
    viewModel: MixBuilderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // On success, replace this screen with the playlist the gateway just saved: backing out of
    // the new playlist should return to where the user started, not to a stale builder.
    LaunchedEffect(uiState.builtPlaylistId) {
        val id = uiState.builtPlaylistId ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        navController.navigateSafelyReplacing(
            route = Screen.PlaylistDetail.createRoute(id),
            patternToPop = Screen.MixBuilder.route
        )
    }

    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissError()
    }

    // The app draws its own floating nav bar (and mini player) over the Scaffold, so the FAB has
    // to be lifted clear of them or it sits underneath and can't be tapped.
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomOccluded = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode) +
        MiniPlayerHeight

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Build a mix",
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.padding(bottom = bottomOccluded),
                onClick = viewModel::build,
                expanded = uiState.canBuild,
                icon = {
                    if (uiState.isBuilding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    }
                },
                text = { Text("Create mix", fontFamily = GoogleSansRounded) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = uiState.mixName,
                onValueChange = viewModel::onNameChange,
                label = { Text("Mix name (optional)") },
                singleLine = true,
                shape = AbsoluteSmoothCornerShape(20.dp, 60),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Add artists") },
                singleLine = true,
                shape = AbsoluteSmoothCornerShape(20.dp, 60),
                trailingIcon = {
                    if (uiState.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (uiState.picked.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.picked.forEach { artist ->
                        InputChip(
                            selected = true,
                            onClick = { viewModel.unpick(artist) },
                            label = { Text(artist.name, fontFamily = GoogleSansRounded) },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Remove ${artist.name}",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (uiState.suggestions.isEmpty() && uiState.picked.isEmpty()) {
                MixBuilderHint()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomOccluded + 96.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = uiState.suggestions,
                        key = { it.navidromeId ?: it.id.toString() }
                    ) { artist ->
                        MixArtistRow(artist = artist, onClick = { viewModel.pick(artist) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MixBuilderHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Pick a few artists",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "We'll blend their music into a playlist and save it to your library.",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = GoogleSansRounded,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MixArtistRow(artist: Artist, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(AbsoluteSmoothCornerShape(18.dp, 60))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        val image = artist.customImageUri ?: artist.imageUrl
        if (image.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(image)
                    .size(Size(120, 120))
                    .crossfade(true)
                    .build(),
                contentDescription = artist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = GoogleSansRounded,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Rounded.Add,
            contentDescription = "Add ${artist.name}",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
