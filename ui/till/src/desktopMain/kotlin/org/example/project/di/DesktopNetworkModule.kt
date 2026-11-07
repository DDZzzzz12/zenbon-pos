package org.example.project.di

import org.example.project.core.auth.DesktopSessionProvider
import org.example.project.core.auth.SessionProvider
import org.example.project.core.data.provider.*
import org.example.project.core.data.provider.remote.*
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Desktop-specific Koin module.
 *
 * Registers desktop-only singletons that cannot live in the shared [NetworkModule]:
 * - [DesktopSessionProvider] — employee/device context for service calls
 * - [ApiClientRTableDataProvider] and other [DataProvider] implementations that
 *   route reads through API clients to the remote pos-server over HTTP
 *
 * HTTP client, [NotificationConnection], and all [ApiClient] bindings are
 * registered in [NetworkModule] and are shared across platforms.
 *
 * @since 1.0.0
 */
val desktopModule =
    module {

        // Session provider for employee/device context in service calls
        singleOf(::DesktopSessionProvider) bind SessionProvider::class

        // DataProvider implementations — route reads through API clients → HTTP → PostgreSQL
        single<RTableDataProvider> { ApiClientRTableDataProvider(get()) }
        single<ProductDataProvider> { ApiClientProductDataProvider(get()) }
        single<ProductCategoryDataProvider> { ApiClientProductCategoryDataProvider(get()) }
        single<ProductExtraDataProvider> { ApiClientProductExtraDataProvider(get()) }
        single<ProductExtrasAssignmentDataProvider> { ApiClientProductExtrasAssignmentDataProvider(get()) }
        single<PaymentTypeDataProvider> { ApiClientPaymentTypeDataProvider(get()) }
        single<RoomElementDataProvider> { ApiClientRoomElementDataProvider(get()) }
        single<ChoiceGroupDataProvider> { ApiClientChoiceGroupDataProvider(get(), get()) }
    }
