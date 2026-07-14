package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlexDao {

    // ─── Songs ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM plex_songs ORDER BY date_added DESC")
    fun getAllPlexSongs(): Flow<List<PlexSongEntity>>

    @Query("SELECT * FROM plex_songs ORDER BY date_added DESC")
    suspend fun getAllPlexSongsList(): List<PlexSongEntity>

    @Query("SELECT * FROM plex_songs WHERE playlist_id = :playlistId ORDER BY date_added DESC")
    fun getSongsByPlaylist(playlistId: String): Flow<List<PlexSongEntity>>

    @Query("SELECT * FROM plex_songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%'")
    fun searchSongs(query: String): Flow<List<PlexSongEntity>>

    @Query("SELECT * FROM plex_songs WHERE id IN (:ids)")
    fun getSongsByIds(ids: List<String>): Flow<List<PlexSongEntity>>

    @Query("SELECT * FROM plex_songs WHERE plex_id = :plexId LIMIT 1")
    suspend fun getSongByPlexId(plexId: String): PlexSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<PlexSongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: PlexSongEntity)

    @Query("DELETE FROM plex_songs WHERE id = :songId")
    suspend fun deleteSong(songId: String)

    @Query("DELETE FROM plex_songs WHERE playlist_id = :playlistId")
    suspend fun deleteSongsByPlaylist(playlistId: String)

    // ─── Playlists ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlexPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlexPlaylistEntity>)

    @Query("SELECT * FROM plex_playlists ORDER BY name ASC")
    fun getAllPlaylists(): Flow<List<PlexPlaylistEntity>>

    @Query("SELECT * FROM plex_playlists")
    suspend fun getAllPlaylistsList(): List<PlexPlaylistEntity>

    @Query("SELECT * FROM plex_playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: String): PlexPlaylistEntity?

    @Query("DELETE FROM plex_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM plex_songs WHERE playlist_id = :playlistId")
    suspend fun clearSongsByPlaylist(playlistId: String)

    @Query("SELECT COUNT(*) FROM plex_playlists")
    suspend fun getPlaylistCount(): Int

    @Query("SELECT DISTINCT plex_id FROM plex_songs")
    suspend fun getAllDistinctPlexIds(): List<String>

    @Query("DELETE FROM plex_songs WHERE playlist_id = '__library__'")
    suspend fun clearLibrarySongs()

    // ─── Clear All ─────────────────────────────────────────────────────

    @Query("DELETE FROM plex_songs")
    suspend fun clearAllSongs()

    @Query("DELETE FROM plex_playlists")
    suspend fun clearAllPlaylists()
}
