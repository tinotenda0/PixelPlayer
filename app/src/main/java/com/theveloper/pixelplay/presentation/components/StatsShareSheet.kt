package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.utils.ShareCardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import timber.log.Timber
import java.util.Locale

/**
 * Bottom sheet with a shareable recap card of the user's listening stats
 * for the currently selected time range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsShareSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    summary: PlaybackStatsRepository.PlaybackStatsSummary
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var isSharing by remember { mutableStateOf(false) }
    val graphicsLayer = rememberGraphicsLayer()

    fun share() {
        if (isSharing) return
        isSharing = true
        coroutineScope.launch {
            try {
                val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
                val uri = withContext(Dispatchers.IO) {
                    ShareCardUtils.writeCardToCache(context, bitmap, "shared_stats", "stats_recap")
                }
                ShareCardUtils.shareImage(
                    context, uri, context.getString(R.string.stats_share_chooser_title)
                )
            } catch (e: Exception) {
                Timber.w(e, "StatsShareSheet: failed to render/share recap card")
            } finally {
                isSharing = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(top = 0, bottom = 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp + navigationBarsPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.stats_share_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(14.dp))

            // 9:16 story canvas, previewed scaled down but captured full-size.
            Box(
                modifier = Modifier.size(
                    STORY_CARD_WIDTH * STORY_PREVIEW_SCALE,
                    STORY_CARD_HEIGHT * STORY_PREVIEW_SCALE
                ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .requiredSize(STORY_CARD_WIDTH, STORY_CARD_HEIGHT)
                        .scale(STORY_PREVIEW_SCALE)
                        .drawWithContent {
                            graphicsLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(graphicsLayer)
                        }
                ) {
                    StatsRecapCard(summary = summary)
                }
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { share() },
                enabled = !isSharing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = AbsoluteSmoothCornerShape(18.dp, 60)
            ) {
                if (isSharing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.IosShare,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.stats_share_action),
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StatsRecapCard(summary: PlaybackStatsRepository.PlaybackStatsSummary) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(AbsoluteSmoothCornerShape(24.dp, 60))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        containerColor,
                        Color(
                            red = containerColor.red * 0.72f,
                            green = containerColor.green * 0.72f,
                            blue = containerColor.blue * 0.72f,
                            alpha = 1f
                        )
                    )
                )
            )
            .padding(26.dp)
    ) {
        Text(
            text = stringResource(R.string.stats_share_card_title, summary.range.displayName),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RecapMetric(
                value = formatListeningTime(summary.totalDurationMs),
                label = stringResource(R.string.stats_share_metric_time),
                contentColor = contentColor,
                modifier = Modifier.weight(1.2f)
            )
            RecapMetric(
                value = summary.totalPlayCount.toString(),
                label = stringResource(R.string.stats_share_metric_plays),
                contentColor = contentColor,
                modifier = Modifier.weight(1f)
            )
            RecapMetric(
                value = summary.uniqueSongs.toString(),
                label = stringResource(R.string.stats_share_metric_songs),
                contentColor = contentColor,
                modifier = Modifier.weight(1f)
            )
        }

        val topSongs = summary.topSongs.take(3)
        if (topSongs.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.stats_share_top_songs),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold,
                color = contentColor.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(8.dp))
            topSongs.forEachIndexed { index, song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Spacer(Modifier.width(10.dp))
                    SmartImage(
                        model = song.albumArtUri,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(9.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = contentColor.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        val topArtists = summary.topArtists.take(3)
        if (topArtists.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.stats_share_top_artists),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold,
                color = contentColor.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = topArtists.joinToString("  ·  ") { it.artist },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(accentColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_lyrics_24),
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = contentColor
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold,
                color = contentColor.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun RecapMetric(
    value: String,
    label: String,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(contentColor.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = GoogleSansRounded,
            color = contentColor.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatListeningTime(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
        else -> String.format(Locale.getDefault(), "%dm", minutes)
    }
}
