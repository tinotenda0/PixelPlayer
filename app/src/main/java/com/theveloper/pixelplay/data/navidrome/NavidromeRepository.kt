@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.navidrome

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.NavidromeDao
import com.theveloper.pixelplay.data.database.NavidromePlaylistEntity
import com.theveloper.pixelplay.data.database.NavidromeSongEntity
import com.theveloper.pixelplay.data.database.toEntity
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.database.decodeArtistRefs
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.model.NavidromeAlbum
import com.theveloper.pixelplay.data.navidrome.model.NavidromeArtist
import com.theveloper.pixelplay.data.navidrome.model.NavidromeCredentials
import com.theveloper.pixelplay.data.navidrome.model.NavidromeSong
import com.theveloper.pixelplay.data.network.navidrome.NavidromeApiService
import com.theveloper.pixelplay.data.network.navidrome.NavidromeResponseParser
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.stream.BulkSyncResult
import com.theveloper.pixelplay.data.stream.CloudMusicUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import androidx.core.content.edit

/**
 * Repository for Navidrome/Subsonic music service.
 *
 * Manages authentication, playlist synchronization, and song caching.
 */
@Suppress("DEPRECATION")
@Singleton
class NavidromeRepository @Inject constructor(
    private val api: NavidromeApiService,
    private val dao: NavidromeDao,
    private val musicDao: MusicDao,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SYNC_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val TAG = "NavidromeRepo"
        private const val PREFS_NAME = "navidrome_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAST_FULL_SYNC = "last_full_sync"

        // ID offsets for unified library (following Netease: 3-5, QQ: 6-8)
        // Using negative offsets to prevent collisions with MediaStore IDs
        private const val NAVIDROME_SONG_ID_OFFSET = 9_000_000_000_000L
        private const val NAVIDROME_ALBUM_ID_OFFSET = 10_000_000_000_000L
        private const val NAVIDROME_ARTIST_ID_OFFSET = 11_000_000_000_000L
        private const val NAVIDROME_PARENT_DIRECTORY = "/Cloud/Navidrome"
        private const val NAVIDROME_GENRE = "Navidrome"
        private const val NAVIDROME_PLAYLIST_PREFIX = "navidrome_playlist:"
        private const val LIBRARY_PLAYLIST_ID = "__library__"
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Timber.e(e, "$TAG: Failed to create EncryptedSharedPreferences, falling back to plain")
        context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
    }

    private val _isLoggedInFlow = MutableStateFlow(false)
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedInFlow.asStateFlow()

    init {
        initFromSavedCredentials()
    }

    // ─── Authentication ──────────────────────────────────────────────────

    /**
     * Initialize API from saved credentials.
     */
    private fun initFromSavedCredentials() {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)
        val username = prefs.getString(KEY_USERNAME, null)
        val password = prefs.getString(KEY_PASSWORD, null)

        if (!serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
            val credentials = NavidromeCredentials(serverUrl, username, password)
            val validationError = credentials.connectionValidationError()
            if (validationError != null) {
                Timber.w("$TAG: Ignoring insecure or invalid saved Navidrome server URL: $validationError")
                api.clearCredentials()
                _isLoggedInFlow.value = false
                return
            }
            api.setCredentials(credentials)
            _isLoggedInFlow.value = true
            Timber.d("$TAG: Restored credentials for $username@${credentials.normalizedServerUrl}")
        }
    }

    /**
     * Check if user is logged in.
     */
    val isLoggedIn: Boolean
        get() = _isLoggedInFlow.value

    /**
     * Get the current server URL.
     */
    val serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)

    /**
     * Get the current username.
     */
    val username: String?
        get() = prefs.getString(KEY_USERNAME, null)

    var lastFullSyncTime: Long
        get() = prefs.getLong(KEY_LAST_FULL_SYNC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_FULL_SYNC, value) }

    /**
     * Login to Navidrome server with credentials.
     *
     * @param serverUrl The server URL (e.g., "https://music.example.com")
     * @param username The username
     * @param password The password
     * @return Result with username on success, error on failure
     */
    suspend fun login(serverUrl: String, username: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("$TAG: Attempting login to $serverUrl as $username")

                val credentials = NavidromeCredentials(serverUrl, username, password)
                val validationError = credentials.connectionValidationError()
                if (validationError != null) {
                    api.clearCredentials()
                    return@withContext Result.failure(IllegalArgumentException(validationError))
                }
                api.setCredentials(credentials)

                // Test connection
                val pingResult = api.ping()
                if (pingResult.isFailure) {
                    api.clearCredentials()
                    return@withContext Result.failure(
                        pingResult.exceptionOrNull() ?: Exception("Connection failed")
                    )
                }

                // Save credentials
                prefs.edit {
                    putString(KEY_SERVER_URL, credentials.normalizedServerUrl)
                        .putString(KEY_USERNAME, username)
                        .putString(KEY_PASSWORD, password)
                }

                _isLoggedInFlow.value = true
                Timber.d("$TAG: Login successful for $username@$serverUrl")
                Result.success(username)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Login failed")
                api.clearCredentials()
                _isLoggedInFlow.value = false
                Result.failure(e)
            }
        }
    }

    /**
     * Logout and clear all cached data.
     */
    suspend fun logout() {
        Timber.d("$TAG: Logging out")
        api.clearCredentials()
        prefs.edit { clear() }

        // Delete all Navidrome playlists from database
        val playlistsToDelete = dao.getAllPlaylistsList()
        playlistsToDelete.forEach { playlist ->
            dao.deleteSongsByPlaylist(playlist.id)
            deleteAppPlaylistForNavidromePlaylist(playlist.id)
        }

        musicDao.clearAllNavidromeSongs()
        dao.clearAllPlaylists()
        _isLoggedInFlow.value = false
    }

    // ─── Playlists ────────────────────────────────────────────────────────

    /**
     * Sync user playlists from server.
     */
    suspend fun syncPlaylists(): Result<List<NavidromePlaylistEntity>> {
        if (!isLoggedIn) {
            return Result.failure(Exception("Not logged in"))
        }

        return withContext(Dispatchers.IO) {
            try {
                Timber.d("$TAG: Syncing playlists")
                val result = api.getPlaylists()

                if (result.isFailure) {
                    return@withContext Result.failure(
                        result.exceptionOrNull() ?: Exception("Failed to get playlists")
                    )
                }

                val jsonObjects = result.getOrThrow()
                val playlists = NavidromeResponseParser.parsePlaylists(jsonObjects)

                // CRITICAL BUG FIX: If we have local playlists but the server returns an empty list,
                // do NOT proceed with syncing or deleting. This is likely a transient error or empty response.
                // We only delete stale playlists if we actually got some data back to compare with.
                if (playlists.isEmpty() && jsonObjects.isNotEmpty()) {
                    Timber.w("$TAG: Parser returned empty playlists but JSON response had items. Parsing error suspected. Aborting.")
                    return@withContext Result.failure(Exception("Playlist parsing error"))
                }

                if (playlists.isEmpty()) {
                    val localCount = dao.getPlaylistCount()
                    if (localCount > 0) {
                        Timber.w("$TAG: Server returned empty playlists but we have $localCount locally. Aborting sync to prevent data loss.")
                        return@withContext Result.success(emptyList()) 
                    }
                }

                val entities = playlists.map { playlist ->
                    NavidromePlaylistEntity(
                        id = playlist.id,
                        name = playlist.name,
                        comment = playlist.comment,
                        owner = playlist.owner,
                        coverArtId = playlist.coverArt,
                        songCount = playlist.songCount,
                        duration = playlist.duration,
                        public = playlist.public,
                        lastSyncTime = System.currentTimeMillis()
                    )
                }

                // Remove stale playlists
                // CRITICAL: Only remove if we successfully fetched at least one playlist OR the fetch was a success but the user has none.
                // Avoid clearing all if it's a transient network error that wasn't caught.
                val localPlaylists = dao.getAllPlaylistsList()
                val remoteIds = entities.map { it.id }.toSet()
                
                // FIXED: If entities is empty, we already handled the protection (localCount > 0) above.
                // However, we must ensure we ONLY delete playlists if the API response was TRULY empty (jsonObjects is empty).
                val stalePlaylists = if (entities.isNotEmpty() || jsonObjects.isEmpty()) {
                    localPlaylists.filter { it.id !in remoteIds }
                } else {
                    emptyList()
                }

                if (stalePlaylists.isNotEmpty()) {
                    Timber.d("$TAG: Removing ${stalePlaylists.size} stale playlists")
                    stalePlaylists.forEach { stale ->
                        dao.deleteSongsByPlaylist(stale.id)
                        dao.deletePlaylist(stale.id)
                        deleteAppPlaylistForNavidromePlaylist(stale.id)
                    }
                }

                // Insert updated playlists
                entities.forEach { dao.insertPlaylist(it) }

                if (stalePlaylists.isNotEmpty()) {
                    syncUnifiedLibrarySongsFromNavidrome()
                }

                Timber.d("$TAG: Synced ${entities.size} playlists")
                Result.success(entities)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync playlists")
                Result.failure(e)
            }
        }
    }

    /**
     * Sync songs in a specific playlist.
     */
    suspend fun syncPlaylistSongs(playlistId: String): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("$TAG: Syncing songs for playlist $playlistId")

                val result = api.getPlaylist(playlistId)
                if (result.isFailure) {
                    return@withContext Result.failure(
                        result.exceptionOrNull() ?: Exception("Failed to get playlist")
                    )
                }

                val res: Pair<JSONObject, List<JSONObject>> = result.getOrThrow()
                val songJsons = res.second
                val songs = NavidromeResponseParser.parseSongs(songJsons)

                // CRITICAL BUG FIX: If the server returns empty songs (e.g. failure to parse or server error)
                // but counts are positive, we do NOT empty our local cache.
                if (songs.isEmpty() && songJsons.isNotEmpty()) {
                    Timber.w("$TAG: FAILED to parse songs for playlist $playlistId even though JSON has data. Aborting.")
                    return@withContext Result.failure(Exception("Parsing error"))
                }

                val entities = songs.map { song: NavidromeSong ->
                    song.toEntity(playlistId)
                }

                if (entities.isNotEmpty()) {
                    Timber.d("$TAG: Playlist $playlistId - Deleting old songs, inserting ${entities.size} new songs")
                    dao.deleteSongsByPlaylist(playlistId)
                    dao.insertSongs(entities)
                    
                    // Update app playlist only if we have data
                    val playlistName = dao.getPlaylistById(playlistId)?.name ?: "Playlist"
                    updateAppPlaylistForNavidromePlaylist(playlistId, playlistName, entities)
                } else if (songJsons.isEmpty()) {
                    // This is a TRULY empty playlist on the server.
                    // We should ONLY clear it if we actually got a successful empty list response,
                    // not a parse error.
                    Timber.d("$TAG: Playlist $playlistId is empty on server, clearing local cache")
                    dao.deleteSongsByPlaylist(playlistId)
                    val playlistName = dao.getPlaylistById(playlistId)?.name ?: "Playlist"
                    updateAppPlaylistForNavidromePlaylist(playlistId, playlistName, emptyList())
                } else {
                    Timber.w("$TAG: songJsons was not empty (${songJsons.size}) but entities was empty. Parsing issue?")
                }

                // NOTE: Unified library sync is now handled by the caller (e.g., syncAllPlaylistsAndSongs)
                // to avoid multiple redundant syncs. If you need immediate sync for single playlist,
                // call syncUnifiedLibrarySongsFromNavidrome() after this method.

                Timber.d("$TAG: Synced ${entities.size} songs for playlist $playlistId")
                Result.success(entities.size)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync playlist songs")
                Result.failure(e)
            }
        }
    }

    /**
     * Sync all songs from the server library by fetching all albums.
     */
    suspend fun syncLibrarySongs(
        onProgress: ((Float, String) -> Unit)? = null
    ): Result<Int> {
        if (!isLoggedIn) {
            return Result.failure(Exception("Not logged in"))
        }

        return withContext(Dispatchers.IO) {
            try {
                Timber.d("$TAG: Syncing library songs from server")
                val allSongs = mutableListOf<NavidromeSong>()
                val pageSize = 500
                
                onProgress?.invoke(0.1f, context.getString(R.string.cloud_sync_status_fetching_albums))
                val fetchedAlbums = fetchAllAlbums(pageSize)

                // Fetch songs for each album in parallel
                val totalAlbums = fetchedAlbums.size
                val concurrencyLimit = 5
                val semaphore = Semaphore(concurrencyLimit)
                val processedCount = AtomicInteger(0)

                val albumSongLists = coroutineScope {
                    fetchedAlbums.map { albumJson ->
                        async {
                            semaphore.withPermit {
                                val albumId = albumJson.optString("id", "")
                                val albumTitle = albumJson.optString("title", "Unknown Album")
                                if (albumId.isBlank()) return@withPermit emptyList()

                                val songsResult = api.getAlbum(albumId)
                                val currentProcessed = processedCount.incrementAndGet()
                                
                                val progress = 0.1f + (currentProcessed.toFloat() / totalAlbums.coerceAtLeast(1) * 0.8f)
                                onProgress?.invoke(
                                    progress, 
                                    context.getString(R.string.cloud_sync_status_fetching_songs_from_format, albumTitle)
                                )

                                songsResult.fold(
                                    onSuccess = { songJsons ->
                                        NavidromeResponseParser.parseSongs(songJsons)
                                    },
                                    onFailure = {
                                        Timber.w(it, "$TAG: Failed to fetch songs for album $albumId")
                                        emptyList()
                                    }
                                )
                            }
                        }
                    }.awaitAll()
                }

                allSongs.addAll(albumSongLists.flatten())

                if (allSongs.isEmpty()) {
                    Timber.d("$TAG: No library songs found on server")
                    onProgress?.invoke(1f, context.getString(R.string.cloud_sync_status_no_songs_found))
                    return@withContext Result.success(0)
                }

                onProgress?.invoke(
                    0.95f, 
                    context.getString(R.string.cloud_sync_status_saving_songs_format, allSongs.size)
                )
                // Deduplicate by song ID
                val uniqueSongs = allSongs.distinctBy { it.id }

                val entities = uniqueSongs.map { song ->
                    song.toEntity(LIBRARY_PLAYLIST_ID)
                }

                // Replace all library songs
                dao.clearLibrarySongs()
                dao.insertSongs(entities)

                Timber.d("$TAG: Synced ${entities.size} library songs from ${fetchedAlbums.size} albums")
                onProgress?.invoke(1f, context.getString(R.string.cloud_sync_status_library_sync_complete))
                Result.success(entities.size)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync library songs")
                Result.failure(e)
            }
        }
    }

    /**
     * Fetch all albums from server with pagination.
     */
    private suspend fun fetchAllAlbums(pageSize: Int): List<JSONObject> {
        val allAlbums = mutableListOf<JSONObject>()
        var offset = 0

        while (true) {
            val albumsResult = api.getAlbumList(
                type = "alphabeticalByName",
                size = pageSize,
                offset = offset
            )

            val albumJsons = albumsResult.getOrNull()
            if (albumJsons.isNullOrEmpty()) break

            allAlbums.addAll(albumJsons)
            offset += albumJsons.size
            if (albumJsons.size < pageSize) break
        }

        return allAlbums
    }

    /**
     * Sync all playlists and their songs, plus library songs.
     */
    suspend fun syncAllPlaylistsAndSongs(
        onProgress: ((Float, String) -> Unit)? = null
    ): Result<BulkSyncResult> {
        return withContext(Dispatchers.IO) {
            var syncedSongCount = 0
            var failedPlaylistCount = 0

            onProgress?.invoke(0.05f, context.getString(R.string.cloud_sync_status_syncing_library))
            // Sync library songs (all albums)
            val libResult = syncLibrarySongs { progress, message ->
                // Map library sync progress (0-1) to 0.05-0.4 range
                onProgress?.invoke(0.05f + (progress * 0.35f), message)
            }
            libResult.fold(
                onSuccess = { count -> syncedSongCount += count },
                onFailure = { Timber.w(it, "$TAG: Failed syncing library songs") }
            )

            onProgress?.invoke(0.4f, context.getString(R.string.cloud_sync_status_fetching_playlists))
            // Sync playlists
            val playlistResult = syncPlaylists().getOrElse {
                // Playlists failed but library songs may have synced
                try {
                    syncUnifiedLibrarySongsFromNavidrome()
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to sync unified library after playlist fetch failure")
                }
                return@withContext Result.success(
                    BulkSyncResult(
                        playlistCount = 0,
                        syncedSongCount = syncedSongCount,
                        failedPlaylistCount = 0
                    )
                )
            }

            val totalPlaylists = playlistResult.size
            playlistResult.forEachIndexed { index, playlist ->
                val progressBase = 0.4f
                val progressStep = 0.5f / totalPlaylists.coerceAtLeast(1)
                val currentProgress = progressBase + (index * progressStep)
                
                onProgress?.invoke(
                    currentProgress, 
                    context.getString(R.string.cloud_sync_status_syncing_playlist_format, playlist.name)
                )
                
                val songSyncResult = syncPlaylistSongs(playlist.id)
                songSyncResult.fold(
                    onSuccess = { count -> syncedSongCount += count },
                    onFailure = {
                        failedPlaylistCount += 1
                        Timber.w(it, "$TAG: Failed syncing playlist ${playlist.id}")
                    }
                )
            }

            onProgress?.invoke(0.95f, context.getString(R.string.cloud_sync_status_updating_local))
            // Sync to unified library once after everything is synced
            try {
                syncUnifiedLibrarySongsFromNavidrome()
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync unified library")
            }

            onProgress?.invoke(1f, context.getString(R.string.cloud_sync_status_sync_complete))

            if (failedPlaylistCount == 0) {
                lastFullSyncTime = System.currentTimeMillis()
            }

            Result.success(
                BulkSyncResult(
                    playlistCount = playlistResult.size,
                    syncedSongCount = syncedSongCount,
                    failedPlaylistCount = failedPlaylistCount
                )
            )
        }
    }

    /**
     * Get all playlists as Flow.
     */
    fun getPlaylists(): Flow<List<NavidromePlaylistEntity>> = dao.getAllPlaylists()

    /**
     * Get songs in a playlist as Flow of Song.
     */
    fun getPlaylistSongs(playlistId: String): Flow<List<Song>> {
        return dao.getSongsByPlaylist(playlistId).map { entities ->
            entities.map { it.toSong() }
        }
    }

    /**
     * Get all Navidrome songs as Flow.
     */
    fun getAllSongs(): Flow<List<Song>> {
        return dao.getAllNavidromeSongs().map { entities ->
            entities.map { it.toSong() }
        }
    }

    // ─── Search ────────────────────────────────────────────────────────────

    /**
     * Search for songs on the server.
     */
    suspend fun searchSongs(query: String, limit: Int = 30): Result<List<Song>> {
        if (!isLoggedIn) {
            return Result.failure(Exception("Not logged in"))
        }

        return withContext(Dispatchers.IO) {
            try {
                val result = api.searchSongs(query, count = limit)
                if (result.isFailure) {
                    return@withContext Result.failure(
                        result.exceptionOrNull() ?: Exception("Search failed")
                    )
                }

                val jsonObjects = result.getOrThrow()
                val navidromeSongs = NavidromeResponseParser.parseSongs(jsonObjects)
                val songs = navidromeSongs.map { it.toSong() }

                Result.success(songs)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Search failed")
                Result.failure(e)
            }
        }
    }

    /**
     * Search local cached songs.
     */
    fun searchLocalSongs(query: String): Flow<List<Song>> {
        return dao.searchSongs(query).map { entities ->
            entities.map { it.toSong() }
        }
    }

    /** Live artist search results, as app [Artist]s carrying their gateway id. */
    suspend fun searchArtists(query: String, limit: Int = 10): Result<List<Artist>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val json = api.searchArtists(query, count = limit).getOrThrow()
                Result.success(NavidromeResponseParser.parseArtists(json).map { it.toAppArtist() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: searchArtists failed"); Result.failure(e)
            }
        }
    }

    /** Live album search results, as app [Album]s carrying their gateway id. */
    suspend fun searchAlbums(query: String, limit: Int = 20): Result<List<Album>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val json = api.searchAlbums(query, count = limit).getOrThrow()
                Result.success(NavidromeResponseParser.parseAlbums(json).map { it.toAppAlbum() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: searchAlbums failed"); Result.failure(e)
            }
        }
    }

    /** Artist detail fetched live for a gateway `yt-artist-…` id: the artist + its top songs. */
    suspend fun getArtistDetail(artistId: String): Result<GatewayArtistDetail> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val obj = api.getArtistWithAlbums(artistId).getOrThrow()
                val artist = NavidromeResponseParser.parseArtist(obj).toAppArtist()

                val songArr = obj.optJSONArray("topSong")
                val songJsons = (0 until (songArr?.length() ?: 0)).mapNotNull { songArr?.optJSONObject(it) }
                val topSongs = NavidromeResponseParser.parseSongs(songJsons).map { it.toSong() }

                // The gateway already orders the discography newest-first; keep its order.
                val albumArr = obj.optJSONArray("album")
                val albums = (0 until (albumArr?.length() ?: 0))
                    .mapNotNull { albumArr?.optJSONObject(it) }
                    .mapNotNull { runCatching { NavidromeResponseParser.parseAlbum(it).toAppAlbum() }.getOrNull() }

                Result.success(
                    GatewayArtistDetail(
                        artist = artist,
                        topSongs = topSongs,
                        albums = albums,
                        description = obj.optString("description", "").takeIf { it.isNotBlank() },
                        subscribers = obj.optString("subscribers", "").takeIf { it.isNotBlank() }
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: getArtistDetail failed"); Result.failure(e)
            }
        }
    }

    /**
     * Fetches a gateway playlist live: its name plus its tracks. Needed because the DAO only holds
     * playlists that a sync has already pulled down — a playlist created seconds ago (a custom mix)
     * isn't there yet, and its songs are gateway ids the local song table has never seen.
     */
    suspend fun getGatewayPlaylist(playlistId: String): Result<Pair<String, List<Song>>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val (playlistJson, songJsons) = api.getPlaylist(playlistId).getOrThrow()
                val name = playlistJson.optString("name").ifBlank { "Playlist" }
                val songs = NavidromeResponseParser.parseSongs(songJsons).map { it.toSong() }
                Result.success(name to songs)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: getGatewayPlaylist failed"); Result.failure(e)
            }
        }
    }

    /** The gateway's genre names (real YouTube Music genres), or empty when unavailable. */
    suspend fun getGatewayGenres(): List<String> {
        if (!isLoggedIn) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val arr = api.getGenres().getOrThrow().optJSONArray("genre")
                (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val entry = arr?.opt(i)
                    // OpenSubsonic returns objects with the name in `value`; tolerate plain strings.
                    when (entry) {
                        is JSONObject -> entry.optString("value").takeIf { it.isNotBlank() }
                            ?: entry.optString("name").takeIf { it.isNotBlank() }
                        is String -> entry.takeIf { it.isNotBlank() }
                        else -> null
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: getGatewayGenres failed"); emptyList()
            }
        }
    }

    /** Tracks for a gateway genre, resolved live upstream. */
    suspend fun getGatewaySongsByGenre(genre: String, count: Int = 50): List<Song> {
        if (!isLoggedIn) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val arr = api.getSongsByGenre(genre, count).getOrThrow().optJSONArray("song")
                val jsons = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                NavidromeResponseParser.parseSongs(jsons).map { it.toSong() }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: getGatewaySongsByGenre failed"); emptyList()
            }
        }
    }

    /**
     * Blends [artistIds] into a playlist saved on the gateway, returning its id and track count.
     */
    suspend fun buildMix(name: String, artistIds: List<String>, count: Int = 40): Result<BuiltMix> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        if (artistIds.isEmpty()) return Result.failure(Exception("Pick at least one artist"))
        return withContext(Dispatchers.IO) {
            try {
                val obj = api.buildMix(name, artistIds, count).getOrThrow()
                // An empty id means the gateway resolved none of the artists.
                val playlistId = obj.optString("id").takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(
                        Exception("Couldn't find those artists upstream"))
                Result.success(
                    BuiltMix(
                        playlistId = playlistId,
                        name = obj.optString("name", name),
                        songCount = obj.optInt("songCount", 0)
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: buildMix failed"); Result.failure(e)
            }
        }
    }

    /** Album detail fetched live for a gateway `yt-album-…` id: the album + its tracks. */
    suspend fun getAlbumDetail(albumId: String): Result<Pair<Album, List<Song>>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val obj = api.getAlbumWithSongs(albumId).getOrThrow()
                val album = NavidromeResponseParser.parseAlbum(obj).toAppAlbum()
                val arr = obj.optJSONArray("song")
                val jsons = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                val songs = NavidromeResponseParser.parseSongs(jsons).map { it.toSong() }
                Result.success(album to songs)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: getAlbumDetail failed"); Result.failure(e)
            }
        }
    }

    // ── YouTube Music account linking ────────────────────────────────────────

    suspend fun ytmStatus(): YtmStatus {
        if (!isLoggedIn) return YtmStatus(linked = false, configured = false)
        return withContext(Dispatchers.IO) {
            val o = api.getYtmStatus().getOrNull()
            YtmStatus(
                linked = o?.optBoolean("linked", false) ?: false,
                configured = o?.optBoolean("configured", false) ?: false,
                accountName = o?.optString("accountName", "") ?: "",
                needsRelink = o?.optBoolean("needsRelink", false) ?: false
            )
        }
    }

    suspend fun ytmStartLink(): YtmLink {
        if (!isLoggedIn) return YtmLink("error")
        return withContext(Dispatchers.IO) {
            val o = api.startYtmLink().getOrNull() ?: return@withContext YtmLink("error")
            YtmLink(
                status = o.optString("status", "error"),
                userCode = o.optString("userCode", ""),
                verificationUrl = o.optString("verificationUrl", "https://google.com/device"),
                intervalSeconds = o.optInt("interval", 5).coerceAtLeast(2)
            )
        }
    }

    /** "linked" | "pending" | "none" | "unconfigured" | "error". */
    suspend fun ytmPollLink(): String {
        if (!isLoggedIn) return "error"
        return withContext(Dispatchers.IO) {
            api.pollYtmLink().getOrNull()?.optString("status", "pending") ?: "pending"
        }
    }

    /**
     * Submit cookies captured by the in-app sign-in. When the cookie jar holds more than one
     * signed-in Google account the server answers "choose" with the candidates, and the caller
     * must resubmit with the picked [authUser] index — otherwise we'd bind to the wrong account.
     */
    suspend fun ytmSetCookies(cookie: String, authUser: String? = null): YtmLinkResult {
        if (!isLoggedIn) return YtmLinkResult("error")
        return withContext(Dispatchers.IO) {
            val o = api.setYtmCookies(cookie, authUser).getOrNull()
                ?: return@withContext YtmLinkResult("error")
            val arr = o.optJSONArray("account")
            val accounts = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr?.optJSONObject(i)?.let {
                    YtmAccount(it.optString("index", "0"), it.optString("name", ""))
                }
            }
            YtmLinkResult(
                status = o.optString("status", "error"),
                accountName = o.optString("accountName", ""),
                accounts = accounts
            )
        }
    }

    suspend fun ytmUnlink(): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) { api.unlinkYtm().isSuccess }
    }

    // ── Taste onboarding ─────────────────────────────────────────────────────

    /** Starting pool of artists for the pairwise "who do you prefer?" onboarding. */
    suspend fun tasteStartArtists(): Result<List<Artist>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                Result.success(NavidromeResponseParser.parseArtists(
                    api.getTasteStart().getOrThrow()).map { it.toAppArtist() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: tasteStartArtists failed"); Result.failure(e)
            }
        }
    }

    /** Artists related to [artistId] — the next pair branches off the last pick. */
    suspend fun relatedArtists(artistId: String): Result<List<Artist>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                Result.success(NavidromeResponseParser.parseArtists(
                    api.getRelatedArtists(artistId).getOrThrow()).map { it.toAppArtist() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: relatedArtists failed"); Result.failure(e)
            }
        }
    }

    /** Persist the chosen taste-seed artists (by name) so the server can curate the home. */
    suspend fun setTasteSeeds(artistNames: List<String>): Result<Unit> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return api.setSeeds(artistNames)
    }

    /**
     * Fetch songs similar to [songId] straight from the server (radio / endless playback).
     */
    suspend fun getSimilarSongs(songId: String, count: Int = 20): Result<List<Song>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val jsonObjects = api.getSimilarSongs2(songId, count).getOrThrow()
                Result.success(NavidromeResponseParser.parseSongs(jsonObjects).map { it.toSong() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: getSimilarSongs failed")
                Result.failure(e)
            }
        }
    }

    /**
     * Fetch a playlist's songs live from the server. Used for server-curated home rows
     * (Your Mix, Discover, Top Charts, per-artist Radio…) that aren't cached in the local DB.
     */
    suspend fun fetchRemotePlaylistSongs(playlistId: String): Result<List<Song>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val (_, songJson) = api.getPlaylist(playlistId).getOrThrow()
                Result.success(NavidromeResponseParser.parseSongs(songJson).map { it.toSong() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: fetchRemotePlaylistSongs($playlistId) failed")
                Result.failure(e)
            }
        }
    }

    /**
     * Fetch all playlists live from the server (curated rows + user playlists), each as a
     * raw JSON object. The curated rows carry ids like "cur-mix"/"cur-charts" and radio ids.
     */
    suspend fun fetchRemotePlaylists(): Result<List<JSONObject>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) { api.getPlaylists() }
    }

    /**
     * Fetch albums of a given list type (e.g. "recent", "frequent", "newest") live.
     */
    suspend fun fetchRemoteAlbums(type: String, size: Int = 20): Result<List<JSONObject>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) { api.getAlbumList(type = type, size = size) }
    }

    /**
     * The server-curated home rows (Your Mix, Discover, Recently Played, Top Charts, and any
     * per-artist "… Radio"), as ordered (title -> songs) pairs. The gateway returns these as
     * `cur-`-prefixed playlists ahead of the user's own playlists; empty rows are dropped.
     */
    suspend fun fetchCuratedHomeRows(): List<Triple<String, String, List<Song>>> {
        if (!isLoggedIn) return emptyList()
        return withContext(Dispatchers.IO) {
            val playlists = api.getPlaylists().getOrNull().orEmpty()
            val rows = mutableListOf<Triple<String, String, List<Song>>>()
            for (pl in playlists) {
                val id = pl.optString("id")
                if (id.isBlank() || !id.startsWith("cur-")) continue // user playlists live in the Playlists tab
                val name = pl.optString("name").ifBlank { "Mix" }
                // De-dupe songs by id: a row may repeat a track, which would otherwise collide
                // on the LazyRow item key and crash the carousel.
                val songs = fetchRemotePlaylistSongs(id).getOrNull().orEmpty().distinctBy { it.id }
                if (songs.isNotEmpty()) rows.add(Triple(id, name, songs))
            }
            rows
        }
    }

    // ─── Media URLs ────────────────────────────────────────────────────────

    /**
     * Get the streaming URL for a song.
     *
     * @param songId The Navidrome song ID
     * @param maxBitRate Maximum bitrate (0 = no limit)
     * @return The streaming URL
     */
    fun getStreamUrl(songId: String, maxBitRate: Int = 0): String {
        return api.getStreamUrl(songId, maxBitRate)
    }

    /**
     * Get the cover art URL for a song/album/artist.
     *
     * @param coverArtId The cover art ID
     * @param size Desired size in pixels
     * @return The cover art URL
     */
    fun getCoverArtUrl(coverArtId: String?, size: Int = 500): String? {
        if (coverArtId.isNullOrBlank()) return null
        return api.getCoverArtUrl(coverArtId, size)
    }

    // ─── Lyrics ────────────────────────────────────────────────────────────

    /**
     * Get lyrics for a song.
     */
    suspend fun getLyrics(songId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // Try OpenSubsonic extension first
                var result = api.getLyricsBySongId(songId)
                if (result.isSuccess && !result.getOrNull().isNullOrBlank()) {
                    return@withContext result
                }

                // Fallback to standard lyrics API
                val songEntity = dao.getSongByNavidromeId(songId)
                if (songEntity != null) {
                    result = api.getLyrics(songEntity.artist, songEntity.title)
                    if (result.isSuccess && !result.getOrNull().isNullOrBlank()) {
                        return@withContext result
                    }
                }

                Result.failure(Exception("No lyrics found"))
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to get lyrics for song $songId")
                Result.failure(e)
            }
        }
    }

    // ─── Unified Library Sync ──────────────────────────────────────────────

    /**
     * Sync Navidrome songs to the unified music library.
     */
    suspend fun syncUnifiedLibrarySongsFromNavidrome() {
        val navidromeSongs = dao.getAllNavidromeSongsList()
        val existingUnifiedIds = musicDao.getAllNavidromeSongIds()

        if (navidromeSongs.isEmpty()) {
            if (existingUnifiedIds.isNotEmpty()) {
                musicDao.clearAllNavidromeSongs()
            }
            return
        }

        val songs = ArrayList<SongEntity>(navidromeSongs.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = mutableListOf<SongArtistCrossRef>()

        navidromeSongs.forEach { navidromeSong ->
            val songId = toUnifiedSongId(navidromeSong.navidromeId)
            // Prefer the gateway's per-credit identities. Splitting the display string can't
            // work: "Forrest Frank, Cory Asbury" and "Tyler, The Creator" are indistinguishable
            // as text, so a collaboration used to collapse into one fake artist in the library.
            val gatewayRefs = decodeArtistRefs(navidromeSong.artistRefs)
            val artistNames = if (gatewayRefs.isNotEmpty()) gatewayRefs.map { it.name }
                              else parseArtistNames(navidromeSong.artist)
            val primaryArtistName = artistNames.firstOrNull() ?: "Unknown Artist"
            val primaryArtistId = toUnifiedArtistId(primaryArtistName)

            artistNames.forEachIndexed { index, artistName ->
                // Key on the gateway's stable id when we have one: name-hashing splits one
                // artist across punctuation/casing variants and merges distinct same-name acts.
                val artistId = gatewayRefs.getOrNull(index)?.id?.takeIf { it.isNotEmpty() }
                    ?.let { toUnifiedArtistId(it) } ?: toUnifiedArtistId(artistName)
                artists.putIfAbsent(
                    artistId,
                    ArtistEntity(
                        id = artistId,
                        name = artistName,
                        trackCount = 0,
                        imageUrl = null
                    )
                )
                crossRefs.add(
                    SongArtistCrossRef(
                        songId = songId,
                        artistId = artistId,
                        isPrimary = index == 0
                    )
                )
            }

            val albumId = toUnifiedAlbumId(navidromeSong.albumId, navidromeSong.album)
            val albumName = navidromeSong.album.ifBlank { "Unknown Album" }
            albums.putIfAbsent(
                albumId,
                AlbumEntity(
                    id = albumId,
                    title = albumName,
                    artistName = primaryArtistName,
                    artistId = primaryArtistId,
                    songCount = 0,
                    dateAdded = navidromeSong.dateAdded,
                    year = navidromeSong.year,
                    albumArtUriString = navidromeSong.coverArtId?.takeIf { it.isNotBlank() }
                        ?.let { "navidrome_cover://$it" }
                )
            )

            songs.add(
                SongEntity(
                    id = songId,
                    title = navidromeSong.title,
                    artistName = navidromeSong.artist.ifBlank { primaryArtistName },
                    artistId = primaryArtistId,
                    albumArtist = null,
                    albumName = albumName,
                    albumId = albumId,
                    contentUriString = "navidrome://${navidromeSong.navidromeId}",
                    albumArtUriString = navidromeSong.coverArtId?.takeIf { it.isNotBlank() }
                        ?.let { "navidrome_cover://$it" },
                    duration = navidromeSong.duration,
                    genre = navidromeSong.genre ?: NAVIDROME_GENRE,
                    filePath = navidromeSong.path,
                    parentDirectoryPath = NAVIDROME_PARENT_DIRECTORY,
                    isFavorite = false,
                    lyrics = null,
                    trackNumber = navidromeSong.trackNumber,
                    year = navidromeSong.year,
                    dateAdded = navidromeSong.dateAdded.takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                    mimeType = navidromeSong.mimeType,
                    bitrate = navidromeSong.bitRate?.let { it * 1000 },
                    sampleRate = null,
                    telegramChatId = null,
                    telegramFileId = null,
                    sourceType = SourceType.NAVIDROME
                )
            )
        }

        val albumCounts = songs.groupingBy { it.albumId }.eachCount()
        val finalAlbums = albums.values.map { album ->
            album.copy(songCount = albumCounts[album.id] ?: 0)
        }

        val currentUnifiedIds = songs.map { it.id }.toSet()
        val deletedUnifiedIds = existingUnifiedIds.filter { it !in currentUnifiedIds }

        musicDao.incrementalSyncMusicData(
            songs = songs,
            albums = finalAlbums,
            artists = artists.values.toList(),
            crossRefs = crossRefs,
            deletedSongIds = deletedUnifiedIds
        )
    }

    // ─── Utility Methods ───────────────────────────────────────────────────

    private fun parseArtistNames(rawArtist: String): List<String> =
        CloudMusicUtils.parseArtistNames(rawArtist)

    private fun toUnifiedSongId(navidromeId: String): Long {
        return -(NAVIDROME_SONG_ID_OFFSET + navidromeId.hashCode().toLong().absoluteValue)
    }

    private fun toUnifiedAlbumId(albumId: String?, albumName: String): Long {
        val normalized = if (!albumId.isNullOrBlank()) {
            albumId.hashCode().toLong().absoluteValue
        } else {
            albumName.lowercase().hashCode().toLong().absoluteValue
        }
        return -(NAVIDROME_ALBUM_ID_OFFSET + normalized)
    }

    private fun toUnifiedArtistId(artistName: String): Long {
        return -(NAVIDROME_ARTIST_ID_OFFSET + artistName.lowercase().hashCode().toLong().absoluteValue)
    }

    // ─── App Playlist Management ───────────────────────────────────────────

    private fun getAppPlaylistIdForNavidrome(navidromePlaylistId: String): String {
        return "$NAVIDROME_PLAYLIST_PREFIX$navidromePlaylistId"
    }

    private suspend fun updateAppPlaylistForNavidromePlaylist(
        navidromePlaylistId: String,
        playlistName: String,
        navidromeEntities: List<NavidromeSongEntity>
    ) {
        try {
            val unifiedSongIds = navidromeEntities.map { entity ->
                toUnifiedSongId(entity.navidromeId).toString()
            }

            val appPlaylistId = getAppPlaylistIdForNavidrome(navidromePlaylistId)
            val allPlaylists = playlistPreferencesRepository.userPlaylistsFlow
            val existingPlaylist = withContext(Dispatchers.IO) {
                allPlaylists.map { playlists ->
                    playlists.find { it.id == appPlaylistId }
                }.first()
            }

            if (existingPlaylist != null) {
                playlistPreferencesRepository.updatePlaylist(
                    existingPlaylist.copy(
                        name = playlistName,
                        songIds = unifiedSongIds,
                        lastModified = System.currentTimeMillis(),
                        source = "NAVIDROME"
                    )
                )
                Timber.d("$TAG: Updated app playlist for Navidrome playlist $navidromePlaylistId")
            } else {
                playlistPreferencesRepository.createPlaylist(
                    name = playlistName,
                    songIds = unifiedSongIds,
                    customId = appPlaylistId,
                    source = "NAVIDROME"
                )
                Timber.d("$TAG: Created app playlist for Navidrome playlist $navidromePlaylistId")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to update app playlist for Navidrome playlist $navidromePlaylistId")
        }
    }

    private suspend fun deleteAppPlaylistForNavidromePlaylist(navidromePlaylistId: String) {
        try {
            val appPlaylistId = getAppPlaylistIdForNavidrome(navidromePlaylistId)
            playlistPreferencesRepository.deletePlaylist(appPlaylistId)
            Timber.d("$TAG: Deleted app playlist for Navidrome playlist $navidromePlaylistId")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to delete app playlist for Navidrome playlist $navidromePlaylistId")
        }
    }

    // ─── Playback Reporting ──────────────────────────────────────────────

    suspend fun reportPlayback(
        navidromeId: String,
        positionMs: Long,
        state: String,
        playbackRate: Float = 1.0f,
        ignoreScrobble: Boolean = false
    ): Result<Unit> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        val result = api.reportPlayback(
            mediaId = navidromeId,
            positionMs = positionMs,
            state = state,
            playbackRate = playbackRate,
            ignoreScrobble = ignoreScrobble
        )
        // Fallback to standard scrobble if reportPlayback is not supported.
        // PS: The latest release of Navidrome currently doesn't support the
        // standard OpenSubsonic API (reportPlayback) at the time of writing
        // See: (https://github.com/navidrome/navidrome/pull/5442), so this is required.
        if (result.isFailure && result.exceptionOrNull()?.message?.contains("404") == true) {
            if (state == "playing" || state == "starting") {
                return api.scrobble(id = navidromeId, submission = false)
            }
        }
        return result
    }

    suspend fun scrobble(navidromeId: String, submission: Boolean = true): Result<Unit> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return api.scrobble(id = navidromeId, submission = submission)
    }

    // ─── Delete ────────────────────────────────────────────────────────────

    suspend fun deletePlaylist(playlistId: String) {
        dao.deleteSongsByPlaylist(playlistId)
        dao.deletePlaylist(playlistId)
        deleteAppPlaylistForNavidromePlaylist(playlistId)
        syncUnifiedLibrarySongsFromNavidrome()
    }
}

