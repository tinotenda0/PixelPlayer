package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NavidromeDownloadDao {

    @Query("SELECT * FROM navidrome_downloads")
    fun getAllDownloads(): Flow<List<NavidromeDownloadEntity>>

    @Query("SELECT * FROM navidrome_downloads")
    suspend fun getAllDownloadsList(): List<NavidromeDownloadEntity>

    @Query("SELECT * FROM navidrome_downloads WHERE navidrome_id = :navidromeId LIMIT 1")
    suspend fun getDownload(navidromeId: String): NavidromeDownloadEntity?

    @Query("SELECT navidrome_id FROM navidrome_downloads")
    suspend fun getAllDownloadedIds(): List<String>

    @Query("SELECT COUNT(*) FROM navidrome_downloads")
    fun getDownloadCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM navidrome_downloads")
    fun getTotalSizeBytes(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: NavidromeDownloadEntity)

    @Query("DELETE FROM navidrome_downloads WHERE navidrome_id = :navidromeId")
    suspend fun delete(navidromeId: String)

    @Query("DELETE FROM navidrome_downloads")
    suspend fun deleteAll()
}
