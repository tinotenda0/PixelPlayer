package com.theveloper.pixelplay.data.service.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.theveloper.pixelplay.MainActivity
import com.theveloper.pixelplay.R

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // The media notification while casting: full transport controls plus a
        // tap target that reopens PixelPlayer (whose own cast sheet drives the
        // session), instead of the bare play/stop pair.
        val notificationOptions = NotificationOptions.Builder()
            .setActions(
                listOf(
                    MediaIntentReceiver.ACTION_SKIP_PREV,
                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                    MediaIntentReceiver.ACTION_SKIP_NEXT,
                    MediaIntentReceiver.ACTION_STOP_CASTING
                ),
                intArrayOf(0, 1, 2)
            )
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            // Tapping the mini/expanded cast controller returns to the app.
            .setExpandedControllerActivityClassName(MainActivity::class.java.name)
            .build()

        // Configurable receiver: a Styled/Custom receiver app id set in
        // strings.xml brands the TV screen; empty falls back to Google's
        // Default Media Receiver. See res/values/strings.xml.
        val configuredReceiverId = context.getString(R.string.cast_receiver_application_id)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

        return CastOptions.Builder()
            .setReceiverApplicationId(configuredReceiverId)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
