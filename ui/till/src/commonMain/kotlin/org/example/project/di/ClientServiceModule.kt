package org.example.project.di

import org.example.project.core.data.domain.impl.service.ClientOrderServiceImpl
import org.example.project.core.data.domain.impl.service.FloorPlanSyncServiceImpl
import org.example.project.core.data.domain.impl.service.InventoryServiceImpl
import org.example.project.core.data.domain.impl.service.PreferenceSyncServiceImpl
import org.example.project.core.data.domain.impl.service.SyncServiceImpl
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.domain.service.FloorPlanSyncService
import org.example.project.core.data.domain.service.InventoryService
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.data.domain.service.TerminalRegistrationService
import org.example.project.core.data.domain.service.TerminalRegistrationServiceImpl
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val clientServiceModule =
    module {
        singleOf(::FloorPlanSyncServiceImpl) bind FloorPlanSyncService::class
        // Explicit rather than singleOf: the four *DataManager parameters are optional, and
        // singleOf would resolve every constructor parameter with get() and throw on a client that
        // keeps no local cache (the wasm browser preview). The API clients stay required — the
        // server calls are the point of this service; only the cache write-through is optional.
        single {
            InventoryServiceImpl(
                productApiClient = get(),
                productCategoryApiClient = get(),
                productExtrasApiClient = get(),
                productExtrasAssignmentApiClient = get(),
                productDataManager = getOrNull(),
                productCategoryDataManager = getOrNull(),
                productExtraDataManager = getOrNull(),
                productExtrasAssignmentDataManager = getOrNull(),
            )
        } bind InventoryService::class

        /**
         * Preference sync service for syncing shop info and configuration
         * to local KeyValueStore. Works in both standalone and bundled modes.
         */
        singleOf(::PreferenceSyncServiceImpl) bind PreferenceSyncService::class

        /**
         * Sync service for coordinating data synchronization between local storage and server.
         */
        singleOf(::SyncServiceImpl) bind SyncService::class

        /**
         * Client Order Service for order operations.
         */
        singleOf(::ClientOrderServiceImpl) bind ClientOrderService::class

        /**
         * Terminal Registration Service for multi-client DSFinV-K compliance.
         */
        singleOf(::TerminalRegistrationServiceImpl) bind TerminalRegistrationService::class
    }
