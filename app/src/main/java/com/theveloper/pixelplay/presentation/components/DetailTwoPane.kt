package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R

/**
 * Detail-page layout for wide windows: a fixed hero pane on the start edge and the scrolling
 * content on the end edge.
 *
 * The portrait pages use a collapsing header, which trades vertical space for artwork as you
 * scroll. In landscape vertical space is the scarce resource and horizontal space is abundant, so
 * the trade goes the other way: the artwork and actions stay permanently on screen and the list
 * scrolls beside them, never underneath them.
 *
 * @param heroPane artwork, titles and actions. Scrolls on its own if the window is short.
 * @param listPane the tracks. Receives the full height of the pane.
 */
@Composable
fun DetailTwoPaneLayout(
    onBackPressed: () -> Unit,
    heroPane: @Composable ColumnScope.() -> Unit,
    listPane: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // A third of the window, kept inside bounds that stay comfortable on both a phone on its
        // side (~915dp) and a large tablet (~1280dp).
        val heroWidth: Dp = (maxWidth * 0.34f).coerceIn(280.dp, 420.dp)

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(heroWidth)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)
            ) {
                FilledIconButton(
                    onClick = onBackPressed,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                heroPane()
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                listPane()
            }
        }
    }
}

/**
 * The standard contents of a [DetailTwoPaneLayout] hero pane: artwork, title, subtitle, a metadata
 * line and the primary play / shuffle actions.
 *
 * @param artworkShape circle for an artist, rounded rectangle for an album or playlist.
 * @param extraContent appended under the buttons, for anything page-specific.
 */
@Composable
fun ColumnScope.DetailHeroContent(
    artworkModel: Any?,
    artworkContentDescription: String?,
    title: String,
    subtitle: String?,
    meta: String?,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    artworkShape: Shape = RoundedCornerShape(20.dp),
    extraContent: @Composable ColumnScope.() -> Unit = {}
) {
    SmartImage(
        model = artworkModel,
        contentDescription = artworkContentDescription,
        shape = artworkShape,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
    if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (!meta.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = meta,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (onPlay != null || onShuffle != null) {
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (onPlay != null) {
                Button(
                    onClick = onPlay,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_play_arrow_24),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.common_play),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onShuffle != null) {
                OutlinedButton(
                    onClick = onShuffle,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_shuffle_24),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.common_shuffle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    extraContent()
}
