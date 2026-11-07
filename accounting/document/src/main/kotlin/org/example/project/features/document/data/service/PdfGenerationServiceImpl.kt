/**
 * PDF Generation Service Implementation
 *
 * Thin orchestrator that delegates actual PDF rendering to the server-pdf module.
 * Handles service-level concerns: error wrapping, logging, coroutine context,
 * and coordination with other services (shop info, QR codes).
 *
 * CRITICAL ARCHITECTURE PATTERN:
 * - Service controls transaction boundaries using dbQuery
 * - Returns DTOs (not entities)
 * - All OpenPDF usage is encapsulated in server-pdf generators
 * - Reuses PrintFormatterService logic for content formatting
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 6.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.CountryCodeNormalizer
import org.example.project.einvoice.check.EInvoiceInvariants
import org.example.project.einvoice.check.En16931AmountRules
import org.example.project.einvoice.check.En16931RequiredFields
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.syntax.CiiInvoiceWriter
import org.example.project.features.document.domain.mapper.toDto
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfData
import org.example.project.features.salesinvoice.einvoice.SalesInvoiceToEInvoice
import org.example.project.features.setup.domain.service.ShopInfoService
import org.example.project.infrastructure.errors.PosException
import org.example.project.pdf.generator.generateBillReceiptPdfBytes
import org.example.project.pdf.generator.generateEigenbelegPdfBytes
import org.example.project.pdf.generator.generateOrderReceiptPdfBytes
import org.example.project.pdf.generator.generateReferencePrintPdfBytes
import org.example.project.pdf.generator.generateTestPagePdfBytes
import org.example.project.pdf.generator.generateZBonReportPdfBytes
import org.example.project.pdf.generator.renderSalesInvoicePdf
import org.example.project.pdf.generator.renderSalesInvoiceZugferdPdf
import org.example.project.pdf.util.FileStorageHelper
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Implementation of PDF generation service delegating to server-pdf generators.
 *
 * Generates professional PDF documents from bills with
 * letter format layout and multi-language support.
 */
