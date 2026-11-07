/**
 * Implementation of [DatevApExportService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from `Result<T, Error>` in Stage B Batch 3.
 *
 * Retrieves eligible invoices from the database, maps them to DATEV XML
 * accounts payable ledger DTOs, and delegates to the server-export module
 * builders for XML generation and ZIP packaging.
 */
package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.requireRestoredPathReviewed
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.datevxml.DatevApExportDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.export.datevxml.DatevXmlFieldValidator
import org.example.project.export.datevxml.PdfAttachment
import org.example.project.export.datevxml.ap.DatevApExportBuilder
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.document.database.tables.InvoiceVatBreakdownTable
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.models.InvoiceVatBreakdown
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.kassenbuch.domain.service.TransientExportResult
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.pdf.util.FileStorageHelper
import org.example.project.utils.FileSystemUtils
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.formatGerman
import org.jetbrains.exposed.v1.core.SortOrder
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class DatevApExportServiceImpl(
    private val documentRepository: DocumentRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val configManager: IShopConfigurationManager,
    private val exportLogService: DatevExportLogService,
    private val kassenbuchLinks: DocumentKassenbuchLinkResolver,
) : DatevApExportService {
    private val logger = LoggerFactory.getLogger(DatevApExportServiceImpl::class.java)

    /** Internal data carrier for a completed AP generation step. */
    private data class ApExportData(
        val zipData: ByteArray,
        val invoiceCount: Int,
        val entryCount: Int,
        /** Invoices left out because DATEV would reject their amount. Logged, not persisted. */
        val skippedInvoiceCount: Int = 0,
    )

    /**
     * Runs the full AP generation pipeline (DB query → XML build → ZIP) and returns
     * the raw bytes plus counts. Does NOT write to disk or record a log entry.
     */
    private suspend fun generate(
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean,
    ): ApExportData =
        dbQuery {
            logger.info(
                "DatevApExportServiceImpl.generate: starting DATEV AP export for date range: {} to {}",
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

            val invoices =
                documentRepository.getInvoicesForDatevApExport(
                    startDate,
                    endDate,
                    excludeKassenbuchLinked,
                    kassenbuchLinks,
                )

            if (invoices.isEmpty()) {
                throw PosException(
                    PosErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatusCode.NotFound,
                    "No invoices found for the specified date range",
                )
            }

            val apExportMode = configManager.getDatevApExportMode()
            logger.info("DatevApExportServiceImpl.generate: export mode: {}", apExportMode)

            val skippedInvoiceIds = mutableListOf<Long>()
            val entries = invoices.flatMap { invoice -> mapInvoiceToApEntries(invoice, apExportMode, skippedInvoiceIds) }

            if (skippedInvoiceIds.isNotEmpty()) {
                logger.warn(
                    "DatevApExportServiceImpl.generate: {} invoice(s) left out of the Buchungsstapel (amount <= 0): {}",
                    skippedInvoiceIds.size,
                    skippedInvoiceIds,
                )
            }

            if (entries.isEmpty()) {
                throw PosException(
                    PosErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatusCode.NotFound,
                    "No invoices with valid metadata found for the specified date range",
                )
            }

            val documentStoragePath = configManager.getDocumentStoragePath()
            val pdfByInvoiceId =
                buildInvoiceIdToPdfMap(invoices.filterNot { it.id.value in skippedInvoiceIds }, documentStoragePath)

            val consultantNumber = configManager.getDatevXmlConsultantNumber()
            val clientNumber = configManager.getDatevXmlClientNumber()

            val config =
                DatevApExportDto.ApExportConfigDto(
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
                DatevApExportBuilder.buildExportPackage(
                    entries = entries,
                    config = config,
                    startDate = startDate,
                    endDate = endDate,
                    pdfByInvoiceId = pdfByInvoiceId,
                )

            logger.info(
                "DatevApExportServiceImpl.generate: DATEV AP export complete: {} entries, {} PDFs, {} bytes, " +
                    "{} invoice(s) skipped",
                entries.size,
                pdfByInvoiceId.size,
                zipData.size,
                skippedInvoiceIds.size,
            )

            ApExportData(
                zipData = zipData,
                invoiceCount = invoices.size,
                entryCount = entries.size,
                skippedInvoiceCount = skippedInvoiceIds.size,
            )
        }

    override suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String,
        source: DatevExportSource,
    ): DatevExportLogDto =
        export(
            startDate = startDate,
            endDate = endDate,
            exportedByUserId = exportedByUserId,
            periodLabel = periodLabel,
            source = source,
            excludeKassenbuchLinked = true,
        )

    override suspend fun export(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        periodLabel: String,
        source: DatevExportSource,
        excludeKassenbuchLinked: Boolean,
    ): DatevExportLogDto {
        // Fail fast: this export writes a server copy into the DATEV export folder.
        configManager.requireRestoredPathReviewed(RestoredPathSetting.DATEV_EXPORT_PATH, "DATEV AP ledger export")
        val exportData = generate(startDate, endDate, excludeKassenbuchLinked)

        val fileName = "datev_eingangsrechnungen_${startDate}_$endDate.zip"
        val periodFolder = periodLabel.ifBlank { "${startDate}_to_$endDate" }
        saveToServerPath(exportData.zipData, fileName, periodFolder)

        return exportLogService.record(
            exportType = DatevExportType.AP_LEDGER,
            periodStart = LocalDateTime(LocalDate.parse(startDate), LocalTime(0, 0)),
            periodEnd = LocalDateTime(LocalDate.parse(endDate), LocalTime(23, 59, 59)),
            exportedByUserId = exportedByUserId,
            source = source,
            fileBytes = exportData.zipData,
            fileName = fileName,
            rowCount = exportData.invoiceCount,
            entryCount = exportData.entryCount,
            periodFolder = periodFolder,
        )
    }

    override suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
    ): TransientExportResult =
        exportTransient(
            startDate = startDate,
            endDate = endDate,
            exportedByUserId = exportedByUserId,
            excludeKassenbuchLinked = true,
        )

    override suspend fun exportTransient(
        startDate: String,
        endDate: String,
        exportedByUserId: UUID,
        excludeKassenbuchLinked: Boolean,
    ): TransientExportResult {
        val data = generate(startDate, endDate, excludeKassenbuchLinked)
        val fileName = "datev_xml_ap_${startDate}_$endDate.zip"
        exportLogService.record(
            exportType = DatevExportType.AP_LEDGER,
            periodStart = LocalDateTime(LocalDate.parse(startDate), LocalTime(0, 0)),
            periodEnd = LocalDateTime(LocalDate.parse(endDate), LocalTime(23, 59, 59)),
            exportedByUserId = exportedByUserId,
            source = DatevExportSource.MANUAL,
            fileBytes = data.zipData,
            fileName = fileName,
            rowCount = data.invoiceCount,
            entryCount = data.entryCount,
            periodFolder = "",
        )
        return TransientExportResult(bytes = data.zipData, fileName = fileName)
    }

    override suspend fun getApExportStatistics(
        startDate: String,
        endDate: String,
        excludeKassenbuchLinked: Boolean,
    ): DatevApExportDto.ApExportStatisticsDto =
        dbQuery {
            logger.info(
                "DatevApExportServiceImpl.getApExportStatistics: getting DATEV AP export statistics for: {} to {} (excludeKassenbuchLinked={})",
                startDate,
                endDate,
                excludeKassenbuchLinked,
            )

            val invoices =
                documentRepository.getInvoicesForDatevApExport(
                    startDate,
                    endDate,
                    excludeKassenbuchLinked,
                    kassenbuchLinks,
                )

            var totalAmount = BigDecimal.ZERO
            var missingMetadataCount = 0

            for (invoice in invoices) {
                if (invoice.invoiceNumber == null) {
                    missingMetadataCount++
                }
                invoice.taxInclusiveTotal?.let { totalAmount = totalAmount.add(it) }
            }

            DatevApExportDto.ApExportStatisticsDto(
                totalInvoices = invoices.size,
                totalAmount = formatGerman(totalAmount.setScale(2, RoundingMode.HALF_UP)),
                dateRange = "$startDate – $endDate",
                missingMetadataCount = missingMetadataCount,
            )
        }

    /**
     * Maps an [Invoice] to one or more ApLedgerEntryDto.
     *
     * One entry per `invoice_vat_breakdown` row; each row's [InvoiceVatBreakdown.description]
     * overrides the invoice-level booking text when it is non-blank, which is why that column
     * exists. Only an invoice with no breakdown rows at all falls back to a single entry carrying
     * the invoice total.
     */
    private fun mapInvoiceToApEntries(
        invoice: Invoice,
        mode: String,
        skippedInvoiceIds: MutableList<Long>,
    ): List<DatevApExportDto.ApLedgerEntryDto> = withAmountSkip(invoice, skippedInvoiceIds, buildApEntries(invoice, mode))

    /**
     * Drops an invoice whose ledger entries DATEV would reject, instead of failing the whole run.
     *
     * `p7` is `minInclusive 0.01`: a 0.00 entry is schema-invalid, and so is a negative one. A
     * Nullrechnung is nevertheless a real document — a warranty replacement, a free-of-charge
     * delivery, a correction that nets to zero — so it is recorded and simply not booked. Before
     * this guard, [DatevXmlFieldValidator.requireValidAmount] threw a plain
     * `IllegalArgumentException` from inside `DatevApLedgerGenerator`'s fold and one such invoice
     * aborted the entire export with a 500.
     *
     * The check lives here rather than in the validator because the validator is shared with the
     * cash-ledger export and because it sees one *entry*, while the unit that must be skipped is
     * the *invoice* — a split-tax invoice yields several entries and half of one is worse than none.
     *
     * An **unparseable** amount is deliberately left to the validator to reject: that is data
     * corruption, not a legitimate document, and swallowing it would hide it.
     */
    private fun withAmountSkip(
        invoice: Invoice,
        skippedInvoiceIds: MutableList<Long>,
        entries: List<DatevApExportDto.ApLedgerEntryDto>,
    ): List<DatevApExportDto.ApLedgerEntryDto> {
        val rejected = entries.firstOrNull { isNotExportableAmount(it.amount) } ?: return entries

        skippedInvoiceIds.add(invoice.id.value)
        logger.warn(
            "DatevApExportServiceImpl: skipping {} — amount {} is not exportable (DATEV p7 is minInclusive 0.01)",
            invoice.logContext(),
            rejected.amount,
        )
        return emptyList()
    }

    /** `true` for `<= 0`; an unparseable amount is left alone so the validator still rejects it. */
    private fun isNotExportableAmount(amount: String): Boolean {
        val parsed = amount.replace(",", ".").trim().toBigDecimalOrNull() ?: return false
        return parsed <= BigDecimal.ZERO
    }

    private fun buildApEntries(
        invoice: Invoice,
        mode: String,
    ): List<DatevApExportDto.ApLedgerEntryDto> {
        val issueDate = invoice.issueDate ?: return emptyList()
        val isoDate = issueDate.toIsoDate()

        val invoiceId =
            DatevXmlFieldValidator.sanitizeInvoiceId(invoice.rawInvoiceId(), invoice.logContext(), logger)
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "No valid invoiceId for ${invoice.logContext()}",
                )

        val isMinimal = mode == "MINIMAL"
        val booking = invoice.booking

        val currencyCode = invoice.currency
        val bookingText =
            DatevXmlFieldValidator.truncateOrNull(
                booking?.bookingText,
                DatevXmlFieldValidator.MAX_BOOKING_TEXT,
                "bookingText",
                logger,
            )
        val information =
            DatevXmlFieldValidator.truncateOrNull(
                invoice.paymentReference,
                DatevXmlFieldValidator.MAX_INFORMATION,
                "information",
                logger,
            )

        val supplierName =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncate(
                    invoice.sellerName,
                    DatevXmlFieldValidator.MAX_SUPPLIER_NAME,
                    "supplierName",
                    logger,
                )
            }
        val supplierCity =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncate(
                    invoice.sellerCity,
                    DatevXmlFieldValidator.MAX_SUPPLIER_CITY,
                    "supplierCity",
                    logger,
                )
            }
        val vatId =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncate(
                    invoice.sellerVatId,
                    DatevXmlFieldValidator.MAX_VAT_ID,
                    "vatId",
                    logger,
                )
            }
        val iban =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncate(
                    invoice.paymentIban,
                    DatevXmlFieldValidator.MAX_IBAN,
                    "iban",
                    logger,
                )
            }
        val bpAccountNo =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncate(
                    booking?.bpAccountNo,
                    DatevXmlFieldValidator.MAX_BP_ACCOUNT_NO,
                    "bpAccountNo",
                    logger,
                )
            }
        val buCode =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncateOrNull(
                    booking?.buCode,
                    DatevXmlFieldValidator.MAX_BU_CODE,
                    "buCode",
                    logger,
                )
            }
        val costCategoryId =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncateOrNull(
                    booking?.costCategoryId,
                    DatevXmlFieldValidator.MAX_COST_CATEGORY_ID,
                    "costCategoryId",
                    logger,
                )
            }
        val costCategoryId2 =
            if (isMinimal) {
                null
            } else {
                DatevXmlFieldValidator.truncateOrNull(
                    booking?.costCategoryId2,
                    DatevXmlFieldValidator.MAX_COST_CATEGORY_ID,
                    "costCategoryId2",
                    logger,
                )
            }
        val accountNo = if (isMinimal) null else booking?.accountNo

        val dueDateStr = if (isMinimal) null else invoice.dueDate?.toIsoDate()
        val deliveryDateStr = if (isMinimal) null else invoice.deliveryDate?.toIsoDate()
        val paidAtStr = if (isMinimal) null else booking?.paidAt?.toIsoDate()

        val vatBreakdown =
            invoice.vatBreakdown
                .orderBy(InvoiceVatBreakdownTable.id to SortOrder.ASC)
                .toList()

        if (vatBreakdown.isNotEmpty()) {
            return vatBreakdown
                .map { row ->
                    val formattedAmount =
                        row.taxableAmount
                            .add(row.vatAmount)
                            .setScale(2, RoundingMode.HALF_UP)
                            .toPlainString()
                    val formattedTax = row.vatRate.setScale(2, RoundingMode.HALF_UP).toPlainString()

                    DatevApExportDto.ApLedgerEntryDto(
                        date = isoDate,
                        amount = formattedAmount,
                        accountNo = accountNo,
                        bpAccountNo = bpAccountNo,
                        buCode = buCode,
                        tax = formattedTax,
                        currencyCode = currencyCode,
                        invoiceId = invoiceId,
                        bookingText =
                            DatevXmlFieldValidator.truncateOrNull(
                                row.description?.takeIf { it.isNotBlank() } ?: booking?.bookingText,
                                DatevXmlFieldValidator.MAX_BOOKING_TEXT,
                                "bookingText",
                                logger,
                            ),
                        information = information,
                        supplierName = supplierName,
                        supplierCity = supplierCity,
                        vatId = vatId,
                        iban = iban,
                        dueDate = dueDateStr,
                        deliveryDate = deliveryDateStr,
                        costCategoryId = costCategoryId,
                        costCategoryId2 = costCategoryId2,
                        paidAt = paidAtStr,
                    )
                }.also { warnOnConsolidatedDivergence(invoice, it) }
        }

        val grossAmount = invoice.taxInclusiveTotal ?: return emptyList()
        val formattedAmount = grossAmount.setScale(2, RoundingMode.HALF_UP).toPlainString()

        return listOf(
            DatevApExportDto.ApLedgerEntryDto(
                date = isoDate,
                amount = formattedAmount,
                accountNo = accountNo,
                bpAccountNo = bpAccountNo,
                buCode = buCode,
                tax = null,
                currencyCode = currencyCode,
                invoiceId = invoiceId,
                bookingText = bookingText,
                information = information,
                supplierName = supplierName,
                supplierCity = supplierCity,
                vatId = vatId,
                iban = iban,
                dueDate = dueDateStr,
                deliveryDate = deliveryDateStr,
                costCategoryId = costCategoryId,
                costCategoryId2 = costCategoryId2,
                paidAt = paidAtStr,
            ),
        )
    }

    /**
     * Warns when the ledger file's `consolidatedAmount` will not equal the invoice total.
     *
     * `DatevApLedgerGenerator` derives `consolidatedAmount` as the sum of the entry amounts, which
     * with one entry per VAT breakdown row is `Σ(taxable + vat)`. Nothing constrains that to equal
     * `tax_inclusive_total` — a document-level allowance or charge, or a breakdown that does not
     * cover the whole invoice, makes them drift apart. The export must not fail closed over a
     * rounding artifact, so this only logs; a silent divergence, however, would reach the books
     * unnoticed.
     *
     * The comparison is exact (`compareTo`, which ignores scale but not value). Both sides are
     * money at scale 2 by the time they are compared, so there is no legitimate noise to absorb —
     * the smallest possible difference is one cent, and one cent is exactly what an accountant
     * reconciling the ledger against the invoice would have to explain. A tolerance would only
     * hide the cases worth seeing.
     */
    private fun warnOnConsolidatedDivergence(
        invoice: Invoice,
        entries: List<DatevApExportDto.ApLedgerEntryDto>,
    ) {
        val invoiceTotal = invoice.taxInclusiveTotal ?: return
        val entrySum =
            entries.fold(BigDecimal.ZERO) { acc, entry ->
                acc.add(entry.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            }
        if (entrySum.compareTo(invoiceTotal) == 0) return

        logger.warn(
            "DatevApExportServiceImpl: consolidatedAmount will diverge from the invoice total for {} " +
                "(invoiceNumber={}): VAT breakdown sums to {} but tax_inclusive_total is {}",
            invoice.logContext(),
            invoice.invoiceNumber,
            entrySum.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            invoiceTotal.setScale(2, RoundingMode.HALF_UP).toPlainString(),
        )
    }

    /** `DOC-<source document id>` keeps the pre-migration fallback shape; a PDF-less invoice uses its own id. */
    private fun Invoice.rawInvoiceId(): String = invoiceNumber ?: "DOC-${sourceDocument?.id?.value ?: id.value}"

    private fun Invoice.logContext(): String = "invoice ${id.value}"

    private fun LocalDateTime.toIsoDate(): String = "$year-${month.number.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    private fun buildInvoiceIdToPdfMap(
        invoices: List<Invoice>,
        documentStoragePath: String,
    ): Map<String, PdfAttachment> {
        val result = mutableMapOf<String, PdfAttachment>()

        for (invoice in invoices) {
            val doc = invoice.sourceDocument ?: continue
            val issueDate = invoice.issueDate ?: continue
            val isoDate = issueDate.toIsoDate()

            val invoiceId =
                DatevXmlFieldValidator.sanitizeInvoiceId(invoice.rawInvoiceId(), invoice.logContext(), logger)
                    ?: continue

            val resolvedPath = FileStorageHelper.resolveStoredPath(doc.filePath, documentStoragePath)
            val pdfFile = File(resolvedPath)
            if (!pdfFile.exists() || !pdfFile.isFile) {
                logger.warn(
                    "DatevApExportServiceImpl.buildInvoiceIdToPdfMap: invoice PDF not found on disk: {} (document {})",
                    doc.filePath,
                    doc.id.value,
                )
                continue
            }

            try {
                val bytes = pdfFile.readBytes()
                result[invoiceId] = PdfAttachment(date = isoDate, fileName = doc.filename, data = bytes)
                logger.debug("Mapped invoice PDF: {} -> {} ({} bytes)", invoiceId, doc.filename, bytes.size)
            } catch (e: Exception) {
                logger.warn(
                    "DatevApExportServiceImpl.buildInvoiceIdToPdfMap: failed to read invoice PDF {}: {}",
                    doc.filePath,
                    e.message,
                )
            }
        }

        logger.info(
            "DatevApExportServiceImpl.buildInvoiceIdToPdfMap: built invoice-to-PDF map: {} entries for DATEV AP export",
            result.size,
        )
        return result
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
                "DATEV AP export failed: ${e.message}",
                cause = e,
            )
        }
        logger.info(
            "DatevApExportServiceImpl.saveToServerPath: wrote {} bytes to {}",
            zipData.size,
            destFile.absolutePath,
        )
    }
}
