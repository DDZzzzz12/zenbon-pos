package org.example.project.di

import org.example.project.core.notification.AndroidNotificationService
import org.example.project.core.notification.AppLifecycleObserver
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.notification.PosNotificationService
import org.example.project.ui.auth.SessionState
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android-specific DI modules.
 */
actual fun getPlatformModules(): List<Module> =
    listOf(
        androidModule,
    )

/**
 * Android-specific module for platform dependencies.
 */
val androidModule =
    module {
        single {
            val context = androidContext()
            // SessionState is resolved lazily, and only for a service Android timed out: see restartIfTimedOut.
            AppLifecycleObserver(
                onEnterForeground = { PosNotificationService.restartIfTimedOut(context) { get<SessionState>().status } },
            )
        }
        single<PlatformNotificationService> { AndroidNotificationService(androidContext()) }
    }
