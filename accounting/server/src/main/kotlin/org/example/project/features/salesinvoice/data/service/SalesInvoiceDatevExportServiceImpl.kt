package org.example.project.features.salesinvoice.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.getDatevAccountMapping
import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.error.PosErrorCode
import org.example.project.export.datevxml.DatevXmlFieldValidator
import org.example.project.export.datevxml.PdfAttachment
import org.example.project.export.datevxml.ar.DatevArExportBuilder
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.document.domain.models.Document
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceDatevExportService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.pdf.util.FileStorageHelper
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.formatDecimal
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Gathers STANDALONE outgoing invoices for a period, resolves their DATEV accounts per VAT-rate split
 * via [SalesInvoiceDatevAccountResolver], blocks on any unresolvable account (preflight gate), packages
 * the accountsReceivableLedger ZIP through [DatevArExportBuilder], and records the run.
 *
 * Mirrors [org.example.project.features.kassenbuch.data.service.DatevApExportServiceImpl] for the AP
 * side. The deliberate difference is the preflight gate: AP silently *skips* an invoice DATEV would
 * reject, but AR *blocks the whole export* — an omitted outgoing §14 UStG document is a missing entry
 * in the accountant's books, so the operator must fix the account and retry rather than ship a partial.
 */
class SalesInvoiceDatevExportServiceImpl(
    private val configManager: IShopConfigurationManager,
    private val exportLogService: DatevExportLogService,
    private val shopInfoRepository: ShopInfoRepository,
) : SalesInvoiceDatevExportService {
    private val logger = LoggerFactory.getLogger(SalesInvoiceDatevExportServiceImpl::class.java)

    /** Internal carrier for a completed AR generation step. */
    private data class ArExportData(
        val zipData: ByteArray,
        val invoiceCount: Int,
        val entryCount: Int,
    )

    override suspend fun statistics(
        startDate: String,
        endDate: String,
    ): DatevArExportDto.ArExportStatisticsDto =
        dbQuery {
            val mapping = configManager.getDatevAccountMapping()
            val sammel = configManager.getDatevArSammeldebitor()
            val invoices = selectStandalone(startDate, endDate)

            val totalAmount =
                invoices.fold(BigDecimal.ZERO) { acc, invoice ->
                    acc.add(invoice.taxInclusiveTotal ?: BigDecimal.ZERO)
                }

            DatevArExportDto.ArExportStatisticsDto(
                totalInvoices = invoices.size,
                totalAmount = formatDecimal(totalAmount),
                dateRange = "$startDate – $endDate",
                unresolved = invoices.mapNotNull { unresolvedReason(it, mapping, sammel) },
                unassignedContra = UnassignedSummary.of(invoices.flatMap { unassignedCells(it, mapping) }),
            )
        }

    override suspend fun export(
        startDate: String,
        endDate: String,
        exportedBy: UUID,
    ): ByteArray {
        val data = generate(startDate, endDate)

        val fileName = "datev-ar-${startDate}_$endDate.zip"
        exportLogService.record(
            exportType = DatevExportType.AR_LEDGER,
            periodStart = LocalDateTime(LocalDate.parse(startDate), LocalTime(0, 0)),
            periodEnd = LocalDateTime(LocalDate.parse(endDate), LocalTime(23, 59, 59)),
            exportedByUserId = exportedBy,
            source = DatevExportSource.MANUAL,
            fileBytes = data.zipData,
            fileName = fileName,
            rowCount = data.invoiceCount,
            entryCount = data.entryCount,
            periodFolder = startDate.take(7),
        )
        return data.zipData
    }

    /**
     * Runs the full AR pipeline (select → preflight → build) and returns the ZIP bytes plus counts.
     * Throws [PosErrorCode.SALES_INVOICE_EXPORT_BLOCKED] if any invoice's accounts do not resolve.
     */
    private suspend fun generate(
        startDate: String,
        endDate: String,
    ): ArExportData =
        dbQuery {
            val mapping = configManager.getDatevAccountMapping()
            val sammel = configManager.getDatevArSammeldebitor()
            val invoices = selectStandalone(startDate, endDate)

            val unresolved = invoices.mapNotNull { unresolvedReason(it, mapping, sammel) }
            if (unresolved.isNotEmpty()) {
                val numbers = unresolved.joinToString(", ") { it.invoiceNumber }
                throw PosException(
                    PosErrorCode.SALES_INVOICE_EXPORT_BLOCKED,
                    HttpStatusCode.Conflict,
                    "DATEV AR export blocked: ${unresolved.size} invoice(s) have unresolvable accounts: $numbers. " +
                        "Assign a Personenkonto/Sammeldebitor and a revenue account, then retry.",
                )
            }

            val entries = invoices.flatMap { buildArEntries(it, mapping, sammel) }

            val documentStoragePath = configManager.getDocumentStoragePath()
            val pdfByInvoiceId = buildInvoiceIdToPdfMap(invoices, documentStoragePath)

            val zipData =
                DatevArExportBuilder.buildExportPackage(
                    entries = entries,
                    config = arConfig(),
                    startDate = startDate,
                    endDate = endDate,
                    pdfByInvoiceId = pdfByInvoiceId,
                )

            logger.info(
                "SalesInvoiceDatevExportServiceImpl.generate: DATEV AR export complete: {} invoices, {} entries, " +
                    "{} PDFs, {} bytes",
                invoices.size,
                entries.size,
                pdfByInvoiceId.size,
                zipData.size,
            )

            ArExportData(zipData = zipData, invoiceCount = invoices.size, entryCount = entries.size)
        }

    /** STANDALONE invoices, ISSUED or CANCELLED, issued within [startDate, endDate], ordered by number. */
    private fun selectStandalone(
        startDate: String,
        endDate: String,
    ): List<SalesInvoice> {
        val start = LocalDateTime(LocalDate.parse(startDate), LocalTime(0, 0))
        val end = LocalDateTime(LocalDate.parse(endDate), LocalTime(23, 59, 59))
        return SalesInvoice
            .find {
                (SalesInvoiceTable.revenueOrigin eq RevenueOrigin.STANDALONE) and
                    (SalesInvoiceTable.status inList listOf(SalesInvoiceStatus.ISSUED, SalesInvoiceStatus.CANCELLED)) and
                    (SalesInvoiceTable.issueDate greaterEq start) and
                    (SalesInvoiceTable.issueDate lessEq end)
            }.orderBy(SalesInvoiceTable.invoiceNumber to SortOrder.ASC)
            .toList()
    }

    /**
     * Returns an [DatevArExportDto.UnresolvedInvoiceDto] when the Personenkonto does not resolve or any
     * VAT rate's Erlöskonto does not resolve; else null. Pure over the passed-in config.
     */
    private fun unresolvedReason(
        invoice: SalesInvoice,
        mapping: DatevAccountMappingDto.MappingDto?,
        sammel: String?,
    ): DatevArExportDto.UnresolvedInvoiceDto? {
        val booking = invoice.booking.firstOrNull()
        val invoiceNumber = invoice.invoiceNumber ?: "id:${invoice.id.value}"

        val personenkonto =
            SalesInvoiceDatevAccountResolver.personenkonto(
                bookingBp = booking?.bpAccountNo,
                debtorNo = invoice.debtor?.debtorNo,
                sammeldebitor = sammel,
            )
        if (personenkonto == null) {
            return DatevArExportDto.UnresolvedInvoiceDto(
                invoiceNumber = invoiceNumber,
                buyerName = invoice.buyerName,
                reason = "no Personenkonto and no Sammeldebitor",
            )
        }

        val vatRows = orderedVatRows(invoice)
        for (row in vatRows) {
            val rate = row.vatRate.setScale(2, RoundingMode.HALF_UP).toPlainString()
            val erloeskonto =
                SalesInvoiceDatevAccountResolver.erloeskonto(
                    bookingRev = booking?.revenueAccountNo,
                    rate = rate,
                    mapping = mapping,
                )
            // A rate whose revenue cell has „Konto automatisch zuordnen" off is left to DATEV on
            // purpose: exported without accountNo, reported by [unassignedCells], never refused.
            val leftToDatev =
                SalesInvoiceDatevAccountResolver.unassignedCell(booking?.revenueAccountNo, rate, mapping) != null
            if (erloeskonto == null && !leftToDatev) {
                return DatevArExportDto.UnresolvedInvoiceDto(
                    invoiceNumber = invoiceNumber,
                    buyerName = invoice.buyerName,
                    reason = "VAT rate $rate unmapped (no revenue account)",
                )
            }
        }
        return null
    }

    /** The switched-off revenue cells [invoice]'s VAT splits book through, one per split. */
    private fun unassignedCells(
        invoice: SalesInvoice,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): List<CellRef> {
        val booking = invoice.booking.firstOrNull()
        return orderedVatRows(invoice).mapNotNull { row ->
            val rate = row.vatRate.setScale(2, RoundingMode.HALF_UP).toPlainString()
            SalesInvoiceDatevAccountResolver.unassignedCell(booking?.revenueAccountNo, rate, mapping)
        }
    }

    /** One [DatevArExportDto.ArLedgerEntryDto] per VAT-rate split; caller has verified resolvability. */
    private fun buildArEntries(
        invoice: SalesInvoice,
        mapping: DatevAccountMappingDto.MappingDto?,
        sammel: String?,
    ): List<DatevArExportDto.ArLedgerEntryDto> {
        val booking = invoice.booking.firstOrNull()
        val invoiceNumber = invoice.invoiceNumber ?: "id:${invoice.id.value}"
        val isoDate = invoice.issueDate.toIsoDate()
        val dueDate = invoice.dueDate?.toIsoDate()
        val deliveryDate = invoice.serviceDate.toIsoDate()

        val bpAccountNo =
            SalesInvoiceDatevAccountResolver.personenkonto(
                bookingBp = booking?.bpAccountNo,
                debtorNo = invoice.debtor?.debtorNo,
                sammeldebitor = sammel,
            )

        return orderedVatRows(invoice).map { row ->
            val rate = row.vatRate.setScale(2, RoundingMode.HALF_UP).toPlainString()
            val gross = row.taxableAmount.add(row.vatAmount)
            DatevArExportDto.ArLedgerEntryDto(
                date = isoDate,
                amount = formatDecimal(gross),
                accountNo =
                    SalesInvoiceDatevAccountResolver.erloeskonto(
                        bookingRev = booking?.revenueAccountNo,
                        rate = rate,
                        mapping = mapping,
                    ),
                bpAccountNo = bpAccountNo,
                buCode =
                    SalesInvoiceDatevAccountResolver.buCode(
                        bookingBu = booking?.buCode,
                        rate = rate,
                        vatCategoryCode = row.vatCategoryCode,
                        mapping = mapping,
                    ),
                tax = rate,
                currencyCode = invoice.currency,
                invoiceId = invoiceNumber,
                bookingText = booking?.bookingText,
                information = invoice.paymentReference,
                buyerName = invoice.buyerName,
                buyerCity = invoice.buyerCity,
                vatId = invoice.buyerVatId,
                dueDate = dueDate,
                deliveryDate = deliveryDate,
            )
        }
    }

    /**
     * The invoice's VAT rows, oldest first. Sorted in memory rather than via `.orderBy` on the
     * referrer: the collection is loaded more than once per invoice (preflight then build), and
     * Exposed's `LazySizedCollection.orderBy` throws "Can't order already loaded data" on the second
     * call once the referrer has been materialized.
     */
    private fun orderedVatRows(invoice: SalesInvoice): List<SalesInvoiceVatBreakdown> = invoice.vatBreakdown.toList().sortedBy { it.id.value }

    /**
     * The export header. `clientName` is the shop's name, as on the Kassenbuch and AP exports — not the
     * DATEV-XML Kassen-Name, which names one register and defaults to "Kasse". Runs inside
     * [generate]'s transaction, which the repository lookup needs.
     */
    private suspend fun arConfig(): DatevArExportDto.ArExportConfigDto =
        DatevArExportDto.ArExportConfigDto(
            clientName = DatevXmlFieldValidator.clientName(defaultShop().shopName, logger),
            consultantNumber = configManager.getDatevXmlConsultantNumber(),
            clientNumber = configManager.getDatevXmlClientNumber(),
        )

    private fun defaultShop(): ShopInfo =
        shopInfoRepository.findById(1L)
            ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Default shop not found")

    /**
     * Builds the invoiceId→PDF map from each invoice's archived document. A missing or unreadable file
     * is logged and skipped — a PDF-less ledger still exports, exactly as the AP side does.
     */
    private fun buildInvoiceIdToPdfMap(
        invoices: List<SalesInvoice>,
        documentStoragePath: String,
    ): Map<String, PdfAttachment> {
        val result = mutableMapOf<String, PdfAttachment>()
        for (invoice in invoices) {
            val invoiceNumber = invoice.invoiceNumber ?: continue
            val documentId = invoice.documentId?.value ?: continue
            val doc = Document.findById(documentId) ?: continue
            val resolvedPath = FileStorageHelper.resolveStoredPath(doc.filePath, documentStoragePath)
            val pdfFile = File(resolvedPath)
            if (!pdfFile.exists() || !pdfFile.isFile) {
                logger.warn(
                    "SalesInvoiceDatevExportServiceImpl.buildInvoiceIdToPdfMap: invoice PDF not found on disk: {} (document {})",
                    doc.filePath,
                    documentId,
                )
                continue
            }
            try {
                result[invoiceNumber] =
                    PdfAttachment(date = invoice.issueDate.toIsoDate(), fileName = doc.filename, data = pdfFile.readBytes())
            } catch (e: Exception) {
                logger.warn(
                    "SalesInvoiceDatevExportServiceImpl.buildInvoiceIdToPdfMap: failed to read invoice PDF {}: {}",
                    doc.filePath,
                    e.message,
                )
            }
        }
        return result
    }

    private fun LocalDateTime.toIsoDate(): String = "$year-${month.number.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
