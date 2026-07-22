package com.theveloper.pixelplay.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.TasteOnboardingViewModel

/**
 * Pairwise "who do you prefer?" taste onboarding. Feeds the chosen artists to the server so the
 * home screen is curated from first launch. [onDone] is called once seeds are saved (or skipped).
 */
@Composable
fun TasteOnboardingScreen(
    onDone: () -> Unit,
    viewModel: TasteOnboardingViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.finished) { if (ui.finished) onDone() }
    BackHandler { viewModel.skip() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Round ${(ui.round + 1).coerceAtMost(ui.totalRounds)} of ${ui.totalRounds}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.skip() }) { Text("Skip") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Who do you prefer?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Pick the artist you like more — we'll tune your mixes from your choices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            if (ui.loading && ui.left == null) {
                Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Disabled while the next pair loads, so extra taps can't double-count a round.
                    ArtistChoiceCard(
                        artist = ui.left,
                        enabled = !ui.loading && !ui.saving,
                        modifier = Modifier.weight(1f),
                        onClick = { ui.left?.let { viewModel.choose(it) } }
                    )
                    ArtistChoiceCard(
                        artist = ui.right,
                        enabled = !ui.loading && !ui.saving,
                        modifier = Modifier.weight(1f),
                        onClick = { ui.right?.let { viewModel.choose(it) } }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Add-your-own search
            OutlinedTextField(
                value = ui.customQuery,
                onValueChange = { viewModel.updateCustomQuery(it) },
                label = { Text("Add an artist you love") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (ui.customResults.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(ui.customResults, key = { it.navidromeId ?: it.name }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.addCustom(artist) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmartImage(
                                model = artist.imageUrl,
                                contentDescription = artist.name,
                                shape = CircleShape,
                                contentScale = ContentScale.Crop,
                                placeholderResId = R.drawable.rounded_artist_24,
                                errorResId = R.drawable.rounded_artist_24,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { viewModel.finish() },
                enabled = ui.chosen.isNotEmpty() && !ui.saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
            ) {
                Text(
                    if (ui.chosen.isEmpty()) "Pick a few artists"
                    else "Finish (${ui.chosen.size} chosen)"
                )
            }
        }
    }
}

@Composable
private fun ArtistChoiceCard(
    artist: Artist?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.aspectRatio(0.8f).clickable(enabled = artist != null && enabled) { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (artist == null) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.rounded_artist_24),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp)
                )
            } else {
                SmartImage(
                    model = artist.imageUrl,
                    contentDescription = artist.name,
                    shape = CircleShape,
                    contentScale = ContentScale.Crop,
                    placeholderResId = R.drawable.rounded_artist_24,
                    errorResId = R.drawable.rounded_artist_24,
                    modifier = Modifier.size(120.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
