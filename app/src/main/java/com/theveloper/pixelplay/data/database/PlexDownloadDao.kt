package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlexDownloadDao {

    @Query("SELECT * FROM plex_downloads")
    fun getAllDownloads(): Flow<List<PlexDownloadEntity>>

    @Query("SELECT * FROM plex_downloads")
    suspend fun getAllDownloadsList(): List<PlexDownloadEntity>

    @Query("SELECT * FROM plex_downloads WHERE plex_id = :plexId LIMIT 1")
    suspend fun getDownload(plexId: String): PlexDownloadEntity?

    @Query("SELECT plex_id FROM plex_downloads")
    suspend fun getAllDownloadedIds(): List<String>

    @Query("SELECT COUNT(*) FROM plex_downloads")
    fun getDownloadCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM plex_downloads")
    fun getTotalSizeBytes(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: PlexDownloadEntity)

    @Query("DELETE FROM plex_downloads WHERE plex_id = :plexId")
    suspend fun delete(plexId: String)

    @Query("DELETE FROM plex_downloads")
    suspend fun deleteAll()
}
