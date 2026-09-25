package com.theveloper.pixelplay.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ArtworkSquareCropTest {

    @Test
    fun squareArtwork_isLeftAlone() {
        assertThat(ArtworkSquareCrop.cropWindow(1000, 1000)).isNull()
    }

    @Test
    fun nearlySquareArtwork_isLeftAlone() {
        // Within the tolerance: not worth a copy and a re-encode to shave two pixels.
        assertThat(ArtworkSquareCrop.cropWindow(1000, 998)).isNull()
    }

    @Test
    fun wideThumbnail_cropsToCenteredSquareOfTheShortSide() {
        val window = ArtworkSquareCrop.cropWindow(1280, 720)

        assertThat(window).isEqualTo(ArtworkSquareCrop.CropWindow(left = 280, top = 0, size = 720))
    }

    @Test
    fun tallArtwork_cropsToCenteredSquareOfTheShortSide() {
        val window = ArtworkSquareCrop.cropWindow(600, 900)

        assertThat(window).isEqualTo(ArtworkSquareCrop.CropWindow(left = 0, top = 150, size = 600))
    }

    @Test
    fun oddLeftoverPixel_keepsTheCropInsideTheSource() {
        // 11px of overhang splits unevenly; the window must still fit, or createBitmap throws.
        val window = ArtworkSquareCrop.cropWindow(111, 100)!!

        assertThat(window.size).isEqualTo(100)
        assertThat(window.left + window.size).isAtMost(111)
        assertThat(window.top + window.size).isAtMost(100)
    }

    @Test
    fun undecodableBounds_areNotTreatedAsCroppable() {
        // BitmapFactory reports -1 x -1 when it cannot read the file's header.
        assertThat(ArtworkSquareCrop.cropWindow(-1, -1)).isNull()
        assertThat(ArtworkSquareCrop.cropWindow(0, 0)).isNull()
    }
}