/** Whether the signed-in gateway user has linked a YouTube Music account, and which one. */
data class YtmStatus(
    val linked: Boolean,
    val configured: Boolean,
    val accountName: String = "",
    val needsRelink: Boolean = false
)

/** A Google account reachable from the captured cookie jar. */
data class YtmAccount(val index: String, val name: String)

/** A playlist the gateway generated from a set of artists. */
data class BuiltMix(val playlistId: String, val name: String, val songCount: Int)

/**
 * A gateway artist page in one payload: popular tracks, the discography (already ordered
 * newest-first by the gateway) and the biography, so the screen needs a single round trip.
 */
data class GatewayArtistDetail(
    val artist: Artist,
    val topSongs: List<Song>,
    val albums: List<Album>,
    val description: String?,
    val subscribers: String?
)

/** Outcome of submitting cookies: "linked", "choose", "rejected", "incomplete", "error". */
data class YtmLinkResult(
    val status: String,
    val accountName: String = "",
    val accounts: List<YtmAccount> = emptyList()
)

/** A device-code linking attempt: show [userCode] and send the user to [verificationUrl]. */
data class YtmLink(
    val status: String,
    val userCode: String = "",
    val verificationUrl: String = "https://google.com/device",
    val intervalSeconds: Int = 5
)

