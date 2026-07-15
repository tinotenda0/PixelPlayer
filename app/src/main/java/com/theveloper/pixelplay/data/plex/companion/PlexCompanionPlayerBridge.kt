package com.theveloper.pixelplay.data.plex.companion

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.MusicService
import com.theveloper.pixelplay.utils.MediaItemBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Playback state of this device expressed in Plex Companion terms —
 * what we report in timelines to controllers (Plexamp, Plex Web).
 */
data class CompanionPlaybackState(
    val state: String,          // playing | paused | stopped | buffering
    val timeMs: Long,
    val durationMs: Long,
    val ratingKey: String?,
    val containerKey: String?,
    val playQueueId: Long?,
    val volume: Int,            // 0..100
    val shuffle: Boolean,
    val repeatMode: Int,        // Player.REPEAT_MODE_*
    val index: Int = 0          // current position in the player's queue
)

/** One queue entry as reported to remote-control surfaces. */
data class CompanionQueueTrack(
    val ratingKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

/**
 * Bridges Plex Companion commands into the app's real player. Owns an
 * app-scoped [MediaController] connected to [MusicService], so commands work
 * whether or not any UI is alive.
 */
@Singleton
class PlexCompanionPlayerBridge @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PlexCompanionBridge"
    }

    @Volatile
    private var controller: MediaController? = null

    /**
     * Queue container last handed to us by a controller via playMedia,
     * echoed back in timelines so the controller can mirror the queue.
     */
    @Volatile
    var activeContainerKey: String? = null
        private set

    @Volatile
    var activePlayQueueId: Long? = null
        private set

    private val audioManager: AudioManager? = context.getSystemService()

    private suspend fun controller(): MediaController = withContext(Dispatchers.Main) {
        controller?.takeIf { it.isConnected } ?: run {
            controller?.release()
            val token = SessionToken(context, ComponentName(context, MusicService::class.java))
            MediaController.Builder(context, token).buildAsync().await().also {
                controller = it
            }
        }
    }

    /** Replace the queue and start playing — a controller's playMedia. */
    suspend fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        offsetMs: Long,
        containerKey: String?,
        playQueueId: Long?
    ): Boolean {
        if (songs.isEmpty()) return false
        activeContainerKey = containerKey
        activePlayQueueId = playQueueId
        return try {
            val ctrl = controller()
            withContext(Dispatchers.Main) {
                val items = songs.map { MediaItemBuilder.build(it) }
                ctrl.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), offsetMs.coerceAtLeast(0L))
                ctrl.prepare()
                ctrl.play()
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "playQueue failed")
            false
        }
    }

    suspend fun play() = onController { it.play() }

    suspend fun pause() = onController { it.pause() }

    suspend fun stop() = onController {
        it.pause()
        activeContainerKey = null
        activePlayQueueId = null
    }

    suspend fun skipNext() = onController { it.seekToNextMediaItem() }

    suspend fun skipPrevious() = onController { it.seekToPreviousMediaItem() }

    suspend fun seekTo(positionMs: Long) = onController { it.seekTo(positionMs.coerceAtLeast(0L)) }

    /** Jump to a specific index in the current queue. */
    suspend fun playIndex(index: Int) = onController {
        if (index in 0 until it.mediaItemCount) {
            it.seekTo(index, 0L)
            it.play()
        }
    }

    /**
     * Plex-backed tracks currently in the player's queue, in order. Items
     * without a rating key (non-Plex songs) are skipped.
     */
    suspend fun currentQueueTracks(maxItems: Int = 500): List<CompanionQueueTrack> {
        return try {
            val ctrl = controller()
            withContext(Dispatchers.Main) {
                (0 until minOf(ctrl.mediaItemCount, maxItems)).mapNotNull { i ->
                    val item = ctrl.getMediaItemAt(i)
                    val extras = item.mediaMetadata.extras
                    val ratingKey = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_PLEX_ID)
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    CompanionQueueTrack(
                        ratingKey = ratingKey,
                        title = item.mediaMetadata.title?.toString() ?: "Unknown",
                        artist = item.mediaMetadata.artist?.toString() ?: "Unknown",
                        album = item.mediaMetadata.albumTitle?.toString() ?: "",
                        durationMs = extras.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L)
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "currentQueueTracks failed")
            emptyList()
        }
    }

    /** Companion volume is 0..100 against the music stream. */
    fun setVolume(volume: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (volume.coerceIn(0, 100) * max / 100f).toInt().coerceIn(0, max)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            // Do-not-disturb restrictions can block volume changes.
            Timber.tag(TAG).w(e, "setStreamVolume blocked")
        }
    }

    private fun currentVolumePercent(): Int {
        val am = audioManager ?: return 100
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    /** Snapshot of the local player in Companion terms. */
    suspend fun currentState(): CompanionPlaybackState {
        return try {
            val ctrl = controller()
            withContext(Dispatchers.Main) {
                val item = ctrl.currentMediaItem
                val ratingKey = item?.mediaMetadata?.extras
                    ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_PLEX_ID)
                    ?.takeIf { it.isNotBlank() }
                val state = when {
                    item == null -> "stopped"
                    ctrl.playbackState == Player.STATE_BUFFERING -> "buffering"
                    ctrl.isPlaying -> "playing"
                    else -> "paused"
                }
                CompanionPlaybackState(
                    state = state,
                    timeMs = ctrl.currentPosition.coerceAtLeast(0L),
                    durationMs = ctrl.duration.takeIf { it > 0 } ?: 0L,
                    ratingKey = ratingKey,
                    containerKey = activeContainerKey,
                    playQueueId = activePlayQueueId,
                    volume = currentVolumePercent(),
                    shuffle = ctrl.shuffleModeEnabled,
                    repeatMode = ctrl.repeatMode,
                    index = ctrl.currentMediaItemIndex.coerceAtLeast(0)
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "currentState failed")
            CompanionPlaybackState(
                state = "stopped",
                timeMs = 0L,
                durationMs = 0L,
                ratingKey = null,
                containerKey = null,
                playQueueId = null,
                volume = currentVolumePercent(),
                shuffle = false,
                repeatMode = Player.REPEAT_MODE_OFF
            )
        }
    }

    fun release() {
        val ctrl = controller ?: return
        controller = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { ctrl.release() }
        }
    }

    private suspend fun onController(block: (MediaController) -> Unit): Boolean {
        return try {
            val ctrl = controller()
            withContext(Dispatchers.Main) { block(ctrl) }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Companion command failed")
            false
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T {
        return suspendCancellableCoroutine { cont ->
            addListener({
                try {
                    cont.resume(get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, { it.run() })
            cont.invokeOnCancellation { cancel(false) }
        }
    }
}
