package org.example.project.di

import org.example.project.core.notification.DesktopNotificationService
import org.example.project.core.notification.PlatformNotificationService
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * JVM/Desktop-specific DI modules.
 *
 * Provides desktop-specific ViewModels for initialization screens.
 * Common modules (repositories, services, etc.) are already loaded via getAllModules().
 */
actual fun getPlatformModules(): List<Module> =
    listOf(
        jvmModule,
    )

/**
 * JVM-specific module for platform dependencies.
 */
private val jvmModule =
    module {
        single<PlatformNotificationService> { DesktopNotificationService() }
    }
