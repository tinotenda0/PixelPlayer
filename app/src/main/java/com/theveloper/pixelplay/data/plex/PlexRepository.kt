@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.plex

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.PlexDao
import com.theveloper.pixelplay.data.database.PlexPlaylistEntity
import com.theveloper.pixelplay.data.database.PlexSongEntity
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.database.toEntity
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.plex.PlexApiService
import com.theveloper.pixelplay.data.network.plex.PlexCompanionClient
import com.theveloper.pixelplay.data.network.plex.PlexResponseParser
import com.theveloper.pixelplay.data.plex.model.PlexAccount
import com.theveloper.pixelplay.data.plex.model.PlexCredentials
import com.theveloper.pixelplay.data.plex.model.PlexPlayerDevice
import com.theveloper.pixelplay.data.plex.model.PlexRemoteTimeline
import com.theveloper.pixelplay.data.plex.model.PlexSong
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.stream.BulkSyncResult
import com.theveloper.pixelplay.data.stream.CloudMusicUtils
import com.theveloper.pixelplay.data.worker.PlexSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Suppress("DEPRECATION")
@Singleton
class PlexRepository @Inject constructor(
    private val api: PlexApiService,
    private val companionClient: PlexCompanionClient,
    private val dao: PlexDao,
    private val musicDao: MusicDao,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    @ApplicationContext private val context: Context
) {
    private companion object {
        private const val TAG = "PlexRepo"
        private const val PREFS_NAME = "plex_prefs"
        // Legacy single-account keys, migrated to the account list on first run.
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id"

        private const val PLEX_SONG_ID_OFFSET = 15_000_000_000_000L
        private const val PLEX_ALBUM_ID_OFFSET = 16_000_000_000_000L
        private const val PLEX_ARTIST_ID_OFFSET = 17_000_000_000_000L
        private const val PLEX_PARENT_DIRECTORY = "/Cloud/Plex"
        private const val PLEX_GENRE = "Plex"
        private const val PLEX_PLAYLIST_PREFIX = "plex_playlist:"
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

    private val _accountsFlow = MutableStateFlow<List<PlexAccount>>(emptyList())
    val accountsFlow: StateFlow<List<PlexAccount>> = _accountsFlow.asStateFlow()

    private val _activeAccountFlow = MutableStateFlow<PlexAccount?>(null)
    val activeAccountFlow: StateFlow<PlexAccount?> = _activeAccountFlow.asStateFlow()

    init {
        migrateLegacySingleAccountIfNeeded()
        _accountsFlow.value = loadAccounts()
        restoreActiveAccount()
    }

    // ─── Accounts ─────────────────────────────────────────────────────────

    /** Wrap the pre-multi-account credential keys into an account entry once. */
    private fun migrateLegacySingleAccountIfNeeded() {
        val legacyToken = prefs.getString(KEY_AUTH_TOKEN, null) ?: return
        val legacyServer = prefs.getString(KEY_SERVER_URL, null)
        val legacyUsername = prefs.getString(KEY_USERNAME, null)
        val legacyUserId = prefs.getString(KEY_USER_ID, null)

        if (!legacyServer.isNullOrBlank() && !legacyUsername.isNullOrBlank()) {
            val account = PlexAccount(
                id = legacyUserId?.takeIf { it.isNotBlank() } ?: legacyUsername,
                username = legacyUsername,
                plexTvToken = legacyToken,
                serverToken = legacyToken,
                serverUrl = legacyServer
            )
            persistAccounts(listOf(account))
            prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, account.id).apply()
            Timber.d("$TAG: Migrated legacy credentials to account list")
        }
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_ID)
            .apply()
    }

    private fun loadAccounts(): List<PlexAccount> {
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                PlexAccount(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    username = obj.optString("username", "Plex user"),
                    plexTvToken = obj.optString("plexTvToken").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    serverToken = obj.optString("serverToken").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    serverUrl = obj.optString("serverUrl").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    serverName = obj.optString("serverName").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to parse stored accounts")
            emptyList()
        }
    }

    private fun persistAccounts(accounts: List<PlexAccount>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject().apply {
                    put("id", account.id)
                    put("username", account.username)
                    put("plexTvToken", account.plexTvToken)
                    put("serverToken", account.serverToken)
                    put("serverUrl", account.serverUrl)
                    account.serverName?.let { put("serverName", it) }
                }
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
        _accountsFlow.value = accounts
    }

    private fun restoreActiveAccount() {
        val activeId = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, null) ?: return
        val account = _accountsFlow.value.firstOrNull { it.id == activeId } ?: return
        if (activateAccountInternal(account)) {
            Timber.d("$TAG: Restored account ${account.username}@${account.serverUrl}")
        }
    }

    /** Points the API client at [account]. Returns false for invalid server URLs. */
    private fun activateAccountInternal(account: PlexAccount): Boolean {
        val credentials = PlexCredentials(
            serverUrl = account.serverUrl,
            username = account.username,
            password = "",
            authToken = account.serverToken,
            userId = account.id
        )
        val validationError = credentials.connectionValidationError()
        if (validationError != null) {
            Timber.w("$TAG: Ignoring account with invalid server URL: $validationError")
            return false
        }
        api.setCredentials(credentials)
        prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, account.id).apply()
        _activeAccountFlow.value = account
        _isLoggedInFlow.value = true
        schedulePeriodicSync()
        return true
    }

    /**
     * Adds (or updates) an account and makes it active. Switching identities
     * clears the local mirror; the caller kicks off a fresh sync.
     */
    suspend fun addAccountAndActivate(account: PlexAccount): Result<String> {
        val credentials = PlexCredentials(
            serverUrl = account.serverUrl,
            username = account.username,
            password = "",
            authToken = account.serverToken,
            userId = account.id
        )
        credentials.connectionValidationError()?.let {
            return Result.failure(IllegalArgumentException(it))
        }

        val previousActive = _activeAccountFlow.value
        if (previousActive != null && previousActive.id != account.id) {
            clearLocalMirror()
        }

        val updated = _accountsFlow.value.filterNot { it.id == account.id } + account
        persistAccounts(updated)
        activateAccountInternal(account)

        WorkManager.getInstance(context).enqueueUniqueWork(
            PlexSyncWorker.ONE_TIME_WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            PlexSyncWorker.oneTimeWork()
        )
        return Result.success(account.username)
    }

    suspend fun switchAccount(accountId: String): Result<String> {
        val account = _accountsFlow.value.firstOrNull { it.id == accountId }
            ?: return Result.failure(Exception("Account not found"))
        if (_activeAccountFlow.value?.id == accountId) return Result.success(account.username)
        return addAccountAndActivate(account)
    }

    suspend fun removeAccount(accountId: String) {
        val remaining = _accountsFlow.value.filterNot { it.id == accountId }
        persistAccounts(remaining)

        if (_activeAccountFlow.value?.id == accountId) {
            clearLocalMirror()
            api.clearCredentials()
            _activeAccountFlow.value = null
            _isLoggedInFlow.value = false
            prefs.edit().remove(KEY_ACTIVE_ACCOUNT_ID).apply()
            cancelPeriodicSync()

            remaining.firstOrNull()?.let { next ->
                addAccountAndActivate(next)
            }
        }
    }

    /** Removes every synced trace of the current account from local storage. */
    private suspend fun clearLocalMirror() {
        dao.getAllPlaylistsList().forEach { playlist ->
            deleteAppPlaylistForPlexPlaylist(playlist.id)
        }
        dao.clearAllSongs()
        dao.clearAllPlaylists()
        musicDao.clearAllPlexSongs()
    }

    // ─── Authentication ──────────────────────────────────────────────────

    /**
     * Keeps the local Plex mirror fresh with a daily background sync while
     * logged in. UPDATE re-applies the current spec (cadence/constraints) to
     * any previously scheduled work without resetting its next-run timing.
     */
    private fun schedulePeriodicSync() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PlexSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PlexSyncWorker.periodicWork()
        )
    }

    private fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(PlexSyncWorker.PERIODIC_WORK_NAME)
    }

    val isLoggedIn: Boolean
        get() = _isLoggedInFlow.value

    val serverUrl: String?
        get() = _activeAccountFlow.value?.serverUrl

    val username: String?
        get() = _activeAccountFlow.value?.username

    fun getAuthToken(): String? = api.getAuthToken()

    /**
     * Manual sign-in with server URL + plex.tv username/password.
     * Kept as the "advanced" alternative to the web auth flow.
     */
    suspend fun login(serverUrl: String, username: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("$TAG: Attempting manual login to $serverUrl as $username")

                val credentials = PlexCredentials(serverUrl, username, password)
                val validationError = credentials.connectionValidationError()
                if (validationError != null) {
                    return@withContext Result.failure(IllegalArgumentException(validationError))
                }

                // Authenticate against plex.tv and get the account token
                val authResult = api.signIn(username, password)
                if (authResult.isFailure) {
                    return@withContext Result.failure(
                        authResult.exceptionOrNull() ?: Exception("Authentication failed")
                    )
                }

                val (authToken, userId) = authResult.getOrThrow()

                // Confirm the token actually works against this server before saving
                api.setCredentials(credentials.copy(authToken = authToken, userId = userId))
                val pingResult = api.ping()
                if (pingResult.isFailure) {
                    _activeAccountFlow.value?.let { activateAccountInternal(it) }
                        ?: api.clearCredentials()
                    return@withContext Result.failure(
                        Exception("Signed in to plex.tv but could not reach the server: " +
                                "${pingResult.exceptionOrNull()?.message}")
                    )
                }

                addAccountAndActivate(
                    PlexAccount(
                        id = userId.takeIf { it.isNotBlank() } ?: username,
                        username = username,
                        plexTvToken = authToken,
                        serverToken = authToken,
                        serverUrl = credentials.normalizedServerUrl
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Login failed")
                Result.failure(e)
            }
        }
    }

    /** Signs out of every account and wipes the local mirror. */
    suspend fun logout() {
        Timber.d("$TAG: Logging out of all accounts")
        cancelPeriodicSync()
        api.clearCredentials()
        prefs.edit().clear().apply()

        clearLocalMirror()
        _accountsFlow.value = emptyList()
        _activeAccountFlow.value = null
        _isLoggedInFlow.value = false
    }

    // ─── Playlists ────────────────────────────────────────────────────────

    suspend fun syncPlaylists(): Result<List<PlexPlaylistEntity>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))

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
                val playlists = PlexResponseParser.parsePlaylists(jsonObjects)

                if (playlists.isEmpty() && jsonObjects.isNotEmpty()) {
                    Timber.w("$TAG: Parser returned empty playlists but JSON had items. Aborting.")
                    return@withContext Result.failure(Exception("Playlist parsing error"))
                }

                if (playlists.isEmpty()) {
                    val localCount = dao.getPlaylistCount()
                    if (localCount > 0) {
                        Timber.w("$TAG: Server returned empty playlists but we have $localCount locally. Aborting sync.")
                        return@withContext Result.success(emptyList())
                    }
                }

                val entities = playlists.map { playlist ->
                    PlexPlaylistEntity(
                        id = playlist.id,
                        name = playlist.name,
                        songCount = playlist.songCount,
                        duration = playlist.duration,
                        lastSyncTime = System.currentTimeMillis()
                    )
                }

                val localPlaylists = dao.getAllPlaylistsList()
                val remoteIds = entities.map { it.id }.toSet()
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
                        deleteAppPlaylistForPlexPlaylist(stale.id)
                    }
                }

                entities.forEach { dao.insertPlaylist(it) }

                if (stalePlaylists.isNotEmpty()) {
                    syncUnifiedLibrarySongsFromPlex()
                }

                Timber.d("$TAG: Synced ${entities.size} playlists")
                Result.success(entities)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync playlists")
                Result.failure(e)
            }
        }
    }

    suspend fun syncPlaylistSongs(playlistId: String): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("$TAG: Syncing songs for playlist $playlistId")
                val result = api.getPlaylistItems(playlistId)
                if (result.isFailure) {
                    return@withContext Result.failure(
                        result.exceptionOrNull() ?: Exception("Failed to get playlist")
                    )
                }

                val songJsons = result.getOrThrow()
                val songs = PlexResponseParser.parseSongs(songJsons)

                if (songs.isEmpty() && songJsons.isNotEmpty()) {
                    Timber.w("$TAG: FAILED to parse songs for playlist $playlistId. Aborting.")
                    return@withContext Result.failure(Exception("Parsing error"))
                }

                val entities = songs.map { song: PlexSong ->
                    song.toEntity(playlistId)
                }

                if (entities.isNotEmpty()) {
                    dao.deleteSongsByPlaylist(playlistId)
                    dao.insertSongs(entities)
                    val playlistName = dao.getPlaylistById(playlistId)?.name ?: "Playlist"
                    updateAppPlaylistForPlexPlaylist(playlistId, playlistName, entities)
                } else if (songJsons.isEmpty()) {
                    dao.deleteSongsByPlaylist(playlistId)
                    val playlistName = dao.getPlaylistById(playlistId)?.name ?: "Playlist"
                    updateAppPlaylistForPlexPlaylist(playlistId, playlistName, emptyList())
                }

                Timber.d("$TAG: Synced ${entities.size} songs for playlist $playlistId")
                Result.success(entities.size)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync playlist songs")
                Result.failure(e)
            }
        }
    }

    suspend fun syncLibrarySongs(): Result<Int> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))

        return withContext(Dispatchers.IO) {
            try {
                Timber.d("$TAG: Syncing library songs from server")
                val sectionKeys = api.getMusicSectionKeys().getOrElse {
                    return@withContext Result.failure(it)
                }
                if (sectionKeys.isEmpty()) {
                    Timber.d("$TAG: No music sections found on server")
                    return@withContext Result.success(0)
                }

                val allSongs = mutableListOf<PlexSong>()
                val pageSize = 500

                sectionKeys.forEach { sectionKey ->
                    var startIndex = 0
                    while (true) {
                        val result = api.getMusicItems(sectionKey, startIndex = startIndex, limit = pageSize)
                        val (_, items) = result.getOrNull() ?: break
                        if (items.isEmpty()) break

                        val songs = PlexResponseParser.parseSongs(items)
                        allSongs.addAll(songs)
                        startIndex += items.size
                        if (items.size < pageSize) break
                    }
                }

                if (allSongs.isEmpty()) {
                    Timber.d("$TAG: No library songs found on server")
                    return@withContext Result.success(0)
                }

                val uniqueSongs = allSongs.distinctBy { it.id }
                val entities = uniqueSongs.map { song -> song.toEntity(LIBRARY_PLAYLIST_ID) }

                dao.clearLibrarySongs()
                dao.insertSongs(entities)

                Timber.d("$TAG: Synced ${entities.size} library songs")
                Result.success(entities.size)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync library songs")
                Result.failure(e)
            }
        }
    }

    suspend fun syncAllPlaylistsAndSongs(): Result<BulkSyncResult> {
        return withContext(Dispatchers.IO) {
            var syncedSongCount = 0
            var failedPlaylistCount = 0

            val libResult = syncLibrarySongs()
            libResult.fold(
                onSuccess = { count -> syncedSongCount += count },
                onFailure = { Timber.w(it, "$TAG: Failed syncing library songs") }
            )

            val playlistResult = syncPlaylists().getOrElse {
                try {
                    syncUnifiedLibrarySongsFromPlex()
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to sync unified library after playlist fetch failure")
                }
                return@withContext Result.success(
                    BulkSyncResult(playlistCount = 0, syncedSongCount = syncedSongCount, failedPlaylistCount = 0)
                )
            }

            playlistResult.forEach { playlist ->
                val songSyncResult = syncPlaylistSongs(playlist.id)
                songSyncResult.fold(
                    onSuccess = { count -> syncedSongCount += count },
                    onFailure = {
                        failedPlaylistCount += 1
                        Timber.w(it, "$TAG: Failed syncing playlist ${playlist.id}")
                    }
                )
            }

            try {
                syncUnifiedLibrarySongsFromPlex()
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync unified library")
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

    fun getPlaylists(): Flow<List<PlexPlaylistEntity>> = dao.getAllPlaylists()

    fun getPlaylistSongs(playlistId: String): Flow<List<Song>> {
        return dao.getSongsByPlaylist(playlistId).map { entities ->
            entities.map { it.toSong() }
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        dao.clearSongsByPlaylist(playlistId)
        dao.deletePlaylist(playlistId)
        syncUnifiedLibrarySongsFromPlex()
    }

    fun getAllSongs(): Flow<List<Song>> {
        return dao.getAllPlexSongs().map { entities ->
            entities.map { it.toSong() }
        }
    }

    suspend fun getPlaylistSongPlexIds(playlistId: String): List<String> {
        return dao.getSongsByPlaylist(playlistId).first().map { it.plexId }.distinct()
    }

    suspend fun getAllSongPlexIds(): List<String> {
        return dao.getAllPlexSongsList().map { it.plexId }.distinct()
    }

    // ─── Search ────────────────────────────────────────────────────────────

    suspend fun searchSongs(query: String, limit: Int = 30): Result<List<Song>> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))

        return withContext(Dispatchers.IO) {
            try {
                val result = api.searchSongs(query, limit)
                if (result.isFailure) {
                    return@withContext Result.failure(
                        result.exceptionOrNull() ?: Exception("Search failed")
                    )
                }
                val plexSongs = PlexResponseParser.parseSongs(result.getOrThrow())
                Result.success(plexSongs.map { it.toDisplaySong() })
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Search failed")
                Result.failure(e)
            }
        }
    }

    fun searchLocalSongs(query: String): Flow<List<Song>> {
        return dao.searchSongs(query).map { entities ->
            entities.map { it.toSong() }
        }
    }

    // ─── Remote control (Plex Companion / Plexamp) ─────────────────────────

    @Volatile
    private var cachedServerMachineId: String? = null

    private suspend fun getServerMachineId(): String? {
        cachedServerMachineId?.let { return it }
        return api.getServerMachineIdentifier().getOrNull()?.also {
            cachedServerMachineId = it
        }
    }

    val activePlexTvToken: String?
        get() = _activeAccountFlow.value?.plexTvToken

    val activeServerToken: String?
        get() = _activeAccountFlow.value?.serverToken

    /** The active server's stable machine identifier (cached after first fetch). */
    suspend fun serverMachineId(): String? = getServerMachineId()

    /**
     * Resolves a server play queue into playable [Song]s, in queue order.
     * Tracks already in the local mirror come from the DAO; anything else is
     * built from the play-queue metadata so unsynced tracks still play.
     */
    suspend fun getPlayQueueSongs(playQueueId: Long): List<Song> {
        val items = api.getPlayQueue(playQueueId).getOrElse { return emptyList() }
        return items.mapNotNull { item ->
            val ratingKey = item.optString("ratingKey").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            dao.getSongByPlexId(ratingKey)?.toSong() ?: Song(
                id = "plex_companion_$ratingKey",
                title = item.optString("title", "Unknown"),
                artist = item.optString("grandparentTitle", "Unknown"),
                artistId = -1L,
                album = item.optString("parentTitle", "Unknown"),
                albumId = -1L,
                path = "",
                contentUriString = "plex://$ratingKey",
                albumArtUriString = "plex_cover://$ratingKey",
                duration = item.optLong("duration", 0L),
                dateAdded = System.currentTimeMillis(),
                mimeType = null,
                bitrate = null,
                sampleRate = null,
                plexId = ratingKey
            )
        }
    }

    /**
     * Remote-controllable players on the active account (other Plexamp
     * instances etc.), each resolved to its first reachable address.
     */
    suspend fun getRemotePlayers(): Result<List<PlexPlayerDevice>> {
        val account = _activeAccountFlow.value
            ?: return Result.failure(Exception("Not logged in"))

        val resources = api.getPlayers(account.plexTvToken).getOrElse {
            return Result.failure(it)
        }

        val players = coroutineScope {
            resources
                // Never list ourselves: this install is now a Companion player
                // too and shows up in plex.tv resources like any Plexamp.
                .filter { it.clientIdentifier.isNotBlank() && it.clientIdentifier != api.clientIdentifier }
                .map { resource ->
                    async {
                        // Local, direct addresses first; Companion players don't
                        // publish relay endpoints that are useful to us.
                        val ordered = resource.connections.sortedWith(
                            compareByDescending<com.theveloper.pixelplay.data.plex.model.PlexServerConnection> { it.isLocal }
                                .thenBy { it.isRelay }
                        )
                        val reachable = ordered.firstOrNull { connection ->
                            !connection.isRelay && companionClient.isReachable(
                                connection.uri, resource.clientIdentifier, account.plexTvToken
                            )
                        }
                        reachable?.let {
                            PlexPlayerDevice(
                                name = resource.name.ifBlank { resource.product },
                                product = resource.product,
                                clientIdentifier = resource.clientIdentifier,
                                uri = it.uri
                            )
                        }
                    }
                }
                .mapNotNull { it.await() }
        }

        return Result.success(players)
    }

    suspend fun sendRemoteCommand(device: PlexPlayerDevice, command: String): Result<Unit> {
        val token = _activeAccountFlow.value?.plexTvToken
        return companionClient.sendPlaybackCommand(device, command, token)
    }

    suspend fun setRemoteVolume(device: PlexPlayerDevice, volume: Int): Result<Unit> {
        val token = _activeAccountFlow.value?.plexTvToken
        return companionClient.setVolume(device, volume, token)
    }

    suspend fun seekRemote(device: PlexPlayerDevice, positionMs: Long): Result<Unit> {
        val token = _activeAccountFlow.value?.plexTvToken
        return companionClient.seekTo(device, positionMs, token)
    }

    suspend fun getRemoteTimeline(device: PlexPlayerDevice): Result<PlexRemoteTimeline?> {
        val token = _activeAccountFlow.value?.plexTvToken
        return companionClient.getTimeline(device, token)
    }

    /** Local-mirror lookup so the remote screen can show title/artist. */
    suspend fun getSongByRatingKey(ratingKey: String): Song? {
        return try {
            dao.getSongByPlexId(ratingKey)?.toSong()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Hosts where the active server can be reached on its own LAN, from
     * plex.tv resources. The stored serverUrl may be a tunnel/proxy hostname
     * that only forwards Plex's port — sidecar services (the Connect broker)
     * are only reachable via these direct addresses.
     */
    suspend fun getServerLocalHosts(): List<String> {
        val token = _activeAccountFlow.value?.plexTvToken ?: return emptyList()
        val resources = api.getServers(token).getOrNull() ?: return emptyList()
        return resources
            .flatMap { it.connections }
            .filter { it.isLocal && !it.isRelay }
            .mapNotNull { it.uri.toHttpUrlOrNull()?.host }
            .distinct()
    }

    /** Album-art path (PMS transcoder input) for a track, when mirrored locally. */
    suspend fun getThumbPathForRatingKey(ratingKey: String): String? {
        return try {
            dao.getSongByPlexId(ratingKey)?.thumbPath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Start playing a queue of tracks from this account's server on a remote
     * player: creates a play queue on the server, then points the player at
     * it, selecting [startRatingKey] at [offsetMs].
     */
    suspend fun playQueueOnDevice(
        device: PlexPlayerDevice,
        ratingKeys: List<String>,
        startRatingKey: String,
        offsetMs: Long = 0L
    ): Result<Unit> {
        if (ratingKeys.isEmpty()) return Result.failure(Exception("Empty queue"))
        val account = _activeAccountFlow.value
            ?: return Result.failure(Exception("Not logged in"))
        val machineId = getServerMachineId()
            ?: return Result.failure(Exception("Could not resolve server identity"))

        val playQueueId = api.createPlayQueue(
            metadataIds = ratingKeys.joinToString(","),
            machineIdentifier = machineId
        ).getOrElse {
            return Result.failure(it)
        }

        return companionClient.playMedia(
            device = device,
            serverUrl = account.serverUrl,
            serverMachineIdentifier = machineId,
            serverToken = account.serverToken,
            playQueueId = playQueueId,
            trackKey = "/library/metadata/$startRatingKey",
            offsetMs = offsetMs,
            token = account.plexTvToken
        )
    }

    // ─── Playback Reporting ────────────────────────────────────────────────

    suspend fun scrobble(ratingKey: String): Result<Unit> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return api.scrobble(ratingKey)
    }

    suspend fun reportPlayback(
        ratingKey: String,
        positionMs: Long,
        state: String,
        durationMs: Long
    ): Result<Unit> {
        if (!isLoggedIn) return Result.failure(Exception("Not logged in"))
        return api.reportTimeline(
            ratingKey = ratingKey,
            state = state,
            timeMs = positionMs,
            durationMs = durationMs
        )
    }

    // ─── Media URLs ────────────────────────────────────────────────────────

    suspend fun getStreamUrl(songId: String): String {
        return api.getStreamUrl(songId).getOrThrow()
    }

    /**
     * Resolve the art URL for a plex_cover:// id. Tracks inherit album/artist
     * art, so prefer the exact thumb path captured at sync time; the bare
     * /library/metadata/{id}/thumb fallback still works for albums and artists.
     */
    suspend fun getImageUrl(itemId: String?, size: Int = 500): String? {
        if (itemId.isNullOrBlank()) return null
        val storedThumb = try {
            dao.getSongByPlexId(itemId)?.thumbPath
        } catch (_: Exception) {
            null
        }
        return if (!storedThumb.isNullOrBlank()) {
            api.getImageUrlForPath(storedThumb, maxWidth = size)
        } else {
            api.getImageUrl(itemId, maxWidth = size)
        }
    }

    // ─── Unified Library Sync ──────────────────────────────────────────────

    suspend fun syncUnifiedLibrarySongsFromPlex() {
        val plexSongs = dao.getAllPlexSongsList()
        val existingUnifiedIds = musicDao.getAllPlexSongIds()

        if (plexSongs.isEmpty()) {
            if (existingUnifiedIds.isNotEmpty()) {
                musicDao.clearAllPlexSongs()
            }
            return
        }

        val songs = ArrayList<SongEntity>(plexSongs.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = mutableListOf<SongArtistCrossRef>()

        plexSongs.forEach { plexSong ->
            val songId = toUnifiedSongId(plexSong.plexId)
            val artistNames = parseArtistNames(plexSong.artist)
            val primaryArtistName = artistNames.firstOrNull() ?: "Unknown Artist"
            val primaryArtistId = toUnifiedArtistId(primaryArtistName)

            artistNames.forEachIndexed { index, artistName ->
                val artistId = toUnifiedArtistId(artistName)
                // The primary artist maps to the Plex artist item (grandparent),
                // whose own thumb resource serves as the artist image.
                val artistImageUrl = if (index == 0 && !plexSong.artistId.isNullOrBlank()) {
                    "plex_cover://${plexSong.artistId}"
                } else {
                    null
                }
                artists.putIfAbsent(
                    artistId,
                    ArtistEntity(
                        id = artistId,
                        name = artistName,
                        trackCount = 0,
                        imageUrl = artistImageUrl
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

            val albumId = toUnifiedAlbumId(plexSong.albumId, plexSong.album)
            val albumName = plexSong.album.ifBlank { "Unknown Album" }
            albums.putIfAbsent(
                albumId,
                AlbumEntity(
                    id = albumId,
                    title = albumName,
                    artistName = primaryArtistName,
                    artistId = primaryArtistId,
                    songCount = 0,
                    dateAdded = plexSong.dateAdded,
                    year = plexSong.year,
                    albumArtUriString = "plex_cover://${plexSong.plexId}"
                )
            )

            songs.add(
                SongEntity(
                    id = songId,
                    title = plexSong.title,
                    artistName = plexSong.artist.ifBlank { primaryArtistName },
                    artistId = primaryArtistId,
                    albumArtist = null,
                    albumName = albumName,
                    albumId = albumId,
                    contentUriString = "plex://${plexSong.plexId}",
                    albumArtUriString = "plex_cover://${plexSong.plexId}",
                    duration = plexSong.duration,
                    genre = plexSong.genre ?: PLEX_GENRE,
                    filePath = plexSong.path,
                    parentDirectoryPath = PLEX_PARENT_DIRECTORY,
                    isFavorite = false,
                    lyrics = null,
                    trackNumber = plexSong.trackNumber,
                    year = plexSong.year,
                    dateAdded = plexSong.dateAdded.takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                    mimeType = plexSong.mimeType,
                    bitrate = plexSong.bitRate?.let { it * 1000 },
                    sampleRate = null,
                    telegramChatId = null,
                    telegramFileId = null,
                    sourceType = SourceType.PLEX
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

    private fun toUnifiedSongId(plexId: String): Long {
        return -(PLEX_SONG_ID_OFFSET + plexId.hashCode().toLong().absoluteValue)
    }

    private fun toUnifiedAlbumId(albumId: String?, albumName: String): Long {
        val normalized = if (!albumId.isNullOrBlank()) {
            albumId.hashCode().toLong().absoluteValue
        } else {
            albumName.lowercase().hashCode().toLong().absoluteValue
        }
        return -(PLEX_ALBUM_ID_OFFSET + normalized)
    }

    private fun toUnifiedArtistId(artistName: String): Long {
        return -(PLEX_ARTIST_ID_OFFSET + artistName.lowercase().hashCode().toLong().absoluteValue)
    }

    // ─── App Playlist Management ───────────────────────────────────────────

    private suspend fun updateAppPlaylistForPlexPlaylist(
        plexPlaylistId: String,
        playlistName: String,
        songs: List<PlexSongEntity>
    ) {
        val appPlaylistId = "$PLEX_PLAYLIST_PREFIX$plexPlaylistId"
        val songIds = songs.map { toUnifiedSongId(it.plexId).toString() }

        val existingPlaylist = withContext(Dispatchers.IO) {
            playlistPreferencesRepository.userPlaylistsFlow.map { playlists ->
                playlists.find { it.id == appPlaylistId }
            }.first()
        }

        if (existingPlaylist != null) {
            playlistPreferencesRepository.updatePlaylist(
                existingPlaylist.copy(
                    name = playlistName,
                    songIds = songIds,
                    lastModified = System.currentTimeMillis(),
                    source = "PLEX"
                )
            )
        } else {
            playlistPreferencesRepository.createPlaylist(
                name = playlistName,
                songIds = songIds,
                customId = appPlaylistId,
                source = "PLEX"
            )
        }
    }

    private suspend fun deleteAppPlaylistForPlexPlaylist(plexPlaylistId: String) {
        val appPlaylistId = "$PLEX_PLAYLIST_PREFIX$plexPlaylistId"
        playlistPreferencesRepository.deletePlaylist(appPlaylistId)
    }

    private fun PlexSong.toDisplaySong(): Song {
        return Song(
            id = "plex_search_$id",
            title = title,
            artist = artist,
            artistId = -1L,
            album = album,
            albumId = -1L,
            path = path,
            contentUriString = "plex://$id",
            albumArtUriString = "plex_cover://$id",
            duration = duration,
            genre = genre,
            mimeType = resolvedMimeType,
            bitrate = bitRate?.let { it * 1000 },
            sampleRate = null,
            year = year,
            trackNumber = trackNumber,
            dateAdded = 0,
            isFavorite = false
        )
    }
}
