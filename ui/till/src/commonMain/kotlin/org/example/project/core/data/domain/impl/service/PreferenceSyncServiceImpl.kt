/**
 * Implementation of preference synchronization service.
 *
 * Fetches shop information and shop configuration from the server
 * and stores them in local KeyValueStore-backed managers. Has no
 * SQLite dependencies, so it works in both standalone and
 * bundled-server modes.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.domain.impl.service

import org.example.project.core.auth.SettingManager
import org.example.project.core.data.database.ShopDataManager
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.data.remote.ShopInfoApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * Concrete implementation of [PreferenceSyncService].
 *
 * Coordinates fetching shop info and voucher configuration from the
 * server, then persists them into [ShopDataManager] and [SettingManager].
 */
class PreferenceSyncServiceImpl(
    private val shopInfoApiClient: ShopInfoApiClient,
    private val ecrConfigurationApiClient: EcrConfigurationApiClient,
    private val fiscalConfigurationApiClient: FiscalConfigurationApiClient,
    private val shopDataManager: ShopDataManager,
    private val settingManager: SettingManager,
) : PreferenceSyncService {
    override suspend fun syncPreferences() {
        // Step 1: Sync shop information (throws on failure — caller wraps in runCatchingApi)
        val shopInfo = shopInfoApiClient.getShopInfoForSync()

        shopDataManager.saveShopInfo(
            shopId = shopInfo.shopId,
            shopName = shopInfo.shopName,
            companyName = shopInfo.companyName,
            shopAddress = shopInfo.shopAddress,
            shopCity = shopInfo.shopCity,
            zipCode = shopInfo.zipCode,
            shopCountry = shopInfo.shopCountry,
            shopPhone = shopInfo.shopPhone,
            shopEmail = shopInfo.shopEmail,
            shopTimezone = shopInfo.shopTimezone,
            shopWebsite = shopInfo.shopWebsite,
            restaurantMode = shopInfo.restaurantMode,
        )

        settingManager.setRestaurantMode(shopInfo.restaurantMode)
        settingManager.setShopLogoUrl(shopInfo.logoUrl)

        // Step 2: Sync shop configuration (failures are non-fatal — shop info already saved)
        syncShopConfiguration(shopInfo.shopId)
    }

    private suspend fun syncShopConfiguration(shopId: Long) {
        runCatchingApi { ecrConfigurationApiClient.getVoucherConfiguration(shopId) }
            .onSuccess { resp ->
                val voucherConfig = resp.voucherConfig
                settingManager.setVoucherSystemEnabled(voucherConfig.enabled)
                settingManager.setVoucherSource(voucherConfig.sources)
            }.onError { /* non-fatal */ }

        runCatchingApi { ecrConfigurationApiClient.getOrderRules(shopId) }
            .onSuccess { rules ->
                settingManager.setEmployeeSelectionOnFreeTable(rules.employeeSelectionOnFreeTable)
                settingManager.setRoundRobinAssignment(rules.roundRobinAssignment)
            }.onError { /* non-fatal */ }

        runCatchingApi { ecrConfigurationApiClient.getBillingRules(shopId) }
            .onSuccess { rules ->
                settingManager.setAllowCrossEmployeeBilling(rules.allowCrossEmployeeBilling)
            }.onError { /* non-fatal */ }

        // Non-fatal like the others: the server enforces the limit regardless, and a till that
        // cannot read it keeps its last synced value (or the 31-day default).
        runCatchingApi { fiscalConfigurationApiClient.getKassenbuchBelegAge(shopId) }
            .onSuccess { settingManager.setKassenbuchMaxBelegAgeDays(it.maxAgeDays) }
            .onError { /* non-fatal */ }
    }
}
