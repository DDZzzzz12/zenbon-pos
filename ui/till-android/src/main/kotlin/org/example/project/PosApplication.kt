package org.example.project

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import org.example.project.core.device.DeviceInfoProvider
import org.example.project.core.notification.AppLifecycleObserver
import org.example.project.core.notification.NotificationChannelManager
import org.example.project.core.utils.initializeAppTranslations
import org.example.project.di.initKoin
import org.example.project.di.launchLocalSecretsMigration
import org.koin.android.ext.koin.androidContext

class PosApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize device info provider for terminal registration
        DeviceInfoProvider.initialize(this)

        // Here, not only in the Activity's LanguageProvider: the system can restart the sticky
        // PosNotificationService in a new process without any Activity, and it needs localized text.
        initializeAppTranslations()

        val koinApplication =
            initKoin {
                androidContext(this@PosApplication)
            }
        // Seals any secret an older build left in plaintext and wipes it from the database file, off the main thread.
        koinApplication.koin.launchLocalSecretsMigration()

        NotificationChannelManager.createChannels(this)

        val appLifecycleObserver: AppLifecycleObserver =
            org.koin.core.context.GlobalContext
                .get()
                .get()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
    }
}
