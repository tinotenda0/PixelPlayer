package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlaybackErrorRecoveryPolicyTest {

    @Test
    fun droppedConnection_retriesTheSameItemOnce() {
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            alreadyRetried = false,
            hasNextMediaItem = true,
            abandonedTracks = 0,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.RETRY)
    }

    @Test
    fun droppedConnection_skipsOnceTheItemHasUsedItsRetry() {
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            alreadyRetried = true,
            hasNextMediaItem = true,
            abandonedTracks = 0,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.SKIP_TO_NEXT)
    }

    @Test
    fun malformedContainer_skipsWithoutBurningARetry() {
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
            alreadyRetried = false,
            hasNextMediaItem = true,
            abandonedTracks = 0,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.SKIP_TO_NEXT)
    }

    @Test
    fun missingFile_skipsWithoutBurningARetry() {
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
            alreadyRetried = false,
            hasNextMediaItem = true,
            abandonedTracks = 0,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.SKIP_TO_NEXT)
    }

    @Test
    fun lastTrackInQueue_stopsInsteadOfSkipping() {
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_DECODING_FAILED),
            alreadyRetried = false,
            hasNextMediaItem = false,
            abandonedTracks = 0,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.STOP)
    }

    @Test
    fun lastTrackInQueue_stillGetsItsRetryBeforeStopping() {
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
            alreadyRetried = false,
            hasNextMediaItem = false,
            abandonedTracks = 0,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.RETRY)
    }

    @Test
    fun repeatedFailures_stopInsteadOfBurningThroughTheQueue() {
        // Three tracks given up on with nothing playing in between: the setup is broken, not the
        // track. Skipping on would walk the whole queue in seconds.
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            alreadyRetried = false,
            hasNextMediaItem = true,
            abandonedTracks = 3,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.STOP)
    }

    @Test
    fun aCoupleOfBadTracks_stillSkipOn() {
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
            alreadyRetried = false,
            hasNextMediaItem = true,
            abandonedTracks = 2,
            isOnline = true
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.SKIP_TO_NEXT)
    }

    @Test
    fun deadZone_holdsTheTrackInsteadOfSkipping() {
        // Driving through a tunnel: the track is fine, the connection is not.
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            alreadyRetried = true,
            hasNextMediaItem = true,
            abandonedTracks = 0,
            isOnline = false
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.WAIT_FOR_NETWORK)
    }

    @Test
    fun deadZone_holdsEvenOnceTheAbandonCapWouldHaveStopped() {
        // Otherwise a long dead zone still ends in silence, just a few tracks later.
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            alreadyRetried = true,
            hasNextMediaItem = true,
            abandonedTracks = 5,
            isOnline = false
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.WAIT_FOR_NETWORK)
    }

    @Test
    fun offlineButTheTrackIsGenuinelyBad_stillSkips() {
        // A malformed container will not decode when the signal comes back either.
        val recovery = resolvePlaybackErrorRecovery(
            error = error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
            alreadyRetried = false,
            hasNextMediaItem = true,
            abandonedTracks = 0,
            isOnline = false
        )

        assertThat(recovery).isEqualTo(PlaybackErrorRecovery.SKIP_TO_NEXT)
    }

    private fun error(errorCode: Int): PlaybackException =
        PlaybackException("test failure", null, errorCode)
}
