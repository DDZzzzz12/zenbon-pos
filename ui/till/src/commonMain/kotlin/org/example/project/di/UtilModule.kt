package org.example.project.di

import co.touchlab.kermit.Logger
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.database.SyncDataManager
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.createAppUpdatePlatform
import org.example.project.features.appupdate.domain.AppUpdatePlatform
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val utilModule =
    module {

        singleOf(::LanguageManager)

        // Android installs APKs; every other platform gets a no-op that reports no installed build.
        single<AppUpdatePlatform> { createAppUpdatePlatform() }

        single { SettingManager(get(), getOrNull<SyncDataManager>()) }

        /**
         * Kermit Logger for application-wide logging.
         *
         * Provides structured logging with configurable severity levels
         * and platform-specific output (console, logcat, etc.).
         */
        single<Logger> {
            Logger.withTag("POSApp")
        }
    }
