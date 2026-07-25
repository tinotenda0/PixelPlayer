package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The small "E" explicit-content marker, mirroring the badge YouTube Music / Spotify show on
 * explicit tracks. Rendered next to a song title wherever songs are listed.
 */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "E",
            color = MaterialTheme.colorScheme.surface,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall.copy(
                lineHeight = 10.sp,
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
            ),
            modifier = Modifier.padding(0.dp)
        )
    }
}
