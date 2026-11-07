package org.example.project.features.document.data.service

import org.example.project.core.domain.dto.document.DocumentSummaryDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.features.document.domain.mapper.toSummaryDto
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.models.linkedDocument
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.pdf.generator.generateKassenmeldungPdfBytes
import org.example.project.pdf.generator.generateZBonReportPdfBytes
import java.util.UUID

/**
 * The accounting side of [KassenbuchDocuments]: a thin delegation to the document services, so the
 * fiscal callers see the same documents, files and transactions as when they called those services
 * themselves. Nothing here opens a transaction; the delegates keep their own behaviour.
 */
class KassenbuchDocumentsImpl(
    private val pdfGenerationService: PdfGenerationService,
    private val documentManagementService: DocumentManagementService,
) : KassenbuchDocuments {
    override suspend fun createEigenbeleg(
        belegNr: String,
        date: String,
        entryType: String,
        amount: String,
        description: String,
        employeeId: UUID,
        correctsBelegNr: String?,
        correctsDate: String?,
        reason: String?,
    ): Long =
        pdfGenerationService
            .generateEigenbelegPdf(
                belegNr = belegNr,
                date = date,
                entryType = entryType,
                amount = amount,
                description = description,
                employeeId = employeeId,
                correctsBelegNr = correctsBelegNr,
                correctsDate = correctsDate,
                reason = reason,
            ).id

    override suspend fun storeZBonReport(
        zBon: ZBonDto.ZBonResponseDto,
        employeeId: UUID,
        kassenbuchHeader: KassenbuchHeader?,
    ): Long = pdfGenerationService.generateZBonReportPdf(zBon = zBon, employeeId = employeeId, kassenbuchHeader = kassenbuchHeader).id

    override suspend fun copyDocument(
        sourceDocumentId: Long,
        copiedBy: UUID,
    ): Long = documentManagementService.copyDocument(sourceDocumentId, copiedBy)

    override suspend fun documentBytes(documentId: Long): ByteArray = documentManagementService.getDocumentFile(documentId).readBytes()

    override fun documentExists(documentId: Long): Boolean = Document.findById(documentId) != null

    override fun linkedDocumentOf(header: KassenbuchHeader): DocumentSummaryDto? = header.linkedDocument?.toSummaryDto()

    override fun storedZBonReportId(entries: Iterable<KassenbuchHeader>): Long? =
        entries
            .mapNotNull { it.linkedDocument }
            .firstOrNull { it.fileType == DocumentType.ZBON_REPORT.name }
            ?.id
            ?.value

    override fun zBonReportPdf(zBon: ZBonDto.ZBonResponseDto): ByteArray = generateZBonReportPdfBytes(zBon)

    override fun kassenmeldungPdf(data: ShopConfigurationDto.KassenMeldungResponseDto): ByteArray = generateKassenmeldungPdfBytes(data)
}
