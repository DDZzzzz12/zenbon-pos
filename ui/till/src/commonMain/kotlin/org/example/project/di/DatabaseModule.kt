package org.example.project.di

import app.cash.sqldelight.db.SqlDriver
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.startupSessionState
import org.example.project.core.data.database.*
import org.example.project.core.data.database.secret.LocalSecretsMigration
import org.example.project.core.data.database.secret.SecretProtectingKeyValueStore
import org.example.project.core.data.local.DecimalColumnAdapter
import org.example.project.core.data.provider.*
import org.example.project.core.data.provider.local.*
import org.example.project.database.Local_choice_group_options
import org.example.project.database.Local_product_extras
import org.example.project.database.Local_products
import org.example.project.database.PosDatabase
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

expect val databaseModule: Module

/**
 * Common database module.
 *
 * Registers PosDatabase, all DataManagers, LocalDatabaseManager with
 * SqlDelightKeyValueStore, and Local DataProvider implementations.
 */
val commonDatabaseModule =
    module {
        // The one SQLite driver: PosDatabase runs on it, and LocalSecretsMigration sends its VACUUM through it.
        single<SqlDriver> { get<DatabaseDriverFactory>().createDriver() }

        // Create PosDatabase instance from the driver above.
        // local_products.price is exact decimal TEXT — see DecimalColumnAdapter and 7.sqm.
        single {
            PosDatabase(
                driver = get(),
                local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
                local_product_extrasAdapter =
                    Local_product_extras.Adapter(priceAdapter = DecimalColumnAdapter),
                local_choice_group_optionsAdapter =
                    Local_choice_group_options.Adapter(price_deltaAdapter = DecimalColumnAdapter),
            )
        }

        // This module is only included by platforms that actually build a PosDatabase.
        single { LocalCacheAvailability(isAvailable = true) }

        // KeyValueStore backed by SQLDelight.
        // The device token, the tokens and the remember-me password are sealed with the platform SecretCipher
        // (Android Keystore / OS keychain), bound by the Android and desktop databaseModule. The wasm
        // databaseModule does not include this module.
        single { SecretProtectingKeyValueStore(SqlDelightKeyValueStore(get()), get()) } bind KeyValueStore::class

        // Seals plaintext an older build left under a secret key and wipes it from the database file. Started after
        // initKoin by launchLocalSecretsMigration. Explicit arguments: `singleOf(::LocalSecretsMigration)` would resolve
        // every constructor parameter with get(), the defaulted IO dispatcher included, and no dispatcher is bound.
        single { LocalSecretsMigration(store = get(), driver = get()) }

        // Token persistence over the same key-value store
        single { TokenStore(get()) }

        // App-level session state. WIPE: the cashier terminal requires a fresh login every
        // launch — see StartupPolicy for why a resumed session is wrong on a fiscal terminal. The
        // wipe also drops the HTTP client's cached bearer and ends a notification stream of the wiped
        // session; see startupSessionState.
        single { startupSessionState(get(), get(), get()) }

        // LocalDatabaseManager uses KeyValueStore. It reads nothing when built — Koin first resolves it on the UI
        // thread — so the sealed device token is loaded on demand, off that thread, through the loader installed here.
        single { LocalDatabaseManager(get()).also { installDeviceTokenLoader(it) } }

        // SyncDataManager uses the shared PosDatabase instance and the KeyValueStore above
        single { SyncDataManager(get(), get()) }

        single { ProductExtrasAssignmentDataManager(get()) }

        single { ProductCategoryDataManager(get()) }

        single { ProductDataManager(get()) }

        single { ProductExtraDataManager(get()) }

        single { PaymentTypeDataManager(get()) }

        single { ShopDataManager(get()) }

        single { ChoiceGroupDataManager(get()) }

        single { ChoiceGroupOptionDataManager(get()) }

        single { ProductChoiceGroupDataManager(get()) }

        single { RTableDataManager(get()) }

        single { RoomElementDataManager(get()) }

        // App-wide cache-change signal bus
        single {
            org.example.project.core.data.cache
                .CacheChangeBus()
        }

        // Write-through writer for live table-status pushes
        single {
            org.example.project.core.data.cache
                .TableStatusCacheWriter(get(), get())
        }

        // Local DataProvider implementations (default for non-bundled)
        single<ProductDataProvider> { LocalProductDataProvider(get(), get()) }
        single<ProductCategoryDataProvider> { LocalProductCategoryDataProvider(get()) }
        single<ProductExtraDataProvider> { LocalProductExtraDataProvider(get()) }
        single<ProductExtrasAssignmentDataProvider> { LocalProductExtrasAssignmentDataProvider(get()) }
        single<PaymentTypeDataProvider> { LocalPaymentTypeDataProvider(get()) }
        single<ChoiceGroupDataProvider> { LocalChoiceGroupDataProvider(get(), get(), get(), get()) }
        single<RTableDataProvider> { LocalRTableDataProvider(get(), get(), get()) }
        single<RoomElementDataProvider> { LocalRoomElementDataProvider(get(), get(), get()) }
    }
