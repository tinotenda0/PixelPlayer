package com.theveloper.pixelplay.presentation.components

import androidx.compose.runtime.compositionLocalOf

/**
 * App-wide set of downloaded track ids (navidromeId), so any song row can show the Spotify-style
 * "downloaded" marker without every screen threading the state through. Provided near the nav host
 * from [com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel.downloadedSongIds].
 *
 * `compositionLocalOf` (not static) so only rows that actually read it recompose when the set
 * changes mid-download — a batch download updates this set once per finished track.
 */
val LocalDownloadedSongIds = compositionLocalOf { emptySet<String>() }
