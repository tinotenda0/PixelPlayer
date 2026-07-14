package com.theveloper.pixelplay.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a rendered share-card bitmap to the cache dir and fires a share chooser.
 * Used by the lyrics card and stats recap card.
 */
object ShareCardUtils {

    fun writeCardToCache(
        context: Context,
        bitmap: Bitmap,
        cacheSubdir: String,
        fileNamePrefix: String
    ): Uri {
        val shareDir = File(context.cacheDir, cacheSubdir).apply { mkdirs() }
        // Clean up older cards so the cache doesn't accumulate.
        shareDir.listFiles()?.forEach { it.delete() }

        val file = File(shareDir, "${fileNamePrefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    fun shareImage(context: Context, uri: Uri, chooserTitle: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
        }
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }
}
