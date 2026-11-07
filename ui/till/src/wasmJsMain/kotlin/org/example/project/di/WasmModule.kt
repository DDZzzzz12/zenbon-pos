package org.example.project.di

import org.example.project.core.auth.SessionProvider
import org.example.project.core.auth.WasmSessionProvider
import org.example.project.core.data.domain.impl.service.NoLocalCacheSyncService
import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.data.provider.*
import org.example.project.core.data.provider.remote.*
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.notification.WasmNotificationService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Browser-specific Koin module.
 *
 * The wasm build constructs no `PosDatabase`, so the `Local*` providers in `commonDatabaseModule`
 * are never registered — see `DatabaseModule.wasmJs.kt`. These `ApiClient*` bindings are therefore
 * the only implementations of the eight `DataProvider` interfaces here, and every read goes to
 * pos-server over HTTP.
 */
val wasmModule =
    module {

        // Session provider for employee/device context in service calls
        singleOf(::WasmSessionProvider) bind SessionProvider::class

        // Notification service — pending-navigation handoff only; the live connection is
        // NotificationViewModel's, and there is no background service in a tab.
        single<PlatformNotificationService> { WasmNotificationService() }

        // There is no local cache to synchronise here, so this overrides `clientServiceModule`'s
        // SyncServiceImpl, which is constructed from six PosDatabase-backed *DataManagers.
        single<SyncService> { NoLocalCacheSyncService() }

        // DataProvider implementations — route reads through API clients → HTTP → pos-server
        single<RTableDataProvider> { ApiClientRTableDataProvider(get()) }
        single<ProductDataProvider> { ApiClientProductDataProvider(get()) }
        single<ProductCategoryDataProvider> { ApiClientProductCategoryDataProvider(get()) }
        single<ProductExtraDataProvider> { ApiClientProductExtraDataProvider(get()) }
        single<ProductExtrasAssignmentDataProvider> { ApiClientProductExtrasAssignmentDataProvider(get()) }
        single<PaymentTypeDataProvider> { ApiClientPaymentTypeDataProvider(get()) }
        single<RoomElementDataProvider> { ApiClientRoomElementDataProvider(get()) }
        single<ChoiceGroupDataProvider> { ApiClientChoiceGroupDataProvider(get(), get()) }
    }
