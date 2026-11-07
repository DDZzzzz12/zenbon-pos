/**
 * Implementation of [DatevXmlExportService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from `Result<T, Error>` in Stage B Batch 3.
 *
 * Retrieves Kassenbuch entries from the database, maps them to DATEV XML
 * cash ledger DTOs, and delegates to the server-export module builders
 * for XML generation and ZIP packaging.
 */
package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.getDatevAccountMapping
import org.example.project.core.config.requireRestoredPathReviewed
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import org.example.project.core.domain.dto.export.DatevExportStatisticsDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.export.datevxml.DatevXmlBookingTextMapper
import org.example.project.export.datevxml.DatevXmlExportBuilder
import org.example.project.export.datevxml.DatevXmlFieldValidator
import org.example.project.export.datevxml.PdfAttachment
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.document.domain.models.linkedDocument
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.DatevXmlExportService
import org.example.project.features.kassenbuch.domain.service.TransientExportResult
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.infrastructure.errors.PosException
import org.example.project.pdf.util.FileStorageHelper
import org.example.project.utils.FileSystemUtils
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.formatGerman
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.datetime.LocalDate as KotlinLocalDate

class DatevXmlExportServiceImpl(
    private val kassenbuchRepository: KassenbuchRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val zBonService: ZBonService,
    private val configManager: IShopConfigurationManager,
    private val exportLogService: DatevExportLogService,
    private val documentRepository: DocumentRepository,
) : DatevXmlExportService {
    private val logger = LoggerFactory.getLogger(DatevXmlExportServiceImpl::class.java)

    /** Internal data carrier for a completed generation step. */
    private data class XmlExportData(
        val zipData: ByteArray,
        val headerCount: Int,
        val totalLineItems: Int,
        val warnings: List<String>,
        val unassignedContra: UnassignedSummary?,
    )

    /**
     * Runs the full generation pipeline (DB query → XML build → ZIP) and returns
     * the raw bytes plus counts. Does NOT write to disk or record a log entry.
     *
     * @param cashBookIds When non-null, restricts the export to these cash books (see
     *        [org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository.getEntriesByDateRange]);
     *        `null` exports ALL books.
     */
    @OptIn(TestOnlyApi::class)
    private suspend fun generate(
        startDate: String,
        endDate: String,
        cashBookIds: List<Long>? = null,
    ): XmlExportData {
        val mapping = configManager.getDatevAccountMapping()
        return dbQuery {
            logger.info(
                "DatevXmlExportServiceImpl.generate: starting DATEV XML export for date range: {} to {}",
                startDate,
                endDate,
            )

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
                    "No Kassenbuch entries found for the specified date range",
                )
            }

            val warnings = SplitTransferWarnings.detect(headers)
            val entries = headers.flatMap { header -> mapHeaderToEntries(header, mapping) }

            val documentStoragePath = configManager.getDocumentStoragePath()
            val pdfAttachments = collectLinkedPdfs(headers, documentStoragePath)

            val kassenName = configManager.getDatevXmlKassenName()
            val consultantNumber = configManager.getDatevXmlConsultantNumber()
            val clientNumber = configManager.getDatevXmlClientNumber()

            val config =
                DatevXmlExportDto.ExportConfigDto(
                    kassenName = kassenName,
                    clientName = DatevXmlFieldValidator.clientName(shop.shopName, logger),
                    consultantNumber =
                        DatevXmlFieldValidator.truncate(
                            consultantNumber,
                            DatevXmlFieldValidator.MAX_CONSULTANT_NUMBER,
                            "consultantNumber",
                            logger,
                        ),
                    clientNumber =
                        DatevXmlFieldValidator.truncate(
                            clientNumber,
                            DatevXmlFieldValidator.MAX_CLIENT_NUMBER,
                            "clientNumber",
                            logger,
                        ),
                )

            val zipData =
                DatevXmlExportBuilder.buildExportPackage(
                    entries = entries,
                    config = config,
                    startDate = startDate,
                    endDate = endDate,
                    pdfAttachments = pdfAttachments,
                )

            val totalLineItems = headers.sumOf { it.lineItems.count().toInt() }
            logger.info(
                "DatevXmlExportServiceImpl.generate: DATEV XML export complete: {} headers, {} line items, {} PDFs, {} bytes",
                headers.size,
                totalLineItems,
                pdfAttachments.size,
                zipData.size,
            )

            XmlExportData(
                zipData = zipData,
                headerCount = headers.size,
                totalLineItems = totalLineItems,
                warnings = warnings,
                unassignedContra = KassenbuchUnassignedContra.collect(headers, mapping),
            )
        }
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
        configManager.requireRestoredPathReviewed(RestoredPathSetting.DATEV_EXPORT_PATH, "DATEV Kassenbuch export")
        val exportData = generate(startDate, endDate, cashBookIds)

        val fileName = "datev_xml_kassenbuch_${startDate}_$endDate.zip"
        val periodFolder = periodLabel.ifBlank { "${startDate}_to_$endDate" }
        saveToServerPath(exportData.zipData, fileName, periodFolder)

        val logDto =
            exportLogService.record(
                exportType = DatevExportType.KASSENBUCH,
                periodStart = LocalDateTime(KotlinLocalDate.parse(startDate), LocalTime(0, 0)),
                periodEnd = LocalDateTime(KotlinLocalDate.parse(endDate), LocalTime(23, 59, 59)),
                exportedByUserId = exportedByUserId,
                source = source,
                fileBytes = exportData.zipData,
                fileName = fileName,
                rowCount = exportData.headerCount,
                entryCount = exportData.totalLineItems,
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
        val fileName = "datev_xml_kassenbuch_${startDate}_$endDate.zip"
        exportLogService.record(
            exportType = DatevExportType.KASSENBUCH,
            periodStart = LocalDateTime(KotlinLocalDate.parse(startDate), LocalTime(0, 0)),
            periodEnd = LocalDateTime(KotlinLocalDate.parse(endDate), LocalTime(23, 59, 59)),
            exportedByUserId = exportedByUserId,
            source = DatevExportSource.MANUAL,
            fileBytes = data.zipData,
            fileName = fileName,
            rowCount = data.headerCount,
            entryCount = data.totalLineItems,
            periodFolder = "",
        )
        return TransientExportResult(
            bytes = data.zipData,
            fileName = fileName,
            warnings = data.warnings,
            unassignedContra = data.unassignedContra,
        )
    }

    override suspend fun getStatistics(
        startDate: String,
        endDate: String,
        cashBookIds: List<Long>?,
    ): DatevExportStatisticsDto =
        dbQuery {
            logger.info(
                "DatevXmlExportServiceImpl.getStatistics: getting DATEV XML export statistics for: {} to {}",
                startDate,
                endDate,
            )

            val shop =
                shopInfoRepository.findById(1L)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Default shop not found",
                    )

            val headers = kassenbuchRepository.getEntriesByDateRange(shop, startDate, endDate, cashBookIds = cashBookIds)

            val totalLineItems = headers.sumOf { it.lineItems.count().toInt() }
            val distinctDays = headers.map { it.belegDatum }.distinct().size
            val totalAmount = headers.sumOf { it.totalAmount }

            DatevExportStatisticsDto(
                totalEntries = totalLineItems,
                totalDays = distinctDays,
                totalAmountFormatted = formatGerman(totalAmount),
                dateRange = "$startDate – $endDate",
            )
        }

    /**
     * Maps a KassenbuchHeader with its line items to DATEV XML CashLedgerEntryDtos.
     */
    @TestOnlyApi
    fun mapHeaderToEntries(
        header: KassenbuchHeader,
        mapping: DatevAccountMappingDto.MappingDto? = null,
    ): List<DatevXmlExportDto.CashLedgerEntryDto> {
        val isoDate = convertBelegDatumToIso(header.belegDatum)

        val linkedInvoice =
            header.linkedDocument?.let {
                if (it.fileType == "INVOICE") it else null
            }
        // `<invoiceId>` is the accountant's handle for the SUPPLIER's invoice, so the supplier's
        // number is what belongs here; `header.belegNr` is our own Kassenbuch document number and is
        // a last resort for a linked scan nobody has captured yet. The normalized invoice is read
        // first and the frozen legacy row second: since Stage 4 nothing writes the legacy table, so
        // reading it alone silently emitted belegNr for every newly linked invoice.
        val rawInvoiceId =
            linkedInvoice?.let { document ->
                documentRepository.getInvoiceBySourceDocument(document.id.value)?.invoiceNumber
                    ?: document.invoiceMetadata?.invoiceNumber
                    // Same book prefix as Buchungsstapel Belegfeld 1 (see DatevBelegfeld1).
                    ?: DatevBelegfeld1.of(header)
            }
        val sanitizedInvoiceId =
            rawInvoiceId?.let {
                DatevXmlFieldValidator.sanitizeInvoiceId(it, "header ${header.id.value}", logger)
            }

        val linkedPdfFileName =
            header.linkedDocument?.let { doc ->
                if (doc.isActive) doc.filename else null
            } ?: header.zBon?.let { zBon ->
                val isoDateForZBon = convertBelegDatumToIso(header.belegDatum)
                "ZBon_Nr${zBon.zbonNummer}_$isoDateForZBon.pdf"
            }

        // F64: a Storno resolves through the entry it reverses — see KassenbuchBookingSource.
        val bookedAs = KassenbuchBookingSource.of(header)
        return header.lineItems.map { lineItem ->
            val bookingText = (lineItem.belegtext ?: header.belegtext).ifBlank { null }

            // Accepted edge (spec §3.4): a NON-numeric header.gegenkonto is dropped by resolveLine, so
            // in a mapped shop the mapping cell wins. The Legacy branch below still carries the raw
            // string unchanged for unconfigured shops.
            val resolution = KassenbuchAccountResolver.resolveLine(header, lineItem, mapping)
            // Legacy = raw header fields, exactly as before (XML byte-identity for
            // unconfigured shops). Mapped = per-line account from the mapping; a cell with
            // „Konto automatisch zuordnen" off yields null for both, so `accountNo` and `buCode`
            // are omitted and DATEV completes them.
            val (resolvedAccountNo, resolvedBuCode) =
                when (resolution) {
                    is KassenbuchAccountResolver.Resolution.Mapped ->
                        resolution.gegenkonto?.toString() to resolution.bu
                    KassenbuchAccountResolver.Resolution.Legacy ->
                        bookedAs.gegenkonto to bookedAs.bu
                }

            DatevXmlExportDto.CashLedgerEntryDto(
                date = isoDate,
                amount = convertVorzBetragToXml(lineItem.vorzBetrag),
                accountNo = resolvedAccountNo,
                buCode = resolvedBuCode,
                costCategoryId =
                    DatevXmlFieldValidator.truncate(
                        header.kost1,
                        DatevXmlFieldValidator.MAX_COST_CATEGORY_ID,
                        "costCategoryId",
                        logger,
                    ),
                costCategoryId2 =
                    DatevXmlFieldValidator.truncate(
                        header.kost2,
                        DatevXmlFieldValidator.MAX_COST_CATEGORY_ID,
                        "costCategoryId2",
                        logger,
                    ),
                tax = convertUstSatzToXml(lineItem.ustSatz),
                information =
                    DatevXmlFieldValidator.truncateOrNull(
                        header.nachricht,
                        DatevXmlFieldValidator.MAX_INFORMATION,
                        "information",
                        logger,
                    ),
                currencyCode = "EUR",
                invoiceId = sanitizedInvoiceId,
                bookingText =
                    DatevXmlFieldValidator.truncate(
                        bookingText ?: DatevXmlBookingTextMapper.getBookingText(
                            entryType = header.entryType,
                            vatRate = lineItem.ustSatz,
                        ),
                        DatevXmlFieldValidator.MAX_BOOKING_TEXT,
                        "bookingText",
                        logger,
                    ) ?: "",
                linkedPdfFileName = linkedPdfFileName,
                headerId = header.id.value,
            )
        }
    }

    /**
     * Collects all linked PDF documents from Kassenbuch headers.
     */
    private suspend fun collectLinkedPdfs(
        headers: List<KassenbuchHeader>,
        documentStoragePath: String,
    ): List<PdfAttachment> {
        val seenFileNames = mutableSetOf<String>()
        val attachments = mutableListOf<PdfAttachment>()

        for (header in headers) {
            val isoDate = convertBelegDatumToIso(header.belegDatum)

            var fileName = ""
            val linkedDoc = header.linkedDocument
            if (linkedDoc != null && linkedDoc.isActive) {
                fileName = linkedDoc.filename
                val resolvedPath = FileStorageHelper.resolveStoredPath(linkedDoc.filePath, documentStoragePath)
                val pdfFile = File(resolvedPath)
                if (pdfFile.exists() && pdfFile.isFile) {
                    if (fileName !in seenFileNames) {
                        seenFileNames.add(fileName)
                        try {
                            val bytes = pdfFile.readBytes()
                            attachments.add(PdfAttachment(date = isoDate, fileName = fileName, data = bytes))
                            logger.debug("collectLinkedPdfs: collected stored PDF: {} ({} bytes)", fileName, bytes.size)
                        } catch (e: Exception) {
                            logger.warn("collectLinkedPdfs: failed to read PDF {}: {}", linkedDoc.filePath, e.message)
                        }
                    }
                    continue
                }
                logger.warn(
                    "collectLinkedPdfs: stored PDF not found on disk: {} (header {})",
                    linkedDoc.filePath,
                    header.id.value,
                )
            }

            val zBon = header.zBon
            if (zBon != null) {
                val zbonId = zBon.id.value
                val zbonNr = zBon.zbonNummer
                fileName = fileName.ifBlank { "ZBon_Nr${zbonNr}_$isoDate.pdf" }
                if (fileName in seenFileNames) continue
                seenFileNames.add(fileName)

                try {
                    val bytes = zBonService.getZBonPdfBytes(zbonId)
                    attachments.add(PdfAttachment(date = isoDate, fileName = fileName, data = bytes))
                    logger.info(
                        "collectLinkedPdfs: generated Z-Bon PDF on-the-fly: {} ({} bytes)",
                        fileName,
                        bytes.size,
                    )
                } catch (e: Exception) {
                    logger.warn("collectLinkedPdfs: error generating Z-Bon PDF for zbonId={}: {}", zbonId, e.message)
                }
            }
        }

        logger.info("collectLinkedPdfs: collected {} PDF documents for DATEV XML export", attachments.size)
        return attachments
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
                "DatevXmlExportServiceImpl.convertBelegDatumToIso: failed to parse belegDatum '{}', using today's date",
                belegDatum,
            )
            LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        }

    private fun convertVorzBetragToXml(amount: BigDecimal): String {
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "Zero VorzBetrag — DATEV rejects 0.00 entries",
            )
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    private fun convertUstSatzToXml(ustSatz: String?): String? {
        if (ustSatz.isNullOrBlank()) return null
        val cleaned = ustSatz.replace(",", ".").trim()
        return try {
            val value = BigDecimal(cleaned)
            if (value.compareTo(BigDecimal.ZERO) == 0) return null
            value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Writes the export to disk. Throws on failure so the caller can abort the export. */
    private suspend fun saveToServerPath(
        zipData: ByteArray,
        fileName: String,
        periodFolder: String,
    ) {
        val exportPath = configManager.getDatevXmlExportPath()
        if (exportPath.isBlank()) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "DATEV XML export path not configured",
            )
        }

        val dir = File(exportPath, periodFolder)
        FileSystemUtils.ensureDirectoryWritable(dir)

        val destFile = File(dir, fileName)
        try {
            destFile.writeBytes(zipData)
        } catch (e: IOException) {
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "DATEV XML export failed: ${e.message}",
                cause = e,
            )
        }
        logger.info(
            "DatevXmlExportServiceImpl.saveToServerPath: wrote {} bytes to {}",
            zipData.size,
            destFile.absolutePath,
        )
    }
}
