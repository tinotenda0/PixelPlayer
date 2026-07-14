package com.theveloper.pixelplay.data.network.plex

import com.theveloper.pixelplay.data.plex.model.PlexAlbum
import com.theveloper.pixelplay.data.plex.model.PlexArtist
import com.theveloper.pixelplay.data.plex.model.PlexPlaylist
import com.theveloper.pixelplay.data.plex.model.PlexSong
import org.json.JSONObject
import timber.log.Timber

/**
 * Parser for Plex Media Server JSON responses.
 * Converts MediaContainer Metadata objects to Plex data models.
 */
object PlexResponseParser {

    private const val TAG = "PlexParser"

    fun parseSong(json: JSONObject): PlexSong {
        val media = json.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)

        val genres = buildList {
            json.optJSONArray("Genre")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }

        // originalTitle carries the track artist when it differs from the
        // album artist (grandparentTitle), e.g. compilations.
        val artist = json.optString("originalTitle").takeIf { it.isNotBlank() }
            ?: json.optString("grandparentTitle", "Unknown Artist")

        return PlexSong(
            id = json.optString("ratingKey", ""),
            title = json.optString("title", "Unknown Title"),
            artist = artist,
            artistId = json.optString("grandparentRatingKey").takeIf { it.isNotBlank() },
            album = json.optString("parentTitle", "Unknown Album"),
            albumId = json.optString("parentRatingKey").takeIf { it.isNotBlank() },
            duration = json.optLong("duration", 0L), // already milliseconds
            trackNumber = json.optInt("index", 0),
            discNumber = json.optInt("parentIndex", 0),
            year = json.optInt("year", json.optInt("parentYear", 0)),
            genre = genres.firstOrNull(),
            bitRate = media?.optInt("bitrate")?.takeIf { it > 0 }, // already kbps
            contentType = part?.optString("container")?.let { containerToMimeType(it) },
            path = part?.optString("file", "") ?: "",
            size = part?.optLong("size")?.takeIf { it > 0 },
            playCount = json.optInt("viewCount", 0),
            // Tracks rarely carry their own thumb; fall back to album, then artist art.
            thumb = json.optString("thumb").takeIf { it.isNotBlank() }
                ?: json.optString("parentThumb").takeIf { it.isNotBlank() }
                ?: json.optString("grandparentThumb").takeIf { it.isNotBlank() }
        )
    }

    fun parseSongs(jsonArray: List<JSONObject>): List<PlexSong> {
        return jsonArray.mapNotNull { json ->
            try {
                parseSong(json)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to parse song")
                null
            }
        }
    }

    fun parseAlbum(json: JSONObject): PlexAlbum {
        val genres = buildList {
            json.optJSONArray("Genre")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }

        return PlexAlbum(
            id = json.optString("ratingKey", ""),
            name = json.optString("title", "Unknown Album"),
            artist = json.optString("parentTitle", "Unknown Artist"),
            artistId = json.optString("parentRatingKey").takeIf { it.isNotBlank() },
            songCount = json.optInt("leafCount", 0),
            duration = json.optLong("duration", 0L),
            year = json.optInt("year", 0),
            genre = genres.firstOrNull()
        )
    }

    fun parseAlbums(jsonArray: List<JSONObject>): List<PlexAlbum> {
        return jsonArray.map { parseAlbum(it) }
    }

    fun parseArtist(json: JSONObject): PlexArtist {
        return PlexArtist(
            id = json.optString("ratingKey", ""),
            name = json.optString("title", "Unknown Artist"),
            albumCount = json.optInt("childCount", 0)
        )
    }

    fun parseArtists(jsonArray: List<JSONObject>): List<PlexArtist> {
        return jsonArray.map { parseArtist(it) }
    }

    fun parsePlaylist(json: JSONObject): PlexPlaylist {
        return PlexPlaylist(
            id = json.optString("ratingKey", ""),
            name = json.optString("title", "Unknown Playlist"),
            songCount = json.optInt("leafCount", 0),
            duration = json.optLong("duration", 0L),
            created = json.optLong("addedAt", 0L) * 1000, // seconds to millis
            changed = json.optLong("updatedAt", json.optLong("addedAt", 0L)) * 1000
        )
    }

    fun parsePlaylists(jsonArray: List<JSONObject>): List<PlexPlaylist> {
        return jsonArray.map { parsePlaylist(it) }
    }

    private fun containerToMimeType(container: String?): String? {
        if (container.isNullOrBlank()) return null
        return when (container.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "opus" -> "audio/opus"
            "webm" -> "audio/webm"
            else -> "audio/$container"
        }
    }
}
