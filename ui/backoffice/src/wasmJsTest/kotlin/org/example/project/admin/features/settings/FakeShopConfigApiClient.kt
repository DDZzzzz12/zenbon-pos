package org.example.project.admin.features.settings

import org.example.project.core.data.remote.AccountingConfigurationApiClient
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.network.error.NetworkApiException

/**
 * Serves the four shop-configuration calls the two recovered settings sections use, plus the
 * directory listing behind the storage-path browser. Every other member throws, so a test that
 * accidentally reaches one fails loudly instead of quietly passing.
 *
 * [vatMappingRequests] and [storageRequests] are what prove the wire payload, not just the state.
 */
class FakeShopConfigApiClient(
    private var storedMapping: VatMappingConfig = VatMappingConfig.getDefault(),
    private var storedStorage: ShopConfigurationDto.DocumentStorageConfigResponseDto =
        ShopConfigurationDto.DocumentStorageConfigResponseDto(
            shopId = 1L,
            path = "/var/pos/documents",
            maxUploadSizeMb = 20,
            defaultDocumentTypes = listOf("INVOICE", "RECEIPT"),
            additionalDocumentTypes = listOf("Mietvertrag"),
        ),
    private val listings: Map<String?, BackupRestoreDto.DirectoryListingDto> = emptyMap(),
    private val onCall: (() -> Unit)? = null,
    private val failingListPaths: Set<String> = emptySet(),
) : ShopConfigurationApiClient,
    AccountingConfigurationApiClient,
    EcrConfigurationApiClient {
    val vatMappingRequests: MutableList<ShopConfigurationDto.UpdateVatMappingRequestDto> = mutableListOf()
    val storageRequests: MutableList<ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto> = mutableListOf()
    val listedPaths: MutableList<String?> = mutableListOf()

    override suspend fun getVatMapping(shopId: Long): ShopConfigurationDto.VatMappingResponseDto {
        onCall?.invoke()
        return ShopConfigurationDto.VatMappingResponseDto(
            shopId = shopId,
            vatMapping = storedMapping,
            isValid = storedMapping.isValid(),
            description = "stored",
        )
    }

    override suspend fun updateVatMapping(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVatMappingRequestDto,
    ): ShopConfigurationDto.VatMappingResponseDto {
        onCall?.invoke()
        vatMappingRequests += request
        storedMapping = request.vatMapping
        return ShopConfigurationDto.VatMappingResponseDto(
            shopId = shopId,
            vatMapping = storedMapping,
            isValid = storedMapping.isValid(),
            description = "stored",
        )
    }

    override suspend fun getDocumentStorageConfig(shopId: Long): ShopConfigurationDto.DocumentStorageConfigResponseDto {
        onCall?.invoke()
        return storedStorage
    }

    override suspend fun updateDocumentStorageConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto,
    ): ShopConfigurationDto.DocumentStorageConfigResponseDto {
        onCall?.invoke()
        storageRequests += request
        storedStorage =
            storedStorage.copy(
                path = request.path,
                maxUploadSizeMb = request.maxUploadSizeMb,
                additionalDocumentTypes = request.additionalDocumentTypes,
            )
        return storedStorage
    }

    override suspend fun listDirectories(path: String?): BackupRestoreDto.DirectoryListingDto {
        onCall?.invoke()
        listedPaths += path
        if (path in failingListPaths) throw NetworkApiException(RuntimeException("404 not found"))
        return listings[path]
            ?: BackupRestoreDto.DirectoryListingDto(currentPath = path ?: "/", parentPath = null, entries = emptyList(), canWrite = false)
    }

    override suspend fun getDatevXmlConfig(shopId: Long) = throw NotImplementedError()

    override suspend fun updateDatevXmlConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getDatevAccountMapping(shopId: Long) = throw NotImplementedError()

    override suspend fun updateDatevAccountMapping(
        shopId: Long,
        request: DatevAccountMappingDto.MappingDto,
    ) = throw NotImplementedError()

    override suspend fun getVoucherConfiguration(shopId: Long) = throw NotImplementedError()

    override suspend fun updateVoucherConfiguration(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVoucherConfigRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getPrintSettings(shopId: Long) = ShopConfigurationDto.PrintSettingsResponseDto(shopId = shopId, enabled = true)

    override suspend fun updatePrintSettings(
        shopId: Long,
        request: ShopConfigurationDto.UpdatePrintSettingsRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getOrderRules(shopId: Long) = throw NotImplementedError()

    override suspend fun updateOrderRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateOrderRulesRequestDto,
    ) = throw NotImplementedError()

    /** What `GET/PUT browser-stations` holds; [browserStationsError] makes either call throw (e.g. a 403). */
    var browserRoles: List<String> = emptyList()
    var browserStationsError: Throwable? = null
    val browserStationsRequests: MutableList<ShopConfigurationDto.UpdateBrowserStationsRequestDto> = mutableListOf()

    override suspend fun getBrowserStations(shopId: Long): ShopConfigurationDto.BrowserStationsResponseDto {
        browserStationsError?.let { throw it }
        return ShopConfigurationDto.BrowserStationsResponseDto(shopId = shopId, roles = browserRoles)
    }

    override suspend fun updateBrowserStations(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBrowserStationsRequestDto,
    ): ShopConfigurationDto.BrowserStationsResponseDto {
        browserStationsError?.let { throw it }
        browserStationsRequests += request
        browserRoles = request.roles
        return ShopConfigurationDto.BrowserStationsResponseDto(shopId = shopId, roles = browserRoles)
    }

    override suspend fun getBillingRules(shopId: Long) = throw NotImplementedError()

    override suspend fun updateBillingRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBillingRulesRequestDto,
    ) = throw NotImplementedError()
}