@OptIn(ExperimentalTime::class)
class PdfGenerationServiceImpl(
    private val repository: DocumentRepository,
    private val shopInfoService: ShopInfoService,
    private val configManager: IShopConfigurationManager,
    private val kassenbuchLinks: DocumentKassenbuchLinkResolver,
) : PdfGenerationService {
    private val logger = LoggerFactory.getLogger(PdfGenerationServiceImpl::class.java)

    /**
     * Generates a compact receipt-style PDF from a bill for IPP printing.
     */
    override suspend fun generateBillReceiptPdf(
        bill: BillDto.BillResponseDto,
        shopHeader: ShopHeaderData,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                generateBillReceiptPdfBytes(bill, shopHeader)
            } catch (e: IOException) {
                logger.error("generateBillReceiptPdf: I/O failure", e)
                throw pdfGenerationFailed(e.message ?: "I/O failure", cause = e)
            } catch (e: Exception) {
                logger.error("generateBillReceiptPdf: failed to generate bill receipt PDF", e)
                throw pdfGenerationFailed(e.message ?: "Render failed", cause = e)
            }
        }

    /**
     * Generates a compact receipt-style PDF from an order for IPP printing.
     */
    override suspend fun generateOrderReceiptPdf(
        order: OrderDto.OrderResponseDto,
        takeAway: Boolean,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                generateOrderReceiptPdfBytes(order, takeAway)
            } catch (e: IOException) {
                logger.error("generateOrderReceiptPdf: I/O failure", e)
                throw pdfGenerationFailed(e.message ?: "I/O failure", cause = e)
            } catch (e: Exception) {
                logger.error("generateOrderReceiptPdf: failed to generate order receipt PDF", e)
                throw pdfGenerationFailed(e.message ?: "Render failed", cause = e)
            }
        }

    /**
     * Generates a compact reference print PDF from open order items for IPP printing.
     */
    override suspend fun generateReferencePrintPdf(
        shopHeader: ShopHeaderData,
        tableNumber: String,
        employeeNumber: String,
        dateTime: String,
        items: List<OrderItemDto.OrderItemResponseDto>,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                generateReferencePrintPdfBytes(shopHeader, tableNumber, employeeNumber, dateTime, items)
            } catch (e: IOException) {
                logger.error("generateReferencePrintPdf: I/O failure", e)
                throw pdfGenerationFailed(e.message ?: "I/O failure", cause = e)
            } catch (e: Exception) {
                logger.error("generateReferencePrintPdf: failed", e)
                throw pdfGenerationFailed(e.message ?: "Render failed", cause = e)
            }
        }

    /**
     * Generates an Eigenbeleg PDF, saves to disk, and creates a database record.
     *
     * When [correctsBelegNr] is non-blank the PDF is rendered as a Storno-Eigenbeleg
     * (see [generateEigenbelegPdfBytes] for layout details).
     */
    override suspend fun generateEigenbelegPdf(
        belegNr: String,
        date: String,
        entryType: String,
        amount: String,
        description: String,
        employeeId: UUID,
        correctsBelegNr: String?,
        correctsDate: String?,
        reason: String?,
    ): DocumentDto.DocumentResponseDto {
        val shopInfo =
            shopInfoService.getShopInfoById(1L)
                ?: throw resourceNotFound("Shop info not found")

        // Generate PDF bytes
        val pdfBytes =
            withContext(Dispatchers.IO) {
                try {
                    generateEigenbelegPdfBytes(
                        belegNr = belegNr,
                        date = date,
                        entryType = entryType,
                        amount = amount,
                        description = description,
                        shopName = shopInfo.shopName,
                        shopAddress = shopInfo.shopAddress,
                        correctsBelegNr = correctsBelegNr,
                        correctsDate = correctsDate,
                        reason = reason,
                    )
                } catch (e: IOException) {
                    logger.error("generateEigenbelegPdf: I/O failure rendering PDF for KB-{}", belegNr, e)
                    throw pdfGenerationFailed(e.message ?: "I/O failure", cause = e)
                } catch (e: Exception) {
                    logger.error("generateEigenbelegPdf: failed to render Eigenbeleg PDF for KB-{}", belegNr, e)
                    throw pdfGenerationFailed(e.message ?: "Render failed", cause = e)
                }
            }

        // Save to disk and create DB record
        val document =
            savePdfToDisk(
                pdfBytes = pdfBytes,
                documentType = "EIGENBELEG",
                identifier = "Nr$belegNr",
                employeeId = employeeId,
            )

        logger.info(
            "generateEigenbelegPdf: generated Eigenbeleg PDF for KB-{}: {}",
            belegNr,
            document.filename,
        )
        // An Eigenbeleg / Z-Bon PDF is never an INVOICE nor a BANK_STATEMENT, so `toDto` would
        // discard either aggregate we looked up. Passing null keeps this out of the generation
        // transaction's query budget; neither parameter has a default precisely so this stays a
        // stated decision rather than a silent one.
        return toDto(document, kassenbuchLinks, invoice = null, bankStatement = null)
    }

    /**
     * Generates a Z-Bon report PDF, saves to disk, and creates a database record.
     */
    override suspend fun generateZBonReportPdf(
        zBon: ZBonDto.ZBonResponseDto,
        employeeId: UUID,
        kassenbuchHeader: KassenbuchHeader?,
    ): DocumentDto.DocumentResponseDto {
        // Generate PDF bytes
        val pdfBytes =
            withContext(Dispatchers.IO) {
                try {
                    generateZBonReportPdfBytes(zBon)
                } catch (e: IOException) {
                    logger.error("generateZBonReportPdf: I/O failure rendering Z-Bon PDF for #{}", zBon.id, e)
                    throw pdfGenerationFailed(e.message ?: "I/O failure", cause = e)
                } catch (e: Exception) {
                    logger.error("generateZBonReportPdf: failed to render Z-Bon PDF for #{}", zBon.id, e)
                    throw pdfGenerationFailed(e.message ?: "Render failed", cause = e)
                }
            }

        // Save to disk and create DB record
        val document =
            savePdfToDisk(
                pdfBytes = pdfBytes,
                documentType = "ZBON_REPORT",
                identifier = "Nr${zBon.zbonNummer}",
                employeeId = employeeId,
            )

        // Link PDF to kassenbuch header if provided
        if (kassenbuchHeader != null) {
            kassenbuchHeader.linkedDocumentId = document.id.value
            logger.info(
                "generateZBonReportPdf: linked Z-Bon PDF {} to KB header {}",
                document.id,
                kassenbuchHeader.id.value,
            )
        }
        logger.info(
            "generateZBonReportPdf: generated Z-Bon PDF for Z-Bon #{}: {}",
            zBon.id,
            document.filename,
        )
        // An Eigenbeleg / Z-Bon PDF is never an INVOICE nor a BANK_STATEMENT, so `toDto` would
        // discard either aggregate we looked up. Passing null keeps this out of the generation
        // transaction's query budget; neither parameter has a default precisely so this stays a
        // stated decision rather than a silent one.
        return toDto(document, kassenbuchLinks, invoice = null, bankStatement = null)
    }

    /**
     * Renders an outgoing invoice from its snapshot and archives it.
     *
     * Reuses [savePdfToDisk] rather than adding a second storage path: hashing, the month folder and
     * the `Document` row are already solved there, and two archival paths would drift.
     */
    override suspend fun generateAndStoreSalesInvoicePdf(
        data: SalesInvoicePdfData,
        employeeId: UUID,
    ): Document {
        val pdfBytes = renderSalesInvoicePdf(data)
        return savePdfToDisk(
            pdfBytes = pdfBytes,
            documentType = DocumentType.OUTGOING_INVOICE.name,
            identifier = data.invoiceNumber,
            employeeId = employeeId,
        )
    }

    /**
     * Maps the invoice to the syntax-neutral model and runs the structural then required-field
     * checks, returning the first problem or null. Pure read — runs in the caller's transaction so
     * the mapper can follow the invoice's lazy `precedingSalesInvoice` reference.
     */
    override suspend fun validateSalesInvoiceEInvoice(
        invoice: SalesInvoice,
        lines: List<SalesInvoiceLine>,
        vatBreakdown: List<SalesInvoiceVatBreakdown>,
    ): String? {
        // Validation runs BEFORE the §14 number is consumed, so a rejected issue leaves no gap in
        // the sequence — but that means a DRAFT carries no invoice number yet. The number's presence
        // (BT-1) is guaranteed by the issue flow that assigns one immediately after this passes, so
        // we validate the mappable CONTENT with a placeholder standing in for the number-to-be. Every
        // other required field (seller/buyer identity, buyer address, lines, VAT breakdown, currency,
        // type code) and the total/VAT invariants are the real values off the invoice.
        val mapped = SalesInvoiceToEInvoice.map(invoice, lines, vatBreakdown)
        val einvoice = if (mapped.invoiceNumber.isBlank()) mapped.copy(invoiceNumber = "PENDING-ISSUE") else mapped
        return EInvoiceInvariants.check(einvoice)
            ?: En16931RequiredFields.check(einvoice)
            ?: unassignedCountry(einvoice)
            ?: En16931AmountRules.check(einvoice)
    }

    /**
     * [En16931RequiredFields] checks the code's shape; this checks it is an assigned code, so a
     * two-letter typo such as `DX` is refused before the number is consumed instead of shipped.
     */
    private fun unassignedCountry(einvoice: EInvoice): String? {
        val parties = listOfNotNull("seller country (BT-40)" to einvoice.seller, einvoice.buyer?.let { "buyer country (BT-55)" to it })
        val (term, party) = parties.firstOrNull { (_, party) -> CountryCodeNormalizer.toIso2(party.country) == null } ?: return null
        return "$term '${party.country}' is not a recognised ISO 3166-1 alpha-2 country code"
    }

    /**
     * Serializes the EN 16931 CII, embeds it in the ZUGFeRD PDF/A-3 letter, and archives it exactly
     * as [generateAndStoreSalesInvoicePdf] does — same `OUTGOING_INVOICE` type and storage path, so
     * the two archival routes never drift.
     */
    override suspend fun generateAndStoreSalesInvoiceZugferdPdf(
        data: SalesInvoicePdfData,
        invoice: SalesInvoice,
        lines: List<SalesInvoiceLine>,
        vatBreakdown: List<SalesInvoiceVatBreakdown>,
        employeeId: UUID,
    ): Document {
        val einvoice = SalesInvoiceToEInvoice.map(invoice, lines, vatBreakdown)
        val ciiXml = CiiInvoiceWriter.write(einvoice)
        val pdfBytes = renderSalesInvoiceZugferdPdf(data, ciiXml)
        return savePdfToDisk(
            pdfBytes = pdfBytes,
            documentType = DocumentType.OUTGOING_INVOICE.name,
            identifier = data.invoiceNumber,
            employeeId = employeeId,
        )
    }

    /**
     * Saves PDF bytes to disk and creates a Document database record.
     */
    private suspend fun savePdfToDisk(
        pdfBytes: ByteArray,
        documentType: String,
        identifier: String,
        employeeId: UUID,
    ): Document {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val contentHash = computeHash(pdfBytes)

        // Try to resolve enum type for filename/path generation
        val enumType = DocumentType.fromStringOrNull(documentType)

        // Generate filename and path
        val filename =
            if (enumType != null) {
                FileStorageHelper.generateFilename(
                    type = enumType,
                    timestamp = now,
                    contentHash = contentHash,
                    identifier = identifier,
                )
            } else {
                FileStorageHelper.generateFilename(documentType, now, identifier)
            }

        val documentStoragePath = configManager.getDocumentStoragePath()
        val absolutePath =
            if (enumType != null) {
                FileStorageHelper.generateFilePath(documentStoragePath, enumType, now, filename)
            } else {
                FileStorageHelper.generateFilePath(documentStoragePath, documentType, now, filename)
            }
        val relativePath =
            if (enumType != null) {
                FileStorageHelper.generateRelativePath(enumType, now, filename)
            } else {
                FileStorageHelper.generateRelativePath(documentType, now, filename)
            }

        // Ensure directory exists and write file
        withContext(Dispatchers.IO) {
            try {
                if (enumType != null) {
                    FileStorageHelper.ensureMonthDirectoryExists(documentStoragePath, enumType, now)
                } else {
                    FileStorageHelper.ensureMonthDirectoryExists(documentStoragePath, documentType, now)
                }
                File(absolutePath).writeBytes(pdfBytes)
            } catch (e: IOException) {
                logger.error("savePdfToDisk: I/O failure writing PDF to {}", absolutePath, e)
                throw pdfGenerationFailed(e.message ?: "I/O failure writing PDF", cause = e)
            }
        }

        // Create database record within transaction
        return repository.create(
            filename = filename,
            fileType = documentType,
            filePath = relativePath,
            fileSize = pdfBytes.size.toLong(),
            uploadedAt = now,
            uploadedBy = employeeId,
            contentHash = contentHash,
            mediaType = "application/pdf",
        )
    }

    /**
     * Generates a simple test page PDF for IPP printer testing.
     */
    override suspend fun generateTestPage(
        locationName: String,
        printerUri: String,
        timestamp: String,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            generateTestPagePdfBytes(
                locationName = locationName,
                printerUri = printerUri,
                timestamp = timestamp,
            )
        }

    /**
     * Computes SHA-256 hash of byte array content.
     */
    private fun computeHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // --- PosException helpers ---

    private fun pdfGenerationFailed(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.INTERNAL_ERROR,
        HttpStatusCode.InternalServerError,
        "PDF generation failed: $msg",
        cause = cause,
    )

    private fun resourceNotFound(what: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            what,
        )
}
