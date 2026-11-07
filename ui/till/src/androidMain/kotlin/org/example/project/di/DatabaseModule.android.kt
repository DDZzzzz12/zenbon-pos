package org.example.project.di

import org.example.project.core.data.database.DatabaseDriverFactory
import org.example.project.core.data.database.secret.AndroidKeystoreSecretCipher
import org.example.project.core.data.database.secret.SecretCipher
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val databaseModule: Module =
    module {
        single { DatabaseDriverFactory(androidContext()) }
        // Seals the device token, the tokens and the remember-me password (see commonDatabaseModule's KeyValueStore).
        single<SecretCipher> { AndroidKeystoreSecretCipher() }
        includes(commonDatabaseModule)
    }
