package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private data class PlayerArtistShortcutItem(
    val ref: ArtistRef,
    val isPrimary: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerArtistPickerBottomSheet(
    song: Song,
    artists: List<ArtistRef>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onArtistClick: (ArtistRef) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val shortcutItems = remember(song.id, artists) {
        // Each ArtistRef already knows whether it is the primary credit and carries its own
        // gateway id, so no id/name matching against the song is needed. If nothing is flagged
        // primary (defensive), treat the first entry as primary so the list still highlights one.
        val hasPrimary = artists.any { it.isPrimary }
        artists
            .mapIndexed { index, ref ->
                PlayerArtistShortcutItem(
                    ref = ref,
                    isPrimary = if (hasPrimary) ref.isPrimary else index == 0
                )
            }
            .sortedByDescending { it.isPrimary }
    }

    val countLabel = when (shortcutItems.size) {
        1 -> stringResource(R.string.artist_picker_count_single)
        else -> stringResource(R.string.artist_picker_count_multiple, shortcutItems.size)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        containerColor = colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.artist_picker_title),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp)),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                shortcutItems.forEachIndexed { index, item ->
                    PlayerArtistShortcutCard(
                        name = item.ref.name,
                        isPrimary = item.isPrimary,
                        shape = artistShortcutShape(
                            index = index,
                            count = shortcutItems.size
                        ),
                        onClick = { onArtistClick(item.ref) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PlayerArtistShortcutCard(
    name: String,
    isPrimary: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (isPrimary) {
        colorScheme.secondaryContainer
    } else {
        colorScheme.surfaceContainerLow
    }
    val contentColor = if (isPrimary) {
        colorScheme.onSecondaryContainer
    } else {
        colorScheme.onSurface
    }
    val labelContainerColor = if (isPrimary) {
        colorScheme.tertiary
    } else {
        colorScheme.surfaceContainerHighest
    }
    val labelContentColor = if (isPrimary) {
        colorScheme.onTertiary
    } else {
        colorScheme.onSurfaceVariant
    }
    val avatarBackground = if (isPrimary) {
        colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
    } else {
        colorScheme.surfaceContainerHighest
    }
    val trailingContainerColor = contentColor.copy(alpha = 0.12f)
    val avatarSize = 52.dp

    Surface(
        onClick = onClick,
        color = containerColor,
        contentColor = contentColor,
        shape = shape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(avatarBackground),
                contentAlignment = Alignment.Center
            ) {
                // Credited-artist refs don't carry an image URL, so the picker shows the artist
                // glyph. The tap still opens the real profile (with its artwork) via the gateway id.
                SmartImage(
                    model = null,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    placeholderResId = R.drawable.rounded_artist_24,
                    errorResId = R.drawable.rounded_artist_24,
                    shape = CircleShape,
                    contentScale = ContentScale.Crop,
                    targetSize = Size(180, 180),
                    placeHolderBackgroundColor = Color.Transparent
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    color = labelContainerColor,
                    shape = CircleShape
                ) {
                    Text(
                        text = stringResource(
                            if (isPrimary) {
                                R.string.artist_picker_primary_label
                            } else {
                                R.string.artist_picker_shortcut_label
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = labelContentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(trailingContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = contentColor
                )
            }
        }
    }
}

private fun artistShortcutShape(
    index: Int,
    count: Int
): RoundedCornerShape {
    val outerCorner = 26.dp
    val innerCorner = 10.dp
    return when {
        count <= 1 -> RoundedCornerShape(outerCorner)
        index == 0 -> RoundedCornerShape(
            topStart = outerCorner,
            topEnd = outerCorner,
            bottomStart = innerCorner,
            bottomEnd = innerCorner
        )
        index == count - 1 -> RoundedCornerShape(
            topStart = innerCorner,
            topEnd = innerCorner,
            bottomStart = outerCorner,
            bottomEnd = outerCorner
        )
        else -> RoundedCornerShape(innerCorner)
    }
}
