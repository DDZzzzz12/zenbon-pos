package org.example.project.di

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration

/**
 * Common Koin initialization for all platforms.
 *
 * Each platform can extend this with platform-specific configuration.
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(getAllModules())
        modules(getPlatformModules())
    }

/**
 * Returns all common modules that should be loaded across all platforms.
 *
 * Modules are loaded in dependency order to avoid circular dependencies.
 * Platform-specific services are loaded early to provide foundation for other modules.
 * Note: Desktop initialization is handled by DesktopServicesModule, not included here.
 */
fun getAllModules(bundlesServer: Boolean = false): List<Module> =
    if (bundlesServer) {
        databaseModule +
            listOf(
                clientServiceModule, // Services + repositories (depends on network and database)
                authModule, // Authentication (depends on network, database, and services)
                viewModelModule, // ViewModels (depends on repositories and services)
                utilModule, // Utilities (no dependencies)
            )
    } else {
        networkModule + databaseModule +
            listOf(
                clientServiceModule, // Services + repositories (depends on network and database)
                authModule, // Authentication (depends on network, database, and services)
                viewModelModule, // ViewModels (depends on repositories and services)
                utilModule, // Utilities (no dependencies)
            )
    }

/**
 * Platform-specific modules can be added by extending this list.
 */
expect fun getPlatformModules(): List<Module>
