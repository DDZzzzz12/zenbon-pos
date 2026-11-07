/**
 * Domain model for Kassenbuch Header (cash book entry).
 *
 * Represents a single logical receipt in the cash book with DATEV compliance features.
 * Each header can have multiple line items for multi-VAT support.
 * PDF documents link to headers for clean document association.
 *
 * Provides object-relational mapping and business logic for cash-related
 * transactions in accordance with German GoBD requirements.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.kassenbuch.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchLineItemTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.formatter.formatVorzBetragForDatev
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import java.math.BigDecimal

/**
 * DATEV-compliant cash book header entity for transaction recording.
 *
 * Manages cash transactions with complete DATEV field mapping,
 * VAT handling via line items, and account mapping for German accounting compliance.
 * Supports automatic entry generation from POS transactions.
 *
 * Key relationships:
 * - Has many [KassenbuchLineItem] children (cascade delete)
 * - Optional one-to-one link to a document via [linkedDocumentId]
 * - References [Employee], [ShopInfo], [TseTransaction], [ZBon]
 */
class KassenbuchHeader(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<KassenbuchHeader>(KassenbuchHeaderTable)

    /** Reference to shop this entry belongs to */
    var shop by ShopInfo referencedOn KassenbuchHeaderTable.shopId

    var cashBook by CashBook referencedOn KassenbuchHeaderTable.cashBookId
    var counterpartyBook by CashBook optionalReferencedOn KassenbuchHeaderTable.counterpartyBookId
    var transferId by KassenbuchHeaderTable.transferId

    /** Reference to employee who created this entry */
    var employee by Employee referencedOn KassenbuchHeaderTable.employeeId

    /** Type of cash book entry */
    var entryType by KassenbuchHeaderTable.entryType

    /** Sequential booking number for audit trail (unique per shop) */
    var belegNr by KassenbuchHeaderTable.belegNr

    /**
     * BelegDatum - Document date in DD.MM.YYYY format
     *
     * Format: DD.MM.YYYY (e.g., "01.02.2026" for February 1st, 2026)
     */
    var belegDatum by KassenbuchHeaderTable.belegDatum

    // === Shared DATEV Fields ===

    /**
     * Belegtext - Descriptive text explaining the transaction
     *
     * Clear, concise description of the business transaction.
     * Required field for tax audits and business analysis.
     */
    var belegtext by KassenbuchHeaderTable.belegtext

    /**
     * Währung - Currency code (ISO 4217 standard)
     *
     * Default value is "EUR" for German businesses.
     */
    var waehrung by KassenbuchHeaderTable.waehrung

    /**
     * BU - Buchungsschlüssel (Posting key for automatic account assignment)
     *
     * DATEV-specific codes for automated bookkeeping.
     */
    var bu by KassenbuchHeaderTable.bu

    /**
     * Gegenkonto - Counter account number
     *
     * The opposing account in double-entry bookkeeping.
     * Uses German standard chart of accounts (SKR03 or SKR04).
     */
    var gegenkonto by KassenbuchHeaderTable.gegenkonto

    /**
     * Kost1 - Primary cost center code
     *
     * Cost accounting dimension for business analysis.
     */
    var kost1 by KassenbuchHeaderTable.kost1

    /**
     * Kost2 - Secondary cost center code
     *
     * Additional cost accounting dimension for detailed analysis.
     */
    var kost2 by KassenbuchHeaderTable.kost2

    /**
     * Nachricht - Additional notes and internal messages
     *
     * Free-text field for supplementary information.
     */
    var nachricht by KassenbuchHeaderTable.nachricht

    /** Why the Beleg date is older than the shop's limit; set only on an override. */
    var lateBookingReason by KassenbuchHeaderTable.lateBookingReason

    // === Computed Totals ===

    /** Total amount = sum of all line item amounts */
    var totalAmount by KassenbuchHeaderTable.totalAmount

    /** Running balance after this entry */
    var currentBalance by KassenbuchHeaderTable.currentBalance

    // === TSE Integration Fields ===

    /**
     * Reference to TSE transaction for German fiscal compliance.
     *
     * Required by KassenSichV regulation for digital signing of Kassenbuch entries.
     * Contains the TSE signature, transaction number, and audit data.
     */
    var tseTransaction by TseTransaction optionalReferencedOn KassenbuchHeaderTable.tseTransactionId

    /**
     * Reference to the Z-Bon period this entry belongs to.
     *
     * Links the Kassenbuch entry to a specific Z-Report for DSFinV-K export.
     */
    var zBon by ZBon optionalReferencedOn KassenbuchHeaderTable.zBonId

    /** TSE signing status for queue-based signing (Nachsignieren) */
    var tseSigningStatus by KassenbuchHeaderTable.tseSigningStatus

    // === System Fields ===

    /** Whether entry is locked (from closed Z-Bon period) */
    var isLocked by KassenbuchHeaderTable.isLocked

    /** True on the Z-Bon's own aggregate entry (see [KassenbuchHeaderTable.isZbonAggregate]). */
    var isZbonAggregate by KassenbuchHeaderTable.isZbonAggregate

    /** Entry creation timestamp */
    var createdAt by KassenbuchHeaderTable.createdAt

    // === Relationships ===

    /**
     * Line items belonging to this header.
     *
     * Returns all [KassenbuchLineItem] records for this header.
     * Each line item represents a separate VAT amount within the receipt.
     */
    val lineItems by KassenbuchLineItem referrersOn KassenbuchLineItemTable.headerId

    /**
     * Id of the directly linked PDF document (nullable one-to-one).
     *
     * Replaces the old many-to-many junction table approach with a simpler direct FK. The document
     * itself is reached through the accounting extension
     * `org.example.project.features.document.domain.models.linkedDocument`.
     */
    var linkedDocumentId by KassenbuchHeaderTable.linkedDocumentId

    /** The original entry this row corrects (null for ordinary entries). */
    var correctsHeader by KassenbuchHeader optionalReferencedOn KassenbuchHeaderTable.correctsHeaderId

    /** REVERSAL or RE_ENTRY (null for ordinary entries). */
    var correctionRole by KassenbuchHeaderTable.correctionRole

    // === Business Logic Methods ===

    /**
     * Checks if this Kassenbuch header has a linked document.
     *
     * @return true if header has a linked document
     */
    fun hasLinkedDocument(): Boolean = linkedDocumentId != null

    /**
     * Gets the number of linked documents (0 or 1).
     *
     * @return 1 if linked to a document, 0 otherwise
     */
    fun getLinkedDocumentCount(): Int = if (linkedDocumentId != null) 1 else 0

    /**
     * Extracts numeric total amount from the stored value.
     *
     * @return Numeric amount as BigDecimal
     */
    fun getNumericTotalAmount(): BigDecimal = totalAmount

    /**
     * Gets the formatted booking text for DATEV export.
     *
     * @return Formatted booking text with transaction details
     */
    fun getFormattedBookingText(): String {
        val parts = mutableListOf<String>()

        if (belegtext.isNotBlank()) {
            parts.add(belegtext)
        }

        parts.add("RechNr: $belegNr")

        return parts.joinToString(" | ").take(500) // DATEV limit
    }

    /**
     * Checks if this entry can be exported to DATEV.
     *
     * @return true if entry is ready for export
     */
    fun canBeExported(): Boolean = belegtext.isNotBlank() && lineItems.count() > 0

    /**
     * Gets the DATEV date string for transaction date.
     *
     * Extracts DDMM from stored DD.MM.YYYY format.
     *
     * @return Date formatted for DATEV export (DDMM format)
     */
    fun getDatevDateString(): String = belegDatum.replace(".", "").take(4)

    /**
     * Generates DATEV export lines for all line items.
     *
     * Creates one export line per line item, using shared header fields
     * combined with per-line fields.
     *
     * @return List of formatted DATEV export strings
     */
    fun toDatevExportLines(): List<String> =
        lineItems.map { lineItem ->
            // Combine belegtext: use line item's if available, otherwise header's
            // Format: "Header text - Line text" or just "Header text"
            // Max length: 60 characters (DATEV limit)
            val combinedBelegtext = formatBelegtext(belegtext, lineItem.belegtext)

            val fields =
                listOf(
                    waehrung,
                    formatVorzBetragForDatev(lineItem.vorzBetrag),
                    belegNr,
                    getDatevDateString(),
                    combinedBelegtext,
                    lineItem.ustSatz ?: "",
                    bu ?: "",
                    gegenkonto ?: "",
                    kost1 ?: "",
                    kost2 ?: "",
                    lineItem.kostmenge?.toString() ?: "",
                    lineItem.skonto?.toString() ?: "",
                    nachricht ?: "",
                )
            fields.joinToString(";")
        }

    /**
     * Formats belegtext combining header and line item text.
     *
     * Combines header belegtext with optional line item belegtext,
     * respecting the DATEV 60-character limit.
     *
     * @param headerText Main description from header
     * @param lineText Optional per-line description
     * @return Combined text truncated to 60 characters
     */
    private fun formatBelegtext(
        headerText: String,
        lineText: String?,
    ): String =
        if (lineText.isNullOrBlank()) {
            // Use header text only, truncate to 60 chars
            headerText.take(60)
        } else {
            // Combine: "Header - Line" format
            val combined = "$headerText - $lineText"
            combined.take(60)
        }
}
