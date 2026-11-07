package org.example.project.features.setup.data.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.features.scheduler.data.service.BuiltInJobsSeeder
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.service.ConfigurationService
import org.example.project.utils.dbQuery

/**
 * Thin facade over [IShopConfigurationManager] / [ConfigurationRepository] that
 * exposes the read/write paths needed by the scheduler executors and (later)
 * the configuration routes. This implementation duplicates the persistence
 * shape that lives inline in `ConfigurationRoutes.kt` — those routes will be
 * refactored to delegate here in a follow-up task.
 */
class ConfigurationServiceImpl(
    private val configManager: IShopConfigurationManager,
    private val configRepository: ConfigurationRepository,
    // Lazy provider — breaks the Koin constructor cycle
    // (ConfigurationServiceImpl <- BuiltInJobsSeeder <- ConfigurationService).
    private val seederProvider: () -> BuiltInJobsSeeder,
) : ConfigurationService {
    override suspend fun getDatevXmlConfig(shopId: Long): ShopConfigurationDto.DatevXmlConfigResponseDto =
        ShopConfigurationDto.DatevXmlConfigResponseDto(
            shopId = shopId,
            kassenName = ShopConfigurationManager.getDatevXmlKassenName(),
            exportPath = ShopConfigurationManager.getDatevXmlExportPath(),
            consultantNumber = ShopConfigurationManager.getDatevXmlConsultantNumber() ?: "",
            clientNumber = ShopConfigurationManager.getDatevXmlClientNumber() ?: "",
            sammeldebitor = ShopConfigurationManager.getDatevArSammeldebitor() ?: "",
            apExportMode = ShopConfigurationManager.getDatevApExportMode(),
            wirtschaftsjahrBeginn = ShopConfigurationManager.getDatevWirtschaftsjahrBeginn().toString(),
            sachkontenlaenge = ShopConfigurationManager.getDatevSachkontenlaenge(),
            festschreibung = ShopConfigurationManager.getDatevFestschreibung(),
            enabled = ShopConfigurationManager.getDatevAutoEnabled(),
            kassenbuchCadence = ShopConfigurationManager.getDatevKassenbuchCadence(),
            apLedgerCadence = ShopConfigurationManager.getDatevApLedgerCadence(),
            buchungsstapelCadence = ShopConfigurationManager.getDatevBuchungsstapelCadence(),
            runHour = ShopConfigurationManager.getDatevRunHour(),
        )

    override suspend fun updateDatevXmlConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
    ): ShopConfigurationDto.DatevXmlConfigResponseDto {
        request.validate()

        // Mirror the JSON shape produced by ConfigurationRoutes.kt PUT /datev-xml — serialized,
        // never interpolated, because most of these fields are operator free text and a quote in
        // any of them would break out of the JSON string literal.
        val configJson =
            Json.encodeToString(
                ShopConfigurationDto.DatevXmlConfig.serializer(),
                ShopConfigurationDto.DatevXmlConfig(
                    enabled = request.enabled,
                    kassenName = request.kassenName,
                    exportPath = request.exportPath,
                    consultantNumber = request.consultantNumber,
                    clientNumber = request.clientNumber,
                    apExportMode = request.apExportMode,
                    wirtschaftsjahrBeginn = request.wirtschaftsjahrBeginn,
                    sachkontenlaenge = request.sachkontenlaenge,
                    festschreibung = request.festschreibung,
                    kassenbuchCadence = request.kassenbuchCadence,
                    apLedgerCadence = request.apLedgerCadence,
                    buchungsstapelCadence = request.buchungsstapelCadence,
                    runHour = request.runHour,
                ),
            )

        dbQuery {
            configRepository.setSetting(
                shopId = shopId,
                configKey = ConfigKey.DATEV_XML_CONFIG,
                value = configJson,
                description = "DATEV XML Schnittstelle configuration",
            )
            // Sammeldebitor is a separate flat setting (read by the AR export via
            // getDatevArSammeldebitor), not part of the DATEV_XML_CONFIG blob. Blank = unconfigured.
            configRepository.setSetting(
                shopId = shopId,
                configKey = ConfigKey.DATEV_AR_SAMMELDEBITOR,
                value = request.sammeldebitor.trim(),
                description = "DATEV Debitoren-Sammeldebitor (Personenkonto)",
            )
        }
        configManager.refreshConfiguration()
        dbQuery { seederProvider().seedDatev() }

        return ShopConfigurationDto.DatevXmlConfigResponseDto(
            shopId = shopId,
            kassenName = request.kassenName,
            exportPath = request.exportPath,
            consultantNumber = request.consultantNumber,
            clientNumber = request.clientNumber,
            sammeldebitor = request.sammeldebitor.trim(),
            apExportMode = request.apExportMode,
            wirtschaftsjahrBeginn = request.wirtschaftsjahrBeginn,
            sachkontenlaenge = request.sachkontenlaenge,
            festschreibung = request.festschreibung,
            enabled = request.enabled,
            kassenbuchCadence = request.kassenbuchCadence,
            apLedgerCadence = request.apLedgerCadence,
            buchungsstapelCadence = request.buchungsstapelCadence,
            runHour = request.runHour,
        )
    }

    override suspend fun getBackupConfig(shopId: Long): BackupRestoreDto.BackupConfigDto =
        BackupRestoreDto.BackupConfigDto(
            defaultBackupPath = configManager.getDefaultBackupPath(),
            lastBackupAt = configManager.getLastBackupAt(),
            autoEnabled = configManager.getBackupAutoEnabled(),
            cadence = configManager.getBackupCadence(),
            retentionCount = configManager.getBackupRetentionCount(),
            runHour = configManager.getBackupRunHour(),
            maxUploadBytes = configManager.getBackupMaxUploadBytes(),
            copyTo = configManager.getBackupCopyTarget(),
            copyRetentionCount = configManager.getBackupCopyRetentionCount(),
            lastCopyAt = configManager.getLastBackupCopyAt(),
            lastCopyStatus = configManager.getLastBackupCopyStatus(),
            lastCopyError = configManager.getLastBackupCopyError(),
        )

    override suspend fun touchLastBackupAt(
        shopId: Long,
        at: String,
    ) {
        dbQuery {
            val existingJson = configRepository.getSetting(shopId, ConfigKey.BACKUP_CONFIG.key) as? JsonObject
            val updatedJson =
                buildJsonObject {
                    // Preserve every existing field, then overwrite lastBackupAt.
                    existingJson?.forEach { (k, v) -> put(k, v) }
                    put("lastBackupAt", at)
                }
            configRepository.setSetting(
                shopId = shopId,
                configKey = ConfigKey.BACKUP_CONFIG,
                value = Json.encodeToString(JsonObject.serializer(), updatedJson),
            )
        }
        configManager.refreshConfiguration()
    }

    override suspend fun recordBackupCopy(
        shopId: Long,
        at: String?,
        status: BackupCopyStatus,
        error: String?,
    ) {
        dbQuery {
            val existingJson = configRepository.getSetting(shopId, ConfigKey.BACKUP_CONFIG.key) as? JsonObject
            val updatedJson =
                buildJsonObject {
                    existingJson?.forEach { (k, v) -> put(k, v) }
                    // lastCopyAt keeps the last VERIFIED copy: only a success moves it.
                    if (at != null) put("lastCopyAt", at)
                    put("lastCopyStatus", status.name)
                    put("lastCopyError", error)
                }
            configRepository.setSetting(
                shopId = shopId,
                configKey = ConfigKey.BACKUP_CONFIG,
                value = Json.encodeToString(JsonObject.serializer(), updatedJson),
            )
        }
        configManager.refreshConfiguration()
    }
}
