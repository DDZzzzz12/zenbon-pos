package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.document.DocumentSummaryDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import java.util.UUID

/**
 * The documents the Kassenbuch and the Z-Bon closing need from the accounting area: the Eigenbelege
 * and the Z-Bon report they store and link, the stored files they read back, and the Kassenmeldung PDF.
 *
 * Document storage, PDF rendering and the `Document` model belong to accounting (spec §13); the
 * fiscal code reaches them only through this port, implemented in accounting. Documents are named by
 * their id here, never by the accounting `Document` entity or its DTOs.
 *
 * Methods marked "ambient transaction" read or write rows and must be called inside the caller's
 * transaction; an implementation never opens one of its own around them, so the stored document
 * commits or rolls back with the Kassenbuch entry that links it.
 */
interface KassenbuchDocuments {
    /**
     * Renders an Eigenbeleg (self-issued receipt) for a Kassenbuch entry, stores it and returns the new
     * document's id. With a non-blank [correctsBelegNr] it is a Storno-Eigenbeleg of that entry, naming
     * [correctsDate] and [reason]. Ambient transaction.
     */
    suspend fun createEigenbeleg(
        belegNr: String,
        date: String,
        entryType: String,
        amount: String,
        description: String,
        employeeId: UUID,
        correctsBelegNr: String? = null,
        correctsDate: String? = null,
        reason: String? = null,
    ): Long

    /** Renders the Z-Bon report, stores it linked to [kassenbuchHeader] and returns its document id. Ambient transaction. */
    suspend fun storeZBonReport(
        zBon: ZBonDto.ZBonResponseDto,
        employeeId: UUID,
        kassenbuchHeader: KassenbuchHeader?,
    ): Long

    /** Copies a stored document (file and row) and returns the copy's id; a correction's re-entry gets its own. */
    suspend fun copyDocument(
        sourceDocumentId: Long,
        copiedBy: UUID,
    ): Long

    /** The stored file of a document, as bytes; throws when the row or the file is missing. */
    suspend fun documentBytes(documentId: Long): ByteArray

    /** Whether a document with this id exists. Ambient transaction. */
    fun documentExists(documentId: Long): Boolean

    /** The summary of the document linked to [header], or null. Ambient transaction. */
    fun linkedDocumentOf(header: KassenbuchHeader): DocumentSummaryDto?

    /** The id of the first Z-Bon report document linked to one of [entries], in their order, or null. Ambient transaction. */
    fun storedZBonReportId(entries: Iterable<KassenbuchHeader>): Long?

    /** Renders the Z-Bon report PDF without storing it (the download's fallback when no stored file is left). */
    fun zBonReportPdf(zBon: ZBonDto.ZBonResponseDto): ByteArray

    /** Renders the Kassenmeldung (§ 146a Abs. 4 AO) record as a PDF. */
    fun kassenmeldungPdf(data: ShopConfigurationDto.KassenMeldungResponseDto): ByteArray
}
