package com.theveloper.pixelplay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.StatsHistoryMigrationWorker
import com.theveloper.pixelplay.data.diagnostics.AdvancedPerformanceDiagnosticsController
import com.theveloper.pixelplay.data.repository.ArtistImageRepository
import com.theveloper.pixelplay.presentation.viewmodel.LibraryStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.ThemeStateHolder
import com.theveloper.pixelplay.utils.AlbumArtCacheManager
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.CrashHandler
import com.theveloper.pixelplay.utils.AppLocaleManager
import com.theveloper.pixelplay.utils.MediaMetadataRetrieverPool
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class PixelPlayApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    @Inject
    lateinit var navidromeCoilFetcherFactory: dagger.Lazy<com.theveloper.pixelplay.data.image.NavidromeCoilFetcher.Factory>

    @Inject
    lateinit var localArtworkCoilFetcherFactory: dagger.Lazy<com.theveloper.pixelplay.data.image.LocalArtworkCoilFetcher.Factory>

    @Inject
    lateinit var themeStateHolder: dagger.Lazy<ThemeStateHolder>

    @Inject
    lateinit var artistImageRepository: dagger.Lazy<ArtistImageRepository>

    @Inject
    lateinit var libraryStateHolder: dagger.Lazy<LibraryStateHolder>

    @Inject
    lateinit var userPreferencesRepository: dagger.Lazy<UserPreferencesRepository>

    @Inject
    lateinit var advancedPerformanceDiagnosticsController: dagger.Lazy<AdvancedPerformanceDiagnosticsController>

    @Inject
    lateinit var connectivityStateHolder: dagger.Lazy<com.theveloper.pixelplay.presentation.viewmodel.ConnectivityStateHolder>

    @Inject
    lateinit var jamManager: dagger.Lazy<com.theveloper.pixelplay.data.jam.JamManager>

    @Inject
    lateinit var navidromeRepository: dagger.Lazy<NavidromeRepository>

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // AÑADE EL COMPANION OBJECT
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "pixelplay_music_channel"
        lateinit var instance: PixelPlayApplication
            private set
    }

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            libraryStateHolder.get().restoreAfterTrimIfNeeded()
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        instance = this
        super.onCreate()

        // Benchmark variant intentionally restarts/kills app process during tests.
        // Avoid persisting those events as user-facing crash reports.
        if (BuildConfig.BUILD_TYPE != "benchmark") {
            CrashHandler.install(this)
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release tree: only WARN/ERROR/WTF - no DEBUG/VERBOSE/INFO
            Timber.plant(ReleaseTree())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "PixelPlayer Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        advancedPerformanceDiagnosticsController.get().start(startupScope)

        // App-scope connectivity monitoring so effectiveOffline is correct before any screen opens
        // and stays live even when the player ViewModel is cleared. initialize() is idempotent.
        connectivityStateHolder.get().initialize()

        // Jam host role: advertises + accepts commands only while actually playing and opted in.
        jamManager.get().start()

        startupScope.launch {
            AlbumArtUtils.migrateLegacyCacheLocation(this@PixelPlayApplication)
            val savedLimit = runCatching {
                userPreferencesRepository.get().albumArtCacheLimitMbFlow.first()
            }.getOrNull()
            if (savedLimit != null) {
                AlbumArtCacheManager.configuredCacheLimitMb = savedLimit.toLong()
            }
        }

        // One-time upload of this device's pre-gateway-Stats local history, once a gateway
        // account is signed in. Covers both "just logged in" and "was already logged in when
        // this update landed" — collecting a StateFlow immediately replays its current value.
        startupScope.launch {
            // StateFlow is already conflated/distinct-by-equality — no distinctUntilChanged
            // needed (applying it to a StateFlow is a compile-time error in this project's
            // coroutines version).
            navidromeRepository.get().isLoggedInFlow
                .collect { loggedIn ->
                    if (loggedIn && !userPreferencesRepository.get().isStatsMigrationCompleted()) {
                        StatsHistoryMigrationWorker.enqueue(
                            WorkManager.getInstance(this@PixelPlayApplication)
                        )
                    }
                }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoader.get().newBuilder()
            .components {
                add(localArtworkCoilFetcherFactory.get())
                add(navidromeCoilFetcherFactory.get())
            }
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        imageLoader.get().memoryCache?.trimMemory(level)

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            themeStateHolder.get().trimMemory(level)
        }

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            artistImageRepository.get().clearCache()
            MediaMetadataRetrieverPool.clear()
        }

        libraryStateHolder.get().trimMemory(level)

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            imageLoader.get().memoryCache?.clear()
        }
    }

    // 3. Sobrescribe el método para proveer la configuración de WorkManager
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
