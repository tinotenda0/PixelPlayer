package com.theveloper.pixelplay.presentation.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
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
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.utils.ShareCardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import timber.log.Timber

private const val MAX_SHARE_LINES = 6

/**
 * Bottom sheet for sharing a selection of lyric lines as a themed card image,
 * Spotify/Apple Music style: pick lines, preview the card, share as image or text.
 * Colors come from the album-derived scheme already used by the lyrics view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLyricsSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    song: Song,
    lyrics: Lyrics,
    containerColor: Color,
    contentColor: Color,
    accentColor: Color,
    onAccentColor: Color,
    cardContainerColor: Color,
    onCardContainerColor: Color
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val allLines = remember(lyrics) {
        (lyrics.synced?.map { it.line } ?: lyrics.plain.orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
    val selectedIndices = remember(allLines) { mutableStateOf(setOf<Int>()) }
    var isSharing by remember { mutableStateOf(false) }

    val selectedLines = remember(selectedIndices.value, allLines) {
        selectedIndices.value.sorted().mapNotNull { allLines.getOrNull(it) }
    }

    val graphicsLayer = rememberGraphicsLayer()
    val itemBackgroundColor = contentColor.copy(alpha = 0.08f)

    fun shareAsText() {
        if (selectedLines.isEmpty()) return
        val text = buildString {
            append("“")
            append(selectedLines.joinToString("\n"))
            append("”")
            append("\n— ")
            append(song.title)
            append(", ")
            append(song.displayArtist)
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.lyrics_share_chooser_title))
        )
    }

    fun shareAsImage() {
        if (selectedLines.isEmpty() || isSharing) return
        isSharing = true
        coroutineScope.launch {
            try {
                val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
                val uri = withContext(Dispatchers.IO) {
                    ShareCardUtils.writeCardToCache(context, bitmap, "shared_lyrics", "lyrics_card")
                }
                ShareCardUtils.shareImage(
                    context, uri, context.getString(R.string.lyrics_share_chooser_title)
                )
            } catch (e: Exception) {
                Timber.w(e, "ShareLyricsSheet: failed to render/share lyrics card")
            } finally {
                isSharing = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(top = 0, bottom = 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp + navigationBarsPadding)
        ) {
            Text(
                text = stringResource(R.string.lyrics_share_title),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.lyrics_share_subtitle, MAX_SHARE_LINES),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = contentColor.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(14.dp))

            // ─── Card preview: 9:16 story canvas, shown scaled down but
            //     captured at full composition size for a crisp share image. ───
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
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
                        LyricsShareCard(
                            song = song,
                            lines = selectedLines,
                            accentColor = accentColor
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ─── Line picker ───
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(20.dp)),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(allLines) { index, line ->
                    val isSelected = index in selectedIndices.value
                    val canSelectMore = selectedIndices.value.size < MAX_SHARE_LINES
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) accentColor.copy(alpha = 0.18f) else itemBackgroundColor
                            )
                            .clickable {
                                selectedIndices.value = when {
                                    isSelected -> selectedIndices.value - index
                                    canSelectMore -> selectedIndices.value + index
                                    else -> selectedIndices.value
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = line,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) accentColor else contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = accentColor
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { shareAsText() },
                    enabled = selectedLines.isNotEmpty() && !isSharing,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = AbsoluteSmoothCornerShape(18.dp, 60)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.lyrics_share_as_text),
                        fontFamily = GoogleSansRounded,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = { shareAsImage() },
                    enabled = selectedLines.isNotEmpty() && !isSharing,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = AbsoluteSmoothCornerShape(18.dp, 60),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = onAccentColor
                    )
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = onAccentColor
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
                        stringResource(R.string.lyrics_share_as_image),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// 9:16 story canvas (Instagram story / WhatsApp status proportions).
// Captured at composition size × device density (~900×1600 px at 3x).
internal val STORY_CARD_WIDTH = 300.dp
internal val STORY_CARD_HEIGHT = 533.dp
internal const val STORY_PREVIEW_SCALE = 0.58f

@Composable
private fun LyricsShareCard(
    song: Song,
    lines: List<String>,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(AbsoluteSmoothCornerShape(24.dp, 60))
            .background(Color(0xFF15151A))
    ) {
        // Cover art as the full-bleed background, heavily blurred.
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(42.dp)
                .scale(1.35f), // over-scan so blur edges never show through
            contentScale = ContentScale.Crop
        )

        // Scrim so the white text stays readable on any artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.72f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(26.dp)
        ) {
            Spacer(Modifier.weight(1f))

            // Song identity sits directly above the quoted lines, Spotify-style.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmartImage(
                    model = song.albumArtUriString,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(13.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.displayArtist,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.lyrics_share_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    color = Color.White.copy(alpha = 0.6f)
                )
            } else {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 23.sp,
                            lineHeight = 31.sp
                        ),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_lyrics_24),
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

