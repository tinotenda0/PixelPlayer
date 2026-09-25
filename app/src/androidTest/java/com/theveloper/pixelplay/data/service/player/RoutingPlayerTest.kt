package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RoutingPlayer] is the single player behind the app's `MediaLibrarySession`, so a difference
 * between it and the local player it wraps is a difference every surface sees at once — the
 * in-app player, the notification, the lock screen, widgets and Wear.
 *
 * While no route is active it must be indistinguishable from the player it wraps. These tests
 * pin that down, because the whole staged rollout rests on the wrapper being introduced without
 * changing local playback.
 *
 * Instrumented rather than a JVM test on purpose: `ForwardingSimpleBasePlayer` asserts it is
 * called on its wrapped player's application looper, so a real one is needed. Nothing here
 * decodes audio — the assertions are about state and command pass-through, not playback.
 */
@RunWith(AndroidJUnit4::class)
class RoutingPlayerTest {

    private lateinit var local: ExoPlayer
    private lateinit var routing: RoutingPlayer

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Before
    fun setUp() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        local = ExoPlayer.Builder(context).build()
        routing = RoutingPlayer(local)
    }

    @After
    fun tearDown() = onMain {
        routing.release()
    }

    @Test
    fun reports_the_wrapped_players_state() = onMain {
        assertThat(routing.playbackState).isEqualTo(local.playbackState)
        assertThat(routing.playWhenReady).isEqualTo(local.playWhenReady)
        assertThat(routing.mediaItemCount).isEqualTo(local.mediaItemCount)
        assertThat(routing.currentMediaItemIndex).isEqualTo(local.currentMediaItemIndex)
        assertThat(routing.repeatMode).isEqualTo(local.repeatMode)
        assertThat(routing.shuffleModeEnabled).isEqualTo(local.shuffleModeEnabled)
    }

    @Test
    fun a_queue_set_on_the_wrapper_lands_on_the_local_player() = onMain {
        routing.setMediaItems(
            listOf(
                MediaItem.fromUri("https://example.invalid/a.mp3"),
                MediaItem.fromUri("https://example.invalid/b.mp3"),
            )
        )

        assertThat(local.mediaItemCount).isEqualTo(2)
        assertThat(routing.mediaItemCount).isEqualTo(2)
    }

    @Test
    fun transport_commands_pass_through() = onMain {
        routing.repeatMode = Player.REPEAT_MODE_ALL
        assertThat(local.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)

        routing.shuffleModeEnabled = true
        assertThat(local.shuffleModeEnabled).isTrue()

        // playWhenReady rather than play(): nothing here can actually decode, and the point is
        // that the intent reaches the local player, not that audio starts.
        routing.playWhenReady = true
        assertThat(local.playWhenReady).isTrue()
    }

    @Test
    fun state_changes_on_the_local_player_are_visible_through_the_wrapper() = onMain {
        local.repeatMode = Player.REPEAT_MODE_ONE
        assertThat(routing.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    }

    @Test
    fun swapping_the_local_player_keeps_the_wrapper_identity() = onMain {
        // What a crossfade does: DualPlayerEngine hands over a different ExoPlayer mid-track.
        // The session holds this wrapper for its whole life, so the swap has to happen
        // underneath without the session's player changing.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val replacement = ExoPlayer.Builder(context).build()
        try {
            replacement.repeatMode = Player.REPEAT_MODE_ALL
            routing.setLocalPlayer(replacement)

            assertThat(routing.localPlayer).isSameInstanceAs(replacement)
            assertThat(routing.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
        } finally {
            // Restore before releasing, or tearDown would release this wrapper's player twice.
            routing.setLocalPlayer(local)
            replacement.release()
        }
    }

    @Test
    fun swapping_to_the_same_player_is_a_no_op() = onMain {
        routing.setLocalPlayer(local)
        assertThat(routing.localPlayer).isSameInstanceAs(local)
    }
}