// ─── Extension Functions ────────────────────────────────────────────────────

/**
 * Convert a gateway artist to the app [Artist], carrying its gateway id for navigation and
 * resolving its image through the `navidrome_cover://` Coil scheme.
 */
fun NavidromeArtist.toAppArtist(): Artist = Artist(
    id = id.hashCode().toLong(),
    name = name,
    songCount = albumCount,
    imageUrl = artistImageUrl ?: coverArt?.let { "navidrome_cover://$it" },
    navidromeId = id
)

/**
 * Convert a gateway album to the app [Album], carrying its gateway id for navigation.
 */
fun NavidromeAlbum.toAppAlbum(): Album = Album(
    id = id.hashCode().toLong(),
    title = name,
    artist = artist,
    year = year,
    dateAdded = 0L,
    albumArtUriString = coverArt?.let { "navidrome_cover://$it" },
    songCount = songCount,
    albumArtist = artist,
    navidromeId = id
)

/**
 * Convert a NavidromeSong to a Song model.
 */
fun NavidromeSong.toSong(): Song {
    return Song(
        id = "navidrome_$id",
        title = title,
        artist = artist,
        artistId = -1L,
        // The gateway's per-credit ids. artistId stays -1 (there is no local artist row), but
        // these give every credit a real, openable identity — including on songs the server has
        // never cached, which used to navigate nowhere.
        artists = artistRefs.mapIndexed { index, ref ->
            ArtistRef(id = -1L, name = ref.name, isPrimary = index == 0, gatewayId = ref.id)
        },
        album = album,
        albumId = -1L,
        path = path,
        contentUriString = "navidrome://$id",
        albumArtUriString = coverArt?.let { "navidrome_cover://$it" },
        duration = duration,
        genre = genre,
        mimeType = resolvedMimeType,
        bitrate = bitRate?.let { it * 1000 },
        sampleRate = null,
        year = year,
        trackNumber = trackNumber,
        dateAdded = System.currentTimeMillis(),
        isFavorite = false,
        navidromeId = id
    )
}
