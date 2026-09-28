package com.theveloper.pixelplay.utils

import android.graphics.Bitmap

/**
 * Center-crops artwork to a square before it reaches a system surface that expects one.
 *
 * Media surfaces — the One UI player, the lock screen, the notification, Android Auto — all lay
 * artwork out in a square slot. Handed a 16:9 gateway thumbnail they pad it out with blurred bars
 * rather than shrinking the slot. Cropping to fill (what Spotify does) means they get a square and
 * have nothing to pad. The app's own UI already does this with `ContentScale.Crop`; this is the
 * same rule applied to the bitmaps and files we hand out.
 */
object ArtworkSquareCrop {

    /**
     * Images within this fraction of square pass through untouched — a 1000x998 cover is not worth
     * a bitmap copy and a re-encode to shave two pixels.
     */
    private const val ASPECT_TOLERANCE = 0.01f

    /** The centered square window to take, or null when the source is already square enough. */
    fun cropWindow(width: Int, height: Int): CropWindow? {
        if (width <= 0 || height <= 0) return null
        val size = minOf(width, height)
        val longest = maxOf(width, height)
        if (longest - size <= longest * ASPECT_TOLERANCE) return null
        return CropWindow(
            left = (width - size) / 2,
            top = (height - size) / 2,
            size = size
        )
    }

    /** The source bitmap itself when it is already square, otherwise a new centered square copy. */
    fun square(source: Bitmap): Bitmap {
        val window = cropWindow(source.width, source.height) ?: return source
        return Bitmap.createBitmap(source, window.left, window.top, window.size, window.size)
    }

    data class CropWindow(val left: Int, val top: Int, val size: Int)
}
