package com.aether.memoryeditor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.aether.memoryeditor.di.AppModule
import com.aether.memoryeditor.util.RootManager
import timber.log.Timber

class AetherApplication : Application() {

    companion object {
        lateinit var instance: AetherApplication
            private set
    }

    val appModule: AppModule by lazy { AppModule(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Initialize root manager
        RootManager.initialize()

        // Create notification channels
        createNotificationChannels()

        Timber.i("Aether Memory Editor initialized")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Main service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            // Scan progress channel
            val scanChannel = NotificationChannel(
                CHANNEL_ID_SCAN,
                "Scan Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Memory scan progress notifications"
                setShowBadge(false)
            }

            // Script execution channel
            val scriptChannel = NotificationChannel(
                CHANNEL_ID_SCRIPT,
                "Script Execution",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Lua script execution notifications"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(
                listOf(serviceChannel, scanChannel, scriptChannel)
            )
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        RootManager.shutdown()
    }
}

// Notification channel IDs
const val CHANNEL_ID_SERVICE = "aether_service"
const val CHANNEL_ID_SCAN = "aether_scan"
const val CHANNEL_ID_SCRIPT = "aether_script"
