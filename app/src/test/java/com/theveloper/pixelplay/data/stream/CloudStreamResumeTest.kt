package com.theveloper.pixelplay.data.stream

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CloudStreamResumeTest {

    @Test
    fun openEndedRequest_resumesFromWhatHasBeenDelivered() {
        val header = CloudStreamResume.resumeRangeHeader(
            clientStart = 0L,
            clientEndInclusive = null,
            deliveredBytes = 524_288L
        )

        assertThat(header).isEqualTo("bytes=524288-")
    }

    @Test
    fun playerSuppliedOffset_isAddedToWhatHasBeenDelivered() {
        // ExoPlayer seeked, so its Range started at 1,000,000; 2,048 bytes of that are out.
        val header = CloudStreamResume.resumeRangeHeader(
            clientStart = 1_000_000L,
            clientEndInclusive = null,
            deliveredBytes = 2_048L
        )

        assertThat(header).isEqualTo("bytes=1002048-")
    }

    @Test
    fun boundedRequest_keepsItsOriginalEnd() {
        // Losing the end would overrun what the player asked for.
        val header = CloudStreamResume.resumeRangeHeader(
            clientStart = 100L,
            clientEndInclusive = 999L,
            deliveredBytes = 400L
        )

        assertThat(header).isEqualTo("bytes=500-999")
    }

    @Test
    fun suffixRange_isNotResumable() {
        // "bytes=-500" has no absolute start until the total length is known.
        assertThat(
            CloudStreamResume.canResume(isSuffixRange = true, deliveredBytes = 1_024L, attempt = 0)
        ).isFalse()
    }

    @Test
    fun nothingDeliveredYet_isLeftToOkHttpsOwnConnectionRetry() {
        assertThat(
            CloudStreamResume.canResume(isSuffixRange = false, deliveredBytes = 0L, attempt = 0)
        ).isFalse()
    }

    @Test
    fun partwayThrough_isResumable() {
        assertThat(
            CloudStreamResume.canResume(isSuffixRange = false, deliveredBytes = 1_024L, attempt = 0)
        ).isTrue()
    }

    @Test
    fun attemptsAreBounded() {
        assertThat(
            CloudStreamResume.canResume(
                isSuffixRange = false,
                deliveredBytes = 1_024L,
                attempt = CloudStreamResume.MAX_ATTEMPTS
            )
        ).isFalse()
    }

    @Test
    fun backoffBacksOffExponentially() {
        assertThat(CloudStreamResume.backoffMs(1)).isEqualTo(500L)
        assertThat(CloudStreamResume.backoffMs(2)).isEqualTo(1_000L)
        assertThat(CloudStreamResume.backoffMs(3)).isEqualTo(2_000L)
        assertThat(CloudStreamResume.backoffMs(4)).isEqualTo(4_000L)
    }

    @Test
    fun theWholeRetryWindowSpansASignalBlipNotAnOutage() {
        // Long enough to ride out a handover; short enough that a genuine outage falls through to
        // the player's own wait-for-network handling instead of being retried here.
        val totalMs = (1..CloudStreamResume.MAX_ATTEMPTS).sumOf { CloudStreamResume.backoffMs(it) }

        assertThat(totalMs).isAtLeast(5_000L)
        assertThat(totalMs).isAtMost(10_000L)
    }
}
