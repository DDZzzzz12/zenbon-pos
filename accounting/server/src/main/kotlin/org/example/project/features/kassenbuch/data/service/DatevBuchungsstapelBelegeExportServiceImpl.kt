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
import org.example.project.export.datevbuchungsstapel.DatevBeleglink
import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelExportBuilder
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import org.example.project.export.datevbuchungsstapelbelege.DatevBuchungsstapelBelegeZipBuilder
import org.example.project.export.datevxml.DatevBeleglinkDocumentGenerator
import org.example.project.export.datevxml.DatevXmlFieldValidator
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelBelegeExportService
import org.example.project.features.kassenbuch.domain.service.TransientExportResult
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.FileSystemUtils
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.datetime.LocalDate as KotlinLocalDate

/**
 * Produces the DATEV Buchungsstapel-mit-Belegen ZIP: EXTF_Buchungsstapel.csv (with
 * col 20 Beleglink = BEDI "<GUID>") + a Beleg-only document.xml + the Beleg PDFs.
 * Kassenbuch-only. Throws [PosException] on error.
 */
class DatevBuchungsstapelBelegeExportServiceImpl(
    private val kassenbuchRepository: KassenbuchRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val configManager: IShopConfigurationManager,
    private val exportLogService: DatevExportLogService,
    private val belegResolver: KassenbuchBelegResolver,
) : DatevBuchungsstapelBelegeExportService {
    private val logger = LoggerFactory.getLogger(DatevBuchungsstapelBelegeExportServiceImpl::class.java)

    private data class BuchungsstapelBelegeData(
        val zipData: ByteArray,
        val rowCount: Int,
        val warnings: List<String>,
        val unassignedContra: UnassignedSummary?,
    )

    private suspend fun generate(
        startDate: String,
        endDate: String,
        cashBookIds: List<Long>? = null,
    ): BuchungsstapelBelegeData {
        val mapping = DatevBuchungsstapelMappingGate.requireConfigured(configManager.getDatevAccountMapping())
        val cfg = loadConfig(startDate, endDate, mapping.kontenrahmen)
        var rowCount = 0
        var warnings = emptyList<String>()
        var unassignedContra: UnassignedSummary? = null
        val zip =
            dbQuery {
                val shop =
                    shopInfoRepository.findById(1L)
                        ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Default shop not found")
                val headers = kassenbuchRepository.getEntriesByDateRange(shop, startDate, endDate, cashBookIds = cashBookIds)
                if (headers.isEmpty()) {
                    throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "No Kassenbuch entries for $startDate..$endDate",
                    )
                }
                val documentStoragePath = configManager.getDocumentStoragePath()
                val resolved = belegResolver.resolve(headers, documentStoragePath) { convertBelegDatumToIso(it.belegDatum) }
                val guidByFileName = resolved.pdfs.associate { it.fileName to DatevBeleglink.guidFor(it.fileName) }

                val rows =
                    headers.flatMap { header ->
                        val fileName = resolved.belegFileNameByHeaderId[header.id.value]
                        val belegLink = fileName?.let { DatevBeleglink.cellValue(guidByFileName.getValue(it)) }
                        KassenbuchBuchungsstapelRowMapper.mapHeader(header, belegLink, mapping)
                    }
                if (rows.isEmpty()) {
                    throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "No exportable rows in $startDate..$endDate",
                    )
                }
                rowCount = rows.size
                unassignedContra = KassenbuchUnassignedContra.collect(headers, mapping)
                warnings =
                    SplitTransferWarnings.detect(headers) +
                    BuchungsstapelPeriodWarnings.detect(
                        rows = rows,
                        requestedStart = cfg.startDate,
                        requestedEnd = cfg.endDate,
                        wirtschaftsjahrBeginn = cfg.wirtschaftsjahrBeginn,
                    )

                val csvBytes = DatevBuchungsstapelExportBuilder.build(cfg, rows)

                val documentXml =
                    DatevBeleglinkDocumentGenerator.generate(
                        belege =
                            resolved.pdfs.map {
                                DatevBeleglinkDocumentGenerator.BelegRef(
                                    guid = guidByFileName.getValue(it.fileName),
                                    pdfFileName = it.fileName,
                                )
                            },
                        description = "DATEV Buchungsstapel mit Belegen $startDate bis $endDate",
                        consultantNumber = cfg.consultantNumber,
                        clientNumber = cfg.clientNumber,
                        clientName = DatevXmlFieldValidator.clientName(shop.shopName, logger),
                        generatingSystem = "POS-KMP",
                    )

                DatevBuchungsstapelBelegeZipBuilder.build(csvBytes, documentXml, resolved.pdfs)
            }
        return BuchungsstapelBelegeData(zipData = zip, rowCount = rowCount, warnings = warnings, unassignedContra = unassignedContra)
    }

    override suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String,
        source: DatevExportSource,
        cashBookIds: List<Long>?,
    ): DatevExportLogDto {
        // Fail fast: this export writes a server copy into the DATEV export folder.
        configManager.requireRestoredPathReviewed(RestoredPathSetting.DATEV_EXPORT_PATH, "DATEV Buchungsstapel-with-receipts export")
        val data = generate(startDate, endDate, cashBookIds)
        val fileName = "datev_buchungsstapel_belege_${startDate}_$endDate.zip"
        val periodFolder = periodLabel.ifBlank { "${startDate}_to_$endDate" }
        saveServerCopy(data.zipData, fileName, periodFolder)
        val logDto =
            exportLogService.record(
                exportType = DatevExportType.BUCHUNGSSTAPEL_MIT_BELEGEN,
                periodStart = LocalDateTime(KotlinLocalDate.parse(startDate), LocalTime(0, 0)),
                periodEnd = LocalDateTime(KotlinLocalDate.parse(endDate), LocalTime(23, 59, 59)),
                exportedByUserId = exportedByUserId,
                source = source,
                fileBytes = data.zipData,
                fileName = fileName,
                rowCount = data.rowCount,
                entryCount = data.rowCount,
                periodFolder = periodFolder,
            )
        return logDto.copy(warnings = data.warnings, unassignedContra = data.unassignedContra)
    }

    override suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        cashBookIds: List<Long>?,
    ): TransientExportResult {
        val data = generate(startDate, endDate, cashBookIds)
        val fileName = "datev_buchungsstapel_belege_${startDate}_$endDate.zip"
        exportLogService.record(
            exportType = DatevExportType.BUCHUNGSSTAPEL_MIT_BELEGEN,
            periodStart = LocalDateTime(KotlinLocalDate.parse(startDate), LocalTime(0, 0)),
            periodEnd = LocalDateTime(KotlinLocalDate.parse(endDate), LocalTime(23, 59, 59)),
            exportedByUserId = exportedByUserId,
            source = DatevExportSource.MANUAL,
            fileBytes = data.zipData,
            fileName = fileName,
            rowCount = data.rowCount,
            entryCount = data.rowCount,
            periodFolder = "",
        )
        return TransientExportResult(
            bytes = data.zipData,
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

    private fun convertBelegDatumToIso(belegDatum: String): String =
        try {
            val parts = belegDatum.split(".")
            val day = parts[0].toInt()
            val month = parts[1].toInt()
            val year = parts[2].toInt()
            LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            logger.warn(
                "DatevBuchungsstapelBelegeExportServiceImpl.convertBelegDatumToIso: failed to parse belegDatum '{}', using today's date",
                belegDatum,
            )
            LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
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
            "DatevBuchungsstapelBelegeExportServiceImpl.saveServerCopy: wrote {} bytes to {}",
            bytes.size,
            destFile.absolutePath,
        )
    }
}
