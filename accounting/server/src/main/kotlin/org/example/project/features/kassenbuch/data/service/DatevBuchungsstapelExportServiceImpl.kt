package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.config.getDatevAccountMapping
import org.example.project.core.config.requireRestoredPathReviewed
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelExportBuilder
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelExportService
import org.example.project.features.kassenbuch.domain.service.TransientExportResult
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.FileSystemUtils
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.datetime.LocalDate as KotlinLocalDate

/**
 * Produces `EXTF_Buchungsstapel.csv` for DATEV Rechnungswesen
 * Stapelverarbeitung.
 *
 * Throws [PosException] on errors. Migrated from `Result<T, Error>` in
 * Stage B Batch 3.
 */
class DatevBuchungsstapelExportServiceImpl(
    private val kassenbuchRepository: KassenbuchRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val configManager: IShopConfigurationManager,
    private val exportLogService: DatevExportLogService,
) : DatevBuchungsstapelExportService {
    private val logger = LoggerFactory.getLogger(DatevBuchungsstapelExportServiceImpl::class.java)

    /** Internal data carrier for a completed Buchungsstapel generation step. */
    private data class BuchungsstapelExportData(
        val bytes: ByteArray,
        val rowCount: Int,
        val warnings: List<String>,
        val unassignedContra: UnassignedSummary?,
    )

    /**
     * Runs the full Buchungsstapel generation pipeline (DB query → CSV build) and
     * returns the raw bytes plus row count. Does NOT write to disk or record a log entry.
     *
     * @param cashBookIds When non-null, restricts the export to these cash books (see
     *        [KassenbuchRepository.getEntriesByDateRange]); `null` exports ALL books.
     */
    private suspend fun generate(
        startDate: String,
        endDate: String,
        cashBookIds: List<Long>? = null,
    ): BuchungsstapelExportData {
        val mapping = DatevBuchungsstapelMappingGate.requireConfigured(configManager.getDatevAccountMapping())
        val cfg = loadConfig(startDate, endDate, mapping.kontenrahmen)
        var rowCount = 0
        var warnings = emptyList<String>()
        var unassignedContra: UnassignedSummary? = null
        val bytes =
            dbQuery {
                val shop =
                    shopInfoRepository.findById(1L)
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "Default shop not found",
                        )
                val headers = kassenbuchRepository.getEntriesByDateRange(shop, startDate, endDate, cashBookIds = cashBookIds)
                if (headers.isEmpty()) {
                    throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "No Kassenbuch entries for $startDate..$endDate",
                    )
                }
                val rows = headers.flatMap { KassenbuchBuchungsstapelRowMapper.mapHeader(it, mapping = mapping) }
                warnings =
                    SplitTransferWarnings.detect(headers) +
                    BuchungsstapelPeriodWarnings.detect(
                        rows = rows,
                        requestedStart = cfg.startDate,
                        requestedEnd = cfg.endDate,
                        wirtschaftsjahrBeginn = cfg.wirtschaftsjahrBeginn,
                    )
                if (rows.isEmpty()) {
                    throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "No exportable rows in $startDate..$endDate",
                    )
                }
                rowCount = rows.size
                unassignedContra = KassenbuchUnassignedContra.collect(headers, mapping)
                DatevBuchungsstapelExportBuilder.build(cfg, rows)
            }
        return BuchungsstapelExportData(bytes = bytes, rowCount = rowCount, warnings = warnings, unassignedContra = unassignedContra)
    }

    override suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String,
        source: DatevExportSource,
    ): DatevExportLogDto = export(startDate, endDate, exportedByUserId, periodLabel, source, cashBookIds = null)

    override suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String,
        source: DatevExportSource,
        cashBookIds: List<Long>?,
    ): DatevExportLogDto {
        // Fail fast: this export writes a server copy into the DATEV export folder.
        configManager.requireRestoredPathReviewed(RestoredPathSetting.DATEV_EXPORT_PATH, "DATEV Buchungsstapel export")
        val exportData = generate(startDate, endDate, cashBookIds)
        val fileName = "EXTF_Buchungsstapel.csv"
        val periodFolder = periodLabel.ifBlank { "${startDate}_to_$endDate" }
        saveServerCopy(exportData.bytes, fileName, periodFolder)

        val logDto =
            exportLogService.record(
                exportType = DatevExportType.BUCHUNGSSTAPEL,
                periodStart = LocalDateTime(KotlinLocalDate.parse(startDate), LocalTime(0, 0)),
                periodEnd = LocalDateTime(KotlinLocalDate.parse(endDate), LocalTime(23, 59, 59)),
                exportedByUserId = exportedByUserId,
                source = source,
                fileBytes = exportData.bytes,
                fileName = fileName,
                rowCount = exportData.rowCount,
                entryCount = exportData.rowCount,
                periodFolder = periodFolder,
            )
        return logDto.copy(warnings = exportData.warnings, unassignedContra = exportData.unassignedContra)
    }

    override suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
    ): TransientExportResult = exportTransient(startDate, endDate, exportedByUserId, cashBookIds = null)

    override suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        cashBookIds: List<Long>?,
    ): TransientExportResult {
        val data = generate(startDate, endDate, cashBookIds)
        val fileName = "datev_buchungsstapel_${startDate}_$endDate.csv"
        exportLogService.record(
            exportType = DatevExportType.BUCHUNGSSTAPEL,
            periodStart = LocalDateTime(KotlinLocalDate.parse(startDate), LocalTime(0, 0)),
            periodEnd = LocalDateTime(KotlinLocalDate.parse(endDate), LocalTime(23, 59, 59)),
            exportedByUserId = exportedByUserId,
            source = DatevExportSource.MANUAL,
            fileBytes = data.bytes,
            fileName = fileName,
            rowCount = data.rowCount,
            entryCount = data.rowCount,
            periodFolder = "",
        )
        return TransientExportResult(
            bytes = data.bytes,
            fileName = fileName,
            warnings = data.warnings,
            unassignedContra = data.unassignedContra,
        )
    }

    private suspend fun loadConfig(
        startDate: String,
        endDate: String,
        kontenrahmen: Kontenrahmen,
    ): BuchungsstapelFileConfig {
        val consultantNumber =
            ShopConfigurationManager.getDatevXmlConsultantNumber()
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "DATEV Beraternummer not configured",
                )
        val clientNumber =
            ShopConfigurationManager.getDatevXmlClientNumber()
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "DATEV Mandantennummer not configured",
                )
        val wjBeginn = ShopConfigurationManager.getDatevWirtschaftsjahrBeginn()
        val sachkontenlaenge = ShopConfigurationManager.getDatevSachkontenlaenge()
        val festschreibung = ShopConfigurationManager.getDatevFestschreibung()
        return BuchungsstapelFileConfig(
            consultantNumber = consultantNumber,
            clientNumber = clientNumber,
            wirtschaftsjahrBeginn = wjBeginn,
            sachkontenlaenge = sachkontenlaenge,
            festschreibung = festschreibung,
            startDate = LocalDate.parse(startDate),
            endDate = LocalDate.parse(endDate),
            bezeichnung = "Kassenbuch $startDate–$endDate",
            // Cell 27 used to be the hardcoded default "04" because no caller ever passed it,
            // so an SKR03 shop shipped a file declaring SKR04.
            skr = kontenrahmen.datevCode,
        )
    }

    /** Writes the export to disk. Throws on failure so the caller can abort the export. */
    private suspend fun saveServerCopy(
        bytes: ByteArray,
        fileName: String,
        periodFolder: String,
    ) {
        val path = ShopConfigurationManager.getDatevXmlExportPath()
        if (path.isBlank()) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "DATEV XML export path not configured",
            )
        }
        val dir = File(path, periodFolder)
        FileSystemUtils.ensureDirectoryWritable(dir)
        val destFile = File(dir, fileName)
        try {
            destFile.writeBytes(bytes)
        } catch (e: IOException) {
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "DATEV Buchungsstapel export failed: ${e.message}",
                cause = e,
            )
        }
        logger.info(
            "DatevBuchungsstapelExportServiceImpl.saveServerCopy: wrote {} bytes to {}",
            bytes.size,
            destFile.absolutePath,
        )
    }
}
