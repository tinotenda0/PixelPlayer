package com.theveloper.pixelplay.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.threeShapeSwitch
import com.theveloper.pixelplay.presentation.viewmodel.TasteOnboardingViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.PixelPlayStatusBarStyle
import com.theveloper.pixelplay.ui.theme.ShapeCache
import com.theveloper.pixelplay.utils.shapes.RoundedStarShape

/**
 * Pairwise "who do you prefer?" taste onboarding, styled to match the rest of the app: the
 * three-zone setup-page shell, Google Sans Rounded display type, squircle surfaces, scalloped
 * artist art and springy press feedback. Feeds the chosen artists to the server so the home
 * screen is curated from first launch. [onDone] fires once seeds are saved (or skipped).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TasteOnboardingScreen(
    onDone: () -> Unit,
    viewModel: TasteOnboardingViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    PixelPlayStatusBarStyle(color = MaterialTheme.colorScheme.surface)
    LaunchedEffect(ui.finished) { if (ui.finished) onDone() }
    BackHandler { viewModel.skip() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Header ───────────────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = ShapeCache.smoothPill,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = stringResource(
                            R.string.taste_round,
                            (ui.round + 1).coerceAtMost(ui.totalRounds),
                            ui.totalRounds
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                TextButton(onClick = { viewModel.skip() }) {
                    Text(stringResource(R.string.taste_skip))
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.taste_title),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = GoogleSansRounded,
                    fontSize = 32.sp
                ),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.taste_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // ── The choice ───────────────────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (ui.loading && ui.left == null) {
                LoadingIndicator(modifier = Modifier.size(48.dp))
            } else {
                AnimatedContent(
                    targetState = ui.left?.navidromeId to ui.right?.navidromeId,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(
                            initialScale = 0.92f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )) togetherWith fadeOut(tween(160))
                    },
                    label = "tastePair"
                ) { _ ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
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
            }
        }

        // ── Picks + add-your-own + finish ────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (ui.chosen.isNotEmpty()) {
                ChosenStrip(chosen = ui.chosen)
                Spacer(Modifier.height(14.dp))
            }

            TextField(
                value = ui.customQuery,
                onValueChange = { viewModel.updateCustomQuery(it) },
                placeholder = { Text(stringResource(R.string.taste_add_own)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.rounded_search_24), contentDescription = null)
                },
                singleLine = true,
                shape = ShapeCache.smoothPill,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (ui.customResults.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp).padding(top = 8.dp)
                ) {
                    items(ui.customResults, key = { it.navidromeId ?: it.name }) { artist ->
                        Surface(
                            onClick = { viewModel.addCustom(artist) },
                            shape = ShapeCache.smooth16,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SmartImage(
                                    model = artist.imageUrl,
                                    contentDescription = artist.name,
                                    shape = CircleShape,
                                    contentScale = ContentScale.Crop,
                                    placeholderResId = R.drawable.rounded_artist_24,
                                    errorResId = R.drawable.rounded_artist_24,
                                    modifier = Modifier.size(38.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.finish() },
                enabled = ui.chosen.isNotEmpty() && !ui.saving,
                shape = ShapeCache.smoothPill,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)
            ) {
                Text(
                    text = if (ui.chosen.isEmpty()) {
                        stringResource(R.string.taste_pick_prompt)
                    } else {
                        stringResource(R.string.taste_finish_count, ui.chosen.size)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

/** Overlapping scalloped avatars of the artists picked so far — taste building up visibly. */
@Composable
private fun ChosenStrip(chosen: List<Artist>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy((-12).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chosen.takeLast(8).forEachIndexed { index, artist ->
            val shape = threeShapeSwitch(index)
            SmartImage(
                model = artist.imageUrl,
                contentDescription = artist.name,
                shape = shape,
                contentScale = ContentScale.Crop,
                placeholderResId = R.drawable.rounded_artist_24,
                errorResId = R.drawable.rounded_artist_24,
                modifier = Modifier
                    .size(38.dp)
                    .border(2.dp, MaterialTheme.colorScheme.surface, shape)
            )
        }
    }
}

@Composable
private fun ArtistChoiceCard(
    artist: Artist?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tasteCardScale"
    )
    val artShape = remember { RoundedStarShape(sides = 8, curve = 0.05, rotation = 0f) }

    Surface(
        modifier = modifier
            .aspectRatio(0.78f)
            .scale(scale)
            .pointerInput(enabled, artist?.navidromeId) {
                if (!enabled || artist == null) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            },
        shape = ShapeCache.smooth24,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SmartImage(
                model = artist?.imageUrl,
                contentDescription = artist?.name,
                shape = artShape,
                contentScale = ContentScale.Crop,
                placeholderResId = R.drawable.rounded_artist_24,
                errorResId = R.drawable.rounded_artist_24,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = artist?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
