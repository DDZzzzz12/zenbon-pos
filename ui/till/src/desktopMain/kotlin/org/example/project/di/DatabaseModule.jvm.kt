package org.example.project.di

import org.example.project.core.data.database.DatabaseDriverFactory
import org.example.project.core.data.database.secret.DesktopSecretCipher
import org.example.project.core.data.database.secret.SecretCipher
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop-specific database module.
 *
 * Provides the standard SQLite-backed database stack via commonDatabaseModule.
 */
actual val databaseModule: Module =
    module {
        // Non-bundled mode: SQLite via SQLDelight
        single { DatabaseDriverFactory() }
        // Seals the device token, the tokens and the remember-me password (see commonDatabaseModule's KeyValueStore).
        // fromOs() does no keychain I/O here; the keychain is read lazily on first use.
        single<SecretCipher> { DesktopSecretCipher.fromOs() }
        includes(commonDatabaseModule)
    }
