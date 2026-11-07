package org.example.project.di

import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.startupSessionState
import org.example.project.core.data.cache.CacheChangeBus
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.LocalCacheAvailability
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.database.LocalStorageKeyValueStore
import org.example.project.core.data.database.ShopDataManager
import org.example.project.core.data.database.installDeviceTokenLoader
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The wasm build's answer to `databaseModule` — and it contains no database.
 *
 * `commonDatabaseModule` is deliberately **not** included: it builds `PosDatabase` from a
 * `SqlDriver`, and SQLDelight's web driver would require `generateAsync = true`, which changes the
 * generated query API for Android and desktop too. A preview must not be able to refactor the till.
 *
 * Every read therefore goes to the server through the `ApiClient*` providers in [wasmModule].
 */
actual val databaseModule: Module =
    module {
        // No PosDatabase here, so nothing caches server data — the UI hides the controls that
        // would only operate on a cache.
        single { LocalCacheAvailability(isAvailable = false) }

        single<KeyValueStore> { LocalStorageKeyValueStore() }
        single { TokenStore(get()) }
        single { startupSessionState(get(), get(), get()) }
        // Reads nothing when built; the device token is loaded when a request first needs it, as on the till.
        single { LocalDatabaseManager(get()).also { installDeviceTokenLoader(it) } }

        // ShopDataManager is the one *DataManager that never needed PosDatabase — it is
        // KeyValueStore-backed — so shop info works here exactly as it does elsewhere.
        single { ShopDataManager(get()) }

        // Pure in-memory event bus with no database behind it — the ViewModels that observe
        // cache changes still need it even though nothing writes a cache here.
        single { CacheChangeBus() }
    }
