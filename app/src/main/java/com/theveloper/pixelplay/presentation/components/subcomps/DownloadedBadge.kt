package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R

/**
 * The "downloaded / available offline" marker — a small green circle with a down-arrow, mirroring
 * Spotify's indicator. Shown on song rows whose track id is in [LocalDownloadedSongIds].
 */
@Composable
fun DownloadedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(DownloadedGreen),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.rounded_download_24),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(11.dp)
        )
    }
}

private val DownloadedGreen = Color(0xFF1DB954)
