package com.theveloper.pixelplay.utils

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi

/**
 * Helper for requesting MediaStore write/delete permissions on Android 11+
 * without needing MANAGE_EXTERNAL_STORAGE.
 *
 * After the user approves the system dialog, both ContentResolver-based and
 * raw file-path operations are allowed (thanks to the FUSE virtual filesystem).
 */
object MediaStorePermissionHelper {
    private const val MEDIASTORE_AUTHORITY = "media"

    /**
     * Legacy/fallback version of getMediaStoreUri when Context is not available.
     * Returns the MediaStore content URI using the primary external volume to avoid Invalid Uri exception.
     */
    fun getMediaStoreUri(songId: Long): Uri? {
        if (songId <= 0) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, songId)
        } else {
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        }
    }

    fun isMediaStoreItemUriString(contentUriString: String): Boolean {
        val normalized = contentUriString.trim().lowercase()
        if (!normalized.startsWith("content://$MEDIASTORE_AUTHORITY/")) return false
        return normalized.substringAfterLast('/').toLongOrNull()?.let { it > 0 } == true
    }

    fun canUseSongIdForMediaStoreRequest(contentUriString: String): Boolean {
        val normalized = contentUriString.trim().lowercase()
        return normalized.isBlank() ||
            normalized.startsWith("/") ||
            normalized.startsWith("file://") ||
            isMediaStoreItemUriString(normalized)
    }

    /**
     * Returns the MediaStore content URI for a given file path.
     * Useful for non-audio files like .lrc that are indexed by MediaStore.
     */
    fun getMediaStoreUri(context: Context, filePath: String): Uri? {
        val hasVolumeName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = if (hasVolumeName) {
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.VOLUME_NAME)
        } else {
            arrayOf(MediaStore.Files.FileColumns._ID)
        }
        val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
        val selectionArgs = arrayOf(filePath)
        val queryUri = MediaStore.Files.getContentUri("external")

        return context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val volumeName = if (hasVolumeName) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.VOLUME_NAME))
                } else {
                    null
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val targetVolume = if (volumeName != null && volumeName != MediaStore.VOLUME_EXTERNAL) {
                        volumeName
                    } else {
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    }
                    val baseVolumeUri = MediaStore.Files.getContentUri(targetVolume)
                    ContentUris.withAppendedId(baseVolumeUri, id)
                } else {
                    ContentUris.withAppendedId(queryUri, id)
                }
            } else {
                null
            }
        }
    }

    /**
     * Creates an IntentSender that, when launched, asks the user to grant
     * write access to the given MediaStore URIs.
     *
     * Returns null on Android < 11 or if [uris] is empty.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createWriteRequestIntentSender(
        context: Context,
        uris: Collection<Uri>
    ): IntentSender? {
        if (uris.isEmpty()) return null

        // Filter out URIs that do not exist in the MediaStore database
        // to avoid IllegalArgumentException: Invalid Uri
        val existingIds = try {
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val idList = uris.mapNotNull { it.lastPathSegment?.toLongOrNull() }
            if (idList.isEmpty()) {
                emptySet()
            } else {
                val selection = "${MediaStore.Files.FileColumns._ID} IN (${idList.joinToString(",") { "?" }})"
                val selectionArgs = idList.map { it.toString() }.toTypedArray()
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idSet = mutableSetOf<Long>()
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    while (cursor.moveToNext()) {
                        idSet.add(cursor.getLong(idColumn))
                    }
                    idSet
                } ?: emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }

        val validUris = uris.filter { uri ->
            val id = uri.lastPathSegment?.toLongOrNull()
            id != null && id in existingIds
        }

        if (validUris.isEmpty()) return null

        return try {
            MediaStore.createWriteRequest(context.contentResolver, validUris).intentSender
        } catch (e: Exception) {
            android.util.Log.e("MediaStorePermissionHelper", "Failed to create write request for URIs: $validUris", e)
            null
        }
    }
}
