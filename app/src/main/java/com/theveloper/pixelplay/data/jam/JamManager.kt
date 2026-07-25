package com.theveloper.pixelplay.data.jam

import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.navidrome.JamHost
import com.theveloper.pixelplay.data.navidrome.JamState
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.service.MusicService
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The "Jam" (Spotify-Jam style) coordinator.
 *
 * HOST role (automatic): while this device is playing AND "Allow household control" is on, it
 * heartbeats its now-playing state to the gateway and applies any commands other household phones
 * send (play/pause/next/previous/seek). Runs whether or not the UI is open, via an app-scoped
 * MediaController bound to [MusicService], so a controller can drive the phone while it's in a
 * pocket driving the car.
 *
 * GUEST role (on demand): [startDiscovery] polls the gateway for household devices currently
 * playing and exposes them as [hosts]; [control] sends a command to one.
 */
@Singleton
class JamManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navidromeRepository: NavidromeRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Stable per-process id: identifies this device as a host, and lets the guest exclude itself. */
    val sessionId: String = UUID.randomUUID().hex()

    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private val _hosts = MutableStateFlow<List<JamHost>>(emptyList())
    /** Household devices currently playing (excludes this device). Updated while discovering. */
    val hosts: StateFlow<List<JamHost>> = _hosts.asStateFlow()

    @Volatile
    private var controller: MediaController? = null
    private var discoveryJob: Job? = null

    /** Start the host role. Called once, app-scoped, from PixelPlayApplication. */
    fun start() {
        scope.launch {
            combine(
                PlaybackActivityTracker.isPlaybackActiveFlow,
                userPreferencesRepository.allowHouseholdControlFlow
            ) { active, allow -> active && allow }
                .distinctUntilChanged()
                .collectLatest { hosting ->
                    if (!hosting) return@collectLatest
                    navidromeRepository.registerJamDevice(deviceName, "android", sessionId)
                    // collectLatest cancels this loop the moment hosting flips false.
                    while (true) {
                        runCatching {
                            val snapshot = readState()
                            if (snapshot != null) {
                                val (state, queue) = snapshot
                                val commands = navidromeRepository.jamHeartbeat(sessionId, state, queue)
                                commands.forEach { applyCommand(it.action, it.positionMs) }
                            }
                        }
                        delay(HOST_HEARTBEAT_MS)
                    }
                }
        }
    }

    // ── Guest role ───────────────────────────────────────────────────────────
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            while (true) {
                _hosts.value = navidromeRepository.getJamHosts(sessionId).filter { it.id != sessionId }
                delay(GUEST_POLL_MS)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    suspend fun control(hostId: String, action: String, positionMs: Long? = null): Boolean =
        navidromeRepository.jamControl(hostId, action, positionMs)

    // ── Host MediaController plumbing (main-thread only) ──────────────────────
    private suspend fun ensureController(): MediaController? {
        controller?.let { return it }
        return withContext(Dispatchers.Main) {
            runCatching {
                val token = SessionToken(context, ComponentName(context, MusicService::class.java))
                MediaController.Builder(context, token).buildAsync().await().also { controller = it }
            }.getOrNull()
        }
    }

    private suspend fun readState(): Pair<JamState, List<String>>? = withContext(Dispatchers.Main) {
        val c = ensureController() ?: return@withContext null
        val item = c.currentMediaItem ?: return@withContext null
        val md = item.mediaMetadata
        val state = JamState(
            songId = item.mediaId,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            coverArt = md.artworkUri?.toString().orEmpty(),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            isPlaying = c.isPlaying
        )
        val queueIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        state to queueIds
    }

    private suspend fun applyCommand(action: String, positionMs: Long?) = withContext(Dispatchers.Main) {
        val c = ensureController() ?: return@withContext
        when (action) {
            "playpause" -> if (c.isPlaying) c.pause() else c.play()
            "play" -> c.play()
            "pause" -> c.pause()
            "next" -> c.seekToNextMediaItem()
            "previous" -> c.seekToPreviousMediaItem()
            "seek" -> positionMs?.let { c.seekTo(it) }
            else -> Unit  // "enqueue" reserved for a later version
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener({
                try {
                    cont.resume(get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, { it.run() })
            cont.invokeOnCancellation { cancel(false) }
        }

    private fun UUID.hex(): String = toString().replace("-", "")

    companion object {
        private const val HOST_HEARTBEAT_MS = 4000L
        private const val GUEST_POLL_MS = 3000L
    }
}
