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
import com.theveloper.pixelplay.data.network.navidrome.SubsonicApiException
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
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
        // PixelPlayer is a dedicated client for this one XPS Subsonic gateway — there is no
        // multi-server support, so the URL is baked in rather than asked of the user.
        const val GATEWAY_URL = "https://api.tinotenda.co"
        const val SYNC_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val TAG = "NavidromeRepo"
        private const val PREFS_NAME = "navidrome_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAST_FULL_SYNC = "last_full_sync"

        // ID offsets for unified library, kept in the same trillion-scale range
        // established when other cloud sources (since removed) used 3-8.
        // Using negative offsets to prevent collisions with MediaStore IDs
        private const val NAVIDROME_SONG_ID_OFFSET = 9_000_000_000_000L
        private const val NAVIDROME_ALBUM_ID_OFFSET = 10_000_000_000_000L
        private const val NAVIDROME_ARTIST_ID_OFFSET = 11_000_000_000_000L
        private const val NAVIDROME_PARENT_DIRECTORY = "/Cloud/Navidrome"
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

    /** Gateway ids of artists the signed-in user has liked. Empty until [refreshLikedArtists]
     *  runs — callers that need it (ArtistDetailScreen, the Liked tab) refresh on load. */
    private val _likedArtistIds = MutableStateFlow<Set<String>>(emptySet())
    val likedArtistIds: StateFlow<Set<String>> = _likedArtistIds.asStateFlow()

    /** Same data as [likedArtistIds], with the name/cover needed to render the Liked tab's
     *  "Liked Artists" row without a separate fetch per artist. */
    private val _likedArtists = MutableStateFlow<List<LikedArtistSummary>>(emptyList())
    val likedArtists: StateFlow<List<LikedArtistSummary>> = _likedArtists.asStateFlow()

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

    /** Songs, artists and albums for a query in a SINGLE gateway round-trip. */
    data class LiveSearchResults(
        val songs: List<Song>,
        val artists: List<Artist>,
        val albums: List<Album>,
    )

    /**
     * One search3 call that returns songs + artists + albums together, instead of the three
     * separate searchSongs/searchArtists/searchAlbums calls the UI used to fire in parallel.
     * search3 already returns all three arrays, so those three calls were three full YouTube
     * Music searches on the gateway for the same query — the reason search sat on "Searching
     * everywhere…" for several seconds. This is a single call.
     */
    suspend fun searchEverything(
        query: String,
        songLimit: Int = 40,
        artistLimit: Int = 20,
        albumLimit: Int = 20,
    ): Result<LiveSearchResults> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            // Retry once. The gateway does a LIVE YouTube Music search per call; under rapid or
            // concurrent searches an individual call occasionally errors or comes back empty even
            // though the query has results. One quick retry turns those "No results" flukes into
            // real results — this is the "sometimes it doesn't fetch anything" report.
            for (attempt in 0..1) {
                try {
                    val response = api.search3(
                        query,
                        artistCount = artistLimit,
                        albumCount = albumLimit,
                        songCount = songLimit,
                    ).getOrThrow()
                    val sr = response.optJSONObject("searchResult3")
                    fun arr(name: String): List<JSONObject> {
                        val a = sr?.optJSONArray(name) ?: return emptyList()
                        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    }
                    // Parse each type independently, and each item within it — one malformed
                    // artist/album must never blank the songs (or vice versa). The old three-call
                    // search got this isolation for free by being three separate try/catch calls;
                    // folding them into one call HAS to keep it, or a single bad item empties the
                    // whole search.
                    val songs = runCatching {
                        NavidromeResponseParser.parseSongs(arr("song"))
                            .mapNotNull { runCatching { it.toSong() }.getOrNull() }
                    }.getOrDefault(emptyList())
                    val artists = runCatching {
                        NavidromeResponseParser.parseArtists(arr("artist"))
                            .mapNotNull { runCatching { it.toAppArtist() }.getOrNull() }
                    }.getOrDefault(emptyList())
                    val albums = runCatching {
                        NavidromeResponseParser.parseAlbums(arr("album"))
                            .mapNotNull { runCatching { it.toAppAlbum() }.getOrNull() }
                    }.getOrDefault(emptyList())
                    val result = LiveSearchResults(songs, artists, albums)
                    // Accept a non-empty result immediately; retry once if the first attempt came
                    // back completely empty (likely a transient upstream hiccup, not a real miss).
                    if (attempt == 1 || songs.isNotEmpty() || artists.isNotEmpty() || albums.isNotEmpty()) {
                        return@withContext Result.success(result)
                    }
                } catch (e: Exception) {
                    lastError = e
                    Timber.e(e, "$TAG: searchEverything attempt $attempt failed")
                }
                kotlinx.coroutines.delay(600)
            }
            Result.failure(lastError ?: Exception("searchEverything returned no results"))
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
     * An artist's full discography, flattened to a song list — every album's tracks, not just
     * [getArtistDetail]'s bounded topSongs shelf. Powers shuffle/play-all on the artist page.
     */
    suspend fun getArtistAllSongs(artistId: String): Result<List<Song>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val songJsons = api.getArtistSongs(artistId).getOrThrow()
                Result.success(NavidromeResponseParser.parseSongs(songJsons).map { it.toSong() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: getArtistAllSongs failed"); Result.failure(e)
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

    /**
     * Creates a brand-new gateway playlist. Used by local-playlist push (a playlist created in
     * the app becomes a real `pl-` playlist on the gateway) and by curated-playlist forking
     * (editing a Mix snapshots it into a new one of these). Returns the new gateway id, or null.
     */
    suspend fun createGatewayPlaylist(name: String, navidromeSongIds: List<String>): String? {
        if (!isLoggedIn) return null
        return withContext(Dispatchers.IO) {
            try {
                api.createPlaylist(name = name, songIds = navidromeSongIds).getOrThrow()
                    .optString("id").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: createGatewayPlaylist failed"); null
            }
        }
    }

    /**
     * Full-replaces a gateway playlist's songs — the single mechanism behind every local-playlist
     * content edit (add/remove/reorder all collapse to "here's the new full list").
     */
    suspend fun replaceGatewayPlaylistSongs(gatewayPlaylistId: String, navidromeSongIds: List<String>): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            api.createPlaylist(playlistId = gatewayPlaylistId, songIds = navidromeSongIds)
                .onFailure { Timber.w(it, "$TAG: replaceGatewayPlaylistSongs failed for $gatewayPlaylistId") }
                .isSuccess
        }
    }

    suspend fun renameGatewayPlaylist(gatewayPlaylistId: String, name: String): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            api.updatePlaylist(gatewayPlaylistId, name)
                .onFailure { Timber.w(it, "$TAG: renameGatewayPlaylist failed for $gatewayPlaylistId") }
                .isSuccess
        }
    }

    suspend fun deleteGatewayPlaylist(gatewayPlaylistId: String): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            api.deletePlaylist(gatewayPlaylistId)
                .onFailure { Timber.w(it, "$TAG: deleteGatewayPlaylist failed for $gatewayPlaylistId") }
                .isSuccess
        }
    }

    /**
     * Pushes a linked YouTube Music playlist's full desired song list — the gateway diffs it
     * against the playlist's live contents on the real account and pushes only the delta.
     */
    suspend fun replaceGatewayYtmPlaylistSongs(ytmPlaylistId: String, navidromeSongIds: List<String>): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            api.updateYtmPlaylist(ytmPlaylistId, songIds = navidromeSongIds)
                .onFailure { Timber.w(it, "$TAG: replaceGatewayYtmPlaylistSongs failed for $ytmPlaylistId") }
                .isSuccess
        }
    }

    suspend fun renameGatewayYtmPlaylist(ytmPlaylistId: String, name: String): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            api.updateYtmPlaylist(ytmPlaylistId, name = name)
                .onFailure { Timber.w(it, "$TAG: renameGatewayYtmPlaylist failed for $ytmPlaylistId") }
                .isSuccess
        }
    }

    /**
     * Which gateway id-space [playlistId] belongs to, so callers (chiefly [PlaylistViewModel])
     * can branch on playlist type without hardcoding the prefix scheme themselves.
     */
    fun gatewayPlaylistClassOf(playlistId: String): GatewayPlaylistClass = when {
        playlistId.startsWith("pl-") -> GatewayPlaylistClass.LOCAL_GATEWAY
        playlistId.startsWith("cur-ytm-") -> GatewayPlaylistClass.CURATED
        playlistId.startsWith("ytmpl-") -> GatewayPlaylistClass.LINKED_YTM
        else -> GatewayPlaylistClass.NOT_GATEWAY
    }

    /**
     * Mirrors a favorite/unfavorite onto the gateway (star/unstar) so it round-trips across
     * devices and survives a library sync, instead of living only in this device's local
     * favorites table like it did before.
     */
    suspend fun setSongFavoriteOnGateway(navidromeSongId: String, isFavorite: Boolean): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            val result = if (isFavorite) api.star(id = navidromeSongId) else api.unstar(id = navidromeSongId)
            result.onFailure { Timber.w(it, "$TAG: setSongFavoriteOnGateway failed for $navidromeSongId") }
            result.isSuccess
        }
    }

    /**
     * Refreshes [likedArtistIds] and [likedArtists] from the gateway. Cheap and safe to call
     * repeatedly (e.g. from every screen that needs it on load) — it's just a starred2 fetch.
     */
    suspend fun refreshLikedArtists() {
        if (!isLoggedIn) {
            _likedArtistIds.value = emptySet()
            _likedArtists.value = emptyList()
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val artists = api.getStarred2().getOrThrow()
                    .optJSONObject("starred2")?.optJSONArray("artist")
                val summaries = (0 until (artists?.length() ?: 0)).mapNotNull { i ->
                    val a = artists?.optJSONObject(i) ?: return@mapNotNull null
                    val id = a.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    LikedArtistSummary(
                        id = id,
                        name = a.optString("name"),
                        coverArt = a.optString("coverArt").takeIf { it.isNotBlank() }
                    )
                }
                _likedArtistIds.value = summaries.map { it.id }.toSet()
                _likedArtists.value = summaries
            } catch (e: Exception) {
                Timber.e(e, "$TAG: refreshLikedArtists failed")
            }
        }
    }

    /** Likes/unlikes an artist on the gateway and updates [likedArtistIds] on success. */
    suspend fun setArtistFavoriteStatus(navidromeArtistId: String, isFavorite: Boolean): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            val result = if (isFavorite) {
                api.star(artistId = navidromeArtistId)
            } else {
                api.unstar(artistId = navidromeArtistId)
            }
            val success = result
                .onFailure { Timber.w(it, "$TAG: setArtistFavoriteStatus failed for $navidromeArtistId") }
                .isSuccess
            if (success) {
                _likedArtistIds.update { current ->
                    if (isFavorite) current + navidromeArtistId else current - navidromeArtistId
                }
            }
            success
        }
    }

    /** How many gateway songs are cached locally. Zero means the library needs a re-sync. */
    suspend fun cachedSongCount(): Int = withContext(Dispatchers.IO) {
        runCatching { dao.countNavidromeSongs() }.getOrDefault(0)
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

    /**
     * Blends [artistIds] into a queue without saving anything — used for liked-artist radio,
     * where a playlist getting left behind on every play would be unwanted clutter.
     */
    suspend fun buildEphemeralMix(
        name: String,
        artistIds: List<String>,
        count: Int = 40
    ): Result<List<Song>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        if (artistIds.isEmpty()) return Result.failure(Exception("Pick at least one artist"))
        return withContext(Dispatchers.IO) {
            try {
                val obj = api.buildMix(name, artistIds, count, save = false).getOrThrow()
                val arr = obj.optJSONArray("entry")
                val jsons = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                val songs = NavidromeResponseParser.parseSongs(jsons).map { it.toSong() }
                if (songs.isEmpty()) {
                    Result.failure(Exception("Couldn't find those artists upstream"))
                } else {
                    Result.success(songs)
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: buildEphemeralMix failed"); Result.failure(e)
            }
        }
    }

    /**
     * An ephemeral queue blending the whole taste signal (liked artists, top artists, onboarding
     * seeds) server-side — never persisted. Powers the endless "Surprise Me" queue: called once
     * to start it, then again every time it needs to grow, so it stays anchored to taste instead
     * of drifting via a single track's radio graph.
     */
    suspend fun buildSurpriseMeQueue(): Result<List<Song>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val obj = api.getSurpriseMe().getOrThrow()
                val arr = obj.optJSONArray("entry")
                val jsons = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                val songs = NavidromeResponseParser.parseSongs(jsons).map { it.toSong() }
                if (songs.isEmpty()) {
                    Result.failure(Exception("Nothing to blend yet"))
                } else {
                    Result.success(songs)
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: buildSurpriseMeQueue failed"); Result.failure(e)
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

    // ── Spotify import ────────────────────────────────────────────────────────

    suspend fun spotifyStatus(): SpotifyStatus {
        if (!isLoggedIn) return SpotifyStatus(linked = false, configured = false)
        return withContext(Dispatchers.IO) {
            val o = api.getSpotifyStatus().getOrNull()
            SpotifyStatus(
                linked = o?.optBoolean("linked", false) ?: false,
                configured = o?.optBoolean("configured", false) ?: false,
                accountName = o?.optString("accountName", "") ?: "",
                progress = o?.optJSONObject("import")?.let { parseSpotifyProgress(it) }
            )
        }
    }

    /** Begin linking; returns the Spotify consent URL to open in a browser. */
    suspend fun spotifyStartLink(): SpotifyLink {
        if (!isLoggedIn) return SpotifyLink("error")
        return withContext(Dispatchers.IO) {
            val o = api.startSpotifyLink().getOrNull() ?: return@withContext SpotifyLink("error")
            SpotifyLink(status = o.optString("status", "error"), authUrl = o.optString("authUrl", ""))
        }
    }

    /** "linked" | "none" | "unconfigured". */
    suspend fun spotifyPollLink(): String {
        if (!isLoggedIn) return "none"
        return withContext(Dispatchers.IO) {
            api.pollSpotifyLink().getOrNull()?.optString("status", "none") ?: "none"
        }
    }

    suspend fun spotifyUnlink(): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) { api.unlinkSpotify().isSuccess }
    }

    /** Fetch the user's playlists + counts so the app can offer a selection. */
    suspend fun spotifyPreview(): SpotifyPreview? {
        if (!isLoggedIn) return null
        return withContext(Dispatchers.IO) {
            val o = api.spotifyPreview().getOrNull() ?: return@withContext null
            if (o.optString("status") != "ok") return@withContext null
            val arr = o.optJSONArray("playlists")
            val playlists = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr?.optJSONObject(i)?.let {
                    SpotifyPlaylistOption(
                        id = it.optString("id"),
                        name = it.optString("name", "Playlist"),
                        count = it.optInt("count", 0)
                    )
                }
            }
            SpotifyPreview(
                playlists = playlists,
                playlistsAvailable = o.optBoolean("playlistsAvailable", false),
                likedCount = o.optInt("likedCount", 0),
                topArtistsCount = o.optInt("topArtistsCount", 0)
            )
        }
    }

    suspend fun spotifyStartImport(options: SpotifyImportOptions? = null): SpotifyImportProgress? {
        if (!isLoggedIn) return null
        return withContext(Dispatchers.IO) {
            val params = options?.let {
                val playlistsParam = when {
                    it.playlistIds == null -> "all"
                    else -> it.playlistIds.joinToString(",")
                }
                mapOf(
                    "playlists" to playlistsParam,
                    "liked" to it.liked.toString(),
                    "artists" to it.artists.toString(),
                    "history" to it.history.toString()
                )
            } ?: emptyMap()
            api.startSpotifyImport(params).getOrNull()?.optJSONObject("import")
                ?.let { parseSpotifyProgress(it) }
        }
    }

    suspend fun spotifyImportStatus(): SpotifyImportProgress? {
        if (!isLoggedIn) return null
        return withContext(Dispatchers.IO) {
            api.spotifyImportStatus().getOrNull()?.optJSONObject("import")
                ?.let { parseSpotifyProgress(it) }
        }
    }

    private fun parseSpotifyProgress(o: org.json.JSONObject): SpotifyImportProgress =
        SpotifyImportProgress(
            state = o.optString("state", "idle"),
            phase = o.optString("phase", ""),
            total = o.optInt("total", 0),
            done = o.optInt("done", 0),
            matched = o.optInt("matched", 0),
            unmatched = o.optInt("unmatched", 0),
            playlists = o.optInt("playlists", 0),
            message = o.optString("message", "")
        )

    // ── Cross-device playback: one canonical session per user, pushed over SSE ─────────────
    // Replaces the old poll-everything design: instead of N devices each independently
    // reporting their own state, there's exactly one canonical "what's playing" per account.
    // A device publishes itself active (publishState); everyone interested - this account's
    // other devices, and (if visible) household members watching Jam - hears about it
    // instantly over subscribeSession's push stream, not by polling.

    suspend fun registerDevice(
        deviceName: String, platform: String, sessionId: String, householdVisible: Boolean
    ): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            api.registerDevice(deviceName, platform, sessionId, householdVisible).isSuccess
        }
    }

    /** Publishes this device's playback state, making it the account's one active device. Any
     *  device that was previously active gets pushed a `superseded` command over its own
     *  subscribeSession stream - a real takeover, not just a state overwrite. */
    suspend fun publishState(
        sessionId: String, state: JamState, queueIds: List<String>, queueIndex: Int
    ): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            val params = mapOf(
                "sessionId" to sessionId,
                "songId" to state.songId, "title" to state.title, "artist" to state.artist,
                "album" to state.album, "coverArt" to state.coverArt,
                "positionMs" to state.positionMs.toString(),
                "durationMs" to state.durationMs.toString(),
                "isPlaying" to state.isPlaying.toString(),
                "queueIndex" to queueIndex.toString(),
                "queue" to queueIds.joinToString(",")
            )
            api.publishState(params).isSuccess
        }
    }

    /** This account's other registered devices - the pick-list for transfer, playing or not. */
    suspend fun getDevices(sessionId: String): List<DeviceSession> {
        if (!isLoggedIn) return emptyList()
        return withContext(Dispatchers.IO) {
            val o = api.getDevices(sessionId).getOrNull() ?: return@withContext emptyList()
            val arr = o.optJSONArray("device")
            (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr?.optJSONObject(i)?.let(::parseDeviceSession) }
        }
    }

    /** This account's one canonical active session, or null if nothing is playing anywhere. */
    suspend fun getMySession(): ActiveSession? {
        if (!isLoggedIn) return null
        return withContext(Dispatchers.IO) {
            val o = api.getSession().getOrNull() ?: return@withContext null
            if (o.optString("activeDeviceId").isBlank()) null else parseActiveSession(o)
        }
    }

    /** Every other household member's active, visible session - the auto-discovered Jam list. */
    suspend fun getHouseholdSessions(): List<ActiveSession> {
        if (!isLoggedIn) return emptyList()
        return withContext(Dispatchers.IO) {
            val o = api.getHouseholdSessions().getOrNull() ?: return@withContext emptyList()
            val arr = o.optJSONArray("session")
            (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr?.optJSONObject(i)?.let(::parseActiveSession) }
        }
    }

    /** Sends a command, targeting it one of two ways: targetSessionId names a specific one of
     *  the caller's own devices (possibly idle - transfer/cast); targetUser resolves to
     *  "whichever device is currently active for this user" (ordinary remote control -
     *  yourself, or another household member via Jam, gated by their household_visible flag
     *  server-side). */
    suspend fun sendCommand(
        action: String, positionMs: Long? = null, volume: Float? = null,
        songIds: List<String> = emptyList(), targetUser: String? = null,
        targetSessionId: String? = null
    ): Boolean {
        if (!isLoggedIn) return false
        return withContext(Dispatchers.IO) {
            val params = buildMap {
                put("action", action)
                positionMs?.let { put("positionMs", it.toString()) }
                volume?.let { put("volume", it.toString()) }
                if (songIds.isNotEmpty()) put("songIds", songIds.joinToString(","))
                targetUser?.let { put("targetUser", it) }
                targetSessionId?.let { put("targetSessionId", it) }
            }
            api.sendCommand(params).getOrNull()?.optBoolean("accepted", false) ?: false
        }
    }

    /** Opens this device's live push channel: session changes (this account's own, and
     *  household members' if visible) and commands sent to it. Returns the EventSource handle
     *  - OkHttp does not auto-reconnect, unlike a browser's EventSource, so the caller owns
     *  noticing [onClosed] and reconnecting with backoff. */
    fun subscribeSession(
        sessionId: String,
        onSession: (ActiveSession) -> Unit,
        onCommand: (JamCommand) -> Unit,
        onClosed: () -> Unit,
    ): EventSource {
        return api.subscribeSession(sessionId, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val json = org.json.JSONObject(data)
                    when (type) {
                        "session" -> onSession(parseActiveSession(json))
                        "command" -> onCommand(parseJamCommand(json))
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: failed to parse subscribeSession event type=$type")
                }
            }

            override fun onClosed(eventSource: EventSource) = onClosed()

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                Timber.w(t, "$TAG: subscribeSession connection failed")
                onClosed()
            }
        })
    }

    private fun parseJamCommand(c: org.json.JSONObject): JamCommand {
        val payload = c.optJSONObject("payload")
        val ids = payload?.optJSONArray("songIds")
        return JamCommand(
            action = c.optString("action"),
            positionMs = payload?.optLong("positionMs")?.takeIf { payload.has("positionMs") },
            volume = payload?.optDouble("volume")?.takeIf { payload.has("volume") }?.toFloat(),
            songIds = (0 until (ids?.length() ?: 0)).mapNotNull { j -> ids?.optString(j) }
        )
    }

    private fun parseDeviceSession(o: org.json.JSONObject): DeviceSession = DeviceSession(
        id = o.optString("id"),
        deviceName = o.optString("deviceName", "Device"),
        platform = o.optString("platform", ""),
        lastSeen = o.optLong("lastSeen")
    )

    private fun parseActiveSession(o: org.json.JSONObject): ActiveSession {
        val s = o.optJSONObject("state") ?: org.json.JSONObject()
        val queueArr = s.optJSONArray("queue")
        return ActiveSession(
            user = o.optString("user"),
            activeDeviceId = o.optString("activeDeviceId"),
            deviceName = o.optString("deviceName", "Device"),
            platform = o.optString("platform", ""),
            state = PlayerSessionState(
                songId = s.optString("songId"), title = s.optString("title"),
                artist = s.optString("artist"), album = s.optString("album"),
                coverArt = s.optString("coverArt"),
                positionMs = s.optLong("positionMs"), durationMs = s.optLong("durationMs"),
                isPlaying = s.optBoolean("isPlaying"), queueIndex = s.optInt("queueIndex"),
                queue = (0 until (queueArr?.length() ?: 0)).mapNotNull { i -> queueArr?.optString(i) }
            ),
            updatedAt = o.optLong("updatedAt")
        )
    }

    /**
     * Resolves song ids back into full [Song]s via the network, regardless of whether this
     * device has ever cached them locally — needed to rebuild a queue handed off (or Jammed)
     * from another device. Matches music-pwa's `songsByIds`.
     */
    suspend fun getSongsByIds(ids: List<String>): List<Song> {
        if (!isLoggedIn || ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val bySongId = coroutineScope {
                ids.map { id -> async { id to api.getSong(id).getOrNull() } }.awaitAll()
            }.toMap()
            // Preserve caller order and drop any id the server couldn't resolve.
            ids.mapNotNull { id ->
                bySongId[id]?.takeIf { it.has("id") }
                    ?.let { NavidromeResponseParser.parseSong(it).toSong() }
            }
        }
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
    /**
     * Raw cached library-song count. Lets the sync tell "empty because we never synced / it was
     * reset" from "recently synced" — so an empty cache always forces a server fetch instead of
     * being treated as a valid up-to-date empty library.
     */
    suspend fun cachedLibrarySongCount(): Int =
        withContext(Dispatchers.IO) { dao.countNavidromeSongs() }

    suspend fun syncUnifiedLibrarySongsFromNavidrome() {
        val navidromeSongs = dao.getAllNavidromeSongsList()
        val existingUnifiedIds = musicDao.getAllNavidromeSongIds()

        if (navidromeSongs.isEmpty()) {
            // Do NOT wipe the unified library just because the raw cache is empty. An empty cache
            // means "not fetched yet" or "a fetch failed" — NOT "the user's library is empty".
            // Wiping here turned one transient sync failure (or a DB-migration cache reset) into a
            // blank Library and home screen that stayed blank until the next full server sync (and
            // the 24h sync-threshold could keep it blank for a day). Leave what's there; a
            // successful server fetch repopulates the raw cache and this runs again with data.
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
            // MUST derive the album's artist id exactly like the index-0 artist row below (gateway
            // id first, name-hash fallback). Deriving it from the NAME here while the artist row
            // was inserted under its GATEWAY id meant the album referenced an artist that didn't
            // exist → a FOREIGN KEY constraint failure in insertAlbums that aborted the ENTIRE
            // library sync, leaving the Library and home screen blank.
            val primaryArtistId = gatewayRefs.firstOrNull()?.id?.takeIf { it.isNotEmpty() }
                ?.let { toUnifiedArtistId(it) } ?: toUnifiedArtistId(primaryArtistName)

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
                    genre = navidromeSong.genre,
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

    // internal (not private): PlaybackStatsRepository reuses this exact mapping to translate
    // gateway song ids coming back from getListeningEvents into the app's unified Song.id.
    internal fun toUnifiedSongId(navidromeId: String): Long {
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
        // Fallback to standard scrobble if reportPlayback isn't implemented server-side
        // (Subsonic error code 90, "unknown method" — see subsonic.py's dispatch fallback).
        val isUnknownMethod = (result.exceptionOrNull() as? SubsonicApiException)?.code == 90
        if (result.isFailure && isUnknownMethod) {
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

    // ─── Listening Events (Stats) ────────────────────────────────────────

    suspend fun reportListeningEvent(
        eventId: String,
        songId: String,
        title: String,
        artist: String,
        album: String,
        cover: String,
        durationMs: Long,
        startTime: Long,
        endTime: Long
    ): Result<JSONObject> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return api.reportListeningEvent(
            eventId, songId, title, artist, album, cover, durationMs, startTime, endTime
        )
    }

    suspend fun getListeningEvents(
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int = 5000
    ): Result<JSONObject> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return api.getListeningEvents(startTime, endTime, limit)
    }

    suspend fun importListeningEvents(events: List<JSONObject>): Result<JSONObject> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return api.importListeningEvents(events)
    }

    // ─── Delete ────────────────────────────────────────────────────────────

    suspend fun deletePlaylist(playlistId: String) {
        dao.deleteSongsByPlaylist(playlistId)
        dao.deletePlaylist(playlistId)
        deleteAppPlaylistForNavidromePlaylist(playlistId)
        syncUnifiedLibrarySongsFromNavidrome()
        if (isLoggedIn) {
            api.deletePlaylist(playlistId)
                .onFailure { Timber.w(it, "$TAG: deletePlaylist gateway call failed for $playlistId") }
        }
    }
}

/** Which gateway id-space a playlist id belongs to (see [NavidromeRepository.gatewayPlaylistClassOf]). */
enum class GatewayPlaylistClass { LOCAL_GATEWAY, CURATED, LINKED_YTM, NOT_GATEWAY }

/** Enough to render a liked-artist chip without a separate per-artist fetch. */
data class LikedArtistSummary(val id: String, val name: String, val coverArt: String?)

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

/** This device's local playback state, as published to the server. */
data class JamState(
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverArt: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false
)

/** A command pushed to this device over its live subscribeSession connection - remote control,
 *  or (when songIds is non-empty) a transfer/cast handing it a whole new queue to play. */
data class JamCommand(
    val action: String,
    val positionMs: Long? = null,
    val volume: Float? = null,
    val songIds: List<String> = emptyList()
)

/** One account's canonical active session — the "who/what/where" a device publishing itself
 *  active produces, and every observer (own devices, Jam) receives. */
data class PlayerSessionState(
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverArt: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val queueIndex: Int = 0,
    val queue: List<String> = emptyList()
)

data class ActiveSession(
    val user: String,
    val activeDeviceId: String,
    val deviceName: String,
    val platform: String,
    val state: PlayerSessionState,
    val updatedAt: Long
)

/** One of this account's registered devices — identity and reachability only. Playback state
 *  lives on the account's one canonical ActiveSession instead, not per device. */
data class DeviceSession(
    val id: String,
    val deviceName: String,
    val platform: String,
    val lastSeen: Long
)

/** Spotify link + import status for the current user. */
data class SpotifyStatus(
    val linked: Boolean,
    val configured: Boolean,
    val accountName: String = "",
    val progress: SpotifyImportProgress? = null
)

/** Begin-linking result: open [authUrl] in a browser. status = "pending"|"unconfigured"|"error". */
data class SpotifyLink(
    val status: String,
    val authUrl: String = ""
)

/** One of the user's Spotify playlists, offered for selection. */
data class SpotifyPlaylistOption(val id: String, val name: String, val count: Int)

/** What's available to import, for the Tidal-style selection screen. */
data class SpotifyPreview(
    val playlists: List<SpotifyPlaylistOption>,
    /** False when Spotify is blocking playlist track access for this app (dev-mode/quota). */
    val playlistsAvailable: Boolean,
    val likedCount: Int,
    val topArtistsCount: Int
)

/** The user's selection. playlistIds = null means "all playlists"; empty list means none. */
data class SpotifyImportOptions(
    val playlistIds: List<String>?,
    val liked: Boolean,
    val artists: Boolean,
    val history: Boolean
)

/** Live progress of a background Spotify import. state = idle|running|done|error. */
data class SpotifyImportProgress(
    val state: String,
    val phase: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val matched: Int = 0,
    val unmatched: Int = 0,
    val playlists: Int = 0,
    val message: String = ""
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
        isExplicit = explicit,
        navidromeId = id
    )
}
