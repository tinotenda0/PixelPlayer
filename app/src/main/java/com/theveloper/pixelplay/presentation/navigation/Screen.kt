package com.theveloper.pixelplay.presentation.navigation

import androidx.compose.runtime.Immutable


@Immutable
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object Accounts : Screen("settings_accounts")
    object SettingsCategory : Screen("settings_category/{categoryId}") {
        fun createRoute(categoryId: String) = "settings_category/$categoryId"
    }
    object PaletteStyle : Screen("palette_style_settings")
    object Experimental : Screen("experimental_settings")
    object NavBarCrRad : Screen("nav_bar_corner_radius")
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist_detail/$playlistId"
    }

    object  DailyMixScreen : Screen("daily_mix")
    object RecentlyPlayed : Screen("recently_played")
    object Stats : Screen("stats")
    object GenreDetail : Screen("genre_detail/{genreId}") { // New screen
        fun createRoute(genreId: String) = "genre_detail/$genreId"
    }
    object DJSpace : Screen("dj_space")
    object YtMusicLink : Screen("ytmusic_link")
    object MixBuilder : Screen("mix_builder")
    // La ruta base es "album_detail". La ruta completa con el argumento se define en AppNavigation.
    object AlbumDetail : Screen("album_detail/{albumId}") {
        // Función de ayuda para construir la ruta de navegación con el ID del álbum.
        fun createRoute(albumId: Long) = "album_detail/$albumId"
        // Gateway album id (e.g. "yt-album-<browseId>"); browse ids are path-safe.
        fun createRoute(albumId: String) = "album_detail/$albumId"
    }

    object ArtistDetail : Screen("artist_detail/{artistId}?name={name}") {
        /**
         * The optional `name` is a fallback, not decoration: a streamed song has no local artist
         * row, so its numeric id is -1 and the lookup dead-ends at "Could not find the artist".
         * Carrying the display name lets the screen recover by resolving upstream instead.
         */
        fun createRoute(artistId: Long, name: String? = null) = build(artistId.toString(), name)

        // Gateway artist id (e.g. "yt-artist-<browseId>"); browse ids are path-safe.
        fun createRoute(artistId: String, name: String? = null) = build(artistId, name)

        private fun build(id: String, name: String?): String =
            if (name.isNullOrBlank()) "artist_detail/$id"
            else "artist_detail/$id?name=${android.net.Uri.encode(name)}"
    }

    object EditTransition : Screen("edit_transition?playlistId={playlistId}") {
        fun createRoute(playlistId: String?) =
            if (playlistId != null) "edit_transition?playlistId=$playlistId" else "edit_transition"
    }

    object About : Screen("about")
    object OpenSourceLicenses : Screen("open_source_licenses")
    object EasterEgg : Screen("easter_egg")

    object ArtistSettings : Screen("artist_settings")
    object DelimiterConfig : Screen("delimiter_config")
    object WordDelimiterConfig : Screen("word_delimiter_config")
    object Equalizer : Screen("equalizer")
    object DeviceCapabilities : Screen("device_capabilities")
    object NeteaseDashboard : Screen("netease_dashboard")
    object QqMusicDashboard : Screen("qqmusic_dashboard")
    object NavidromeDashboard : Screen("navidrome_dashboard")
    object JellyfinDashboard : Screen("jellyfin_dashboard")
    object PlexDashboard : Screen("plex_dashboard")

}
