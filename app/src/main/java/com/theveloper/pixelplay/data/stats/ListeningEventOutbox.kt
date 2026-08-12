package com.theveloper.pixelplay.data.stats

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Durable queue of listening events not yet confirmed uploaded to the gateway — the safety net
 * for [PlaybackStatsRepository.recordPlayback] being called with no connectivity. Mirrors that
 * class's AtomicFile+Gson persistence shape (same pattern, separate file, separate lock).
 */
@Singleton
class ListeningEventOutbox @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val outboxFile = File(context.filesDir, "listening_event_outbox.json")
    private val atomicOutboxFile = AtomicFile(outboxFile)
    private val fileLock = Any()
    private var cachedEvents: List<PendingListeningEvent>? = null // guarded by fileLock
    private val eventsType = object : TypeToken<MutableList<PendingListeningEvent>>() {}.type

    data class PendingListeningEvent(
        val eventId: String,
        val navidromeId: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val durationMs: Long,
        val startTimestamp: Long,
        val endTimestamp: Long
    )

    /** Queues a new event (generating its [PendingListeningEvent.eventId]) and returns it, so
     *  the caller can attempt an inline upload and [remove] it immediately on success. */
    suspend fun enqueue(
        navidromeId: String,
        title: String,
        artist: String,
        album: String,
        cover: String,
        durationMs: Long,
        startTimestamp: Long,
        endTimestamp: Long
    ): PendingListeningEvent = withContext(Dispatchers.IO) {
        val event = PendingListeningEvent(
            eventId = UUID.randomUUID().toString(),
            navidromeId = navidromeId,
            title = title,
            artist = artist,
            album = album,
            cover = cover,
            durationMs = durationMs,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp
        )
        updateEventsAtomically { events ->
            events += event
            if (events.size > MAX_QUEUED_EVENTS) {
                // An outage long enough to overflow this shouldn't grow the queue forever —
                // drop the oldest first, an acceptable trade against unbounded local storage.
                val overflow = events.size - MAX_QUEUED_EVENTS
                repeat(overflow) { events.removeAt(0) }
            }
            events
        }
        event
    }

    /** Everything still waiting to be uploaded, oldest first. */
    suspend fun pending(): List<PendingListeningEvent> = withContext(Dispatchers.IO) {
        readEvents()
    }

    /** Drops confirmed-uploaded (or confirmed-duplicate, per the gateway's idempotent insert)
     *  events from the queue. */
    suspend fun remove(eventIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext
        val idSet = eventIds.toHashSet()
        updateEventsAtomically { events ->
            events.removeAll { it.eventId in idSet }
            events
        }
    }

    private fun readEvents(): List<PendingListeningEvent> {
        synchronized(fileLock) { cachedEvents }?.let { return it }
        val raw = synchronized(fileLock) { readRawLocked() }
        return parseEvents(raw).also { parsed ->
            synchronized(fileLock) { if (cachedEvents == null) cachedEvents = parsed }
        }
    }

    private fun readRawLocked(): String? {
        return runCatching {
            atomicOutboxFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
            .onFailure { throwable ->
                if (throwable !is FileNotFoundException) {
                    Timber.e(throwable, "Failed reading listening event outbox")
                }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseEvents(raw: String?): MutableList<PendingListeningEvent> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return runCatching {
            val parsed: MutableList<PendingListeningEvent>? = gson.fromJson(raw, eventsType)
            parsed ?: mutableListOf()
        }.getOrElse { throwable ->
            Timber.e(throwable, "Failed parsing listening event outbox")
            mutableListOf()
        }
    }

    private fun updateEventsAtomically(
        transform: (MutableList<PendingListeningEvent>) -> MutableList<PendingListeningEvent>
    ): Boolean {
        repeat(MAX_FILE_UPDATE_RETRIES) {
            val rawSnapshot = synchronized(fileLock) { readRawLocked() }
            val updatedEvents = transform(parseEvents(rawSnapshot))
            val payload = gson.toJson(updatedEvents).toByteArray(Charsets.UTF_8)

            val writeSucceeded = synchronized(fileLock) {
                val latestRaw = readRawLocked()
                if (latestRaw != rawSnapshot) {
                    return@synchronized false
                }
                val result = writePayloadLocked(payload)
                if (result) cachedEvents = null
                result
            }
            if (writeSucceeded) {
                return true
            }
        }

        val fallbackRawSnapshot = synchronized(fileLock) { readRawLocked() }
        val payload = gson.toJson(transform(parseEvents(fallbackRawSnapshot))).toByteArray(Charsets.UTF_8)
        return synchronized(fileLock) {
            val result = writePayloadLocked(payload)
            if (result) cachedEvents = null
            result
        }
    }

    private fun writePayloadLocked(payload: ByteArray): Boolean {
        var outputStream: FileOutputStream? = null
        return runCatching {
            val parent = outboxFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Timber.w("Unable to ensure listening event outbox directory: ${parent.absolutePath}")
            }
            outputStream = atomicOutboxFile.startWrite()
            outputStream?.write(payload)
            outputStream?.fd?.sync()
            outputStream?.let { atomicOutboxFile.finishWrite(it) }
            outputStream = null
            true
        }.onFailure { throwable ->
            outputStream?.let { stream -> atomicOutboxFile.failWrite(stream) }
            Timber.e(throwable, "Failed to persist listening event outbox")
        }.getOrDefault(false)
    }

    companion object {
        private const val MAX_QUEUED_EVENTS = 2000
        private const val MAX_FILE_UPDATE_RETRIES = 3
    }
}
