/**
 * Domain model for Kassenbuch Line Item.
 *
 * Represents a single VAT line item within a Kassenbuch header.
 * Each line item contains its own amount, VAT rate, and contributes to
 * the total amount of the parent header.
 *
 * Provides object-relational mapping for multi-VAT receipt support
 * in accordance with DATEV requirements.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.kassenbuch.domain.models

import org.example.project.features.kassenbuch.database.tables.KassenbuchLineItemTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.utils.formatter.formatVorzBetragForDatev
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import java.math.BigDecimal

/**
 * DATEV-compliant cash book line item entity.
 *
 * Represents an individual VAT line within a receipt.
 * Multiple line items with different VAT rates can belong to a single header.
 *
 * Key relationships:
 * - Belongs to [KassenbuchHeader] parent (cascade delete)
 */
class KassenbuchLineItem(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<KassenbuchLineItem>(KassenbuchLineItemTable)

    /** Reference to parent header */
    var header by KassenbuchHeader referencedOn KassenbuchLineItemTable.headerId

    /** Line number within the parent header (0-indexed) */
    var lineNumber by KassenbuchLineItemTable.lineNumber

    // === Per-Line DATEV Fields ===

    /**
     * VorzBetrag - Signed transaction amount as BigDecimal.
     *
     * - Positive values: Income, receipts, cash inflows
     * - Negative values: Expenses, payments, cash outflows
     * - DATEV formatting ("+100,50" / "-75,25") is applied at export time only
     */
    var vorzBetrag by KassenbuchLineItemTable.vorzBetrag

    /**
     * UStSatz - VAT (Umsatzsteuer) rate percentage
     *
     * German VAT rates applied to the transaction:
     * - "19" or "19,00": Standard VAT rate (19%)
     * - "7" or "7,00": Reduced VAT rate (7%) for food, books, etc.
     * - "0" or "0,00": VAT-exempt transactions
     * - Empty/null: No VAT applicable
     */
    var ustSatz by KassenbuchLineItemTable.ustSatz

    /**
     * Belegtext - Optional per-line description
     *
     * Can override or supplement the header's belegtext for this specific line.
     * If null, uses the header's belegtext.
     */
    var belegtext by KassenbuchLineItemTable.belegtext

    /**
     * Kostmenge - Cost quantity or units for this line item
     *
     * Quantitative measure associated with this specific line.
     */
    var kostmenge by KassenbuchLineItemTable.kostmenge

    /**
     * Skonto - Cash discount amount in EUR for this line item
     *
     * Discount applied specifically to this line.
     */
    var skonto by KassenbuchLineItemTable.skonto

    /**
     * SubBalance - Running balance after this specific line item
     *
     * Used for displaying incremental balance changes in multi-line entries.
     */
    var subBalance by KassenbuchLineItemTable.subBalance

    /** Line kind classification — see [KassenbuchLineItemTable.kind]. */
    var kind by KassenbuchLineItemTable.kind

    /** Payment type behind a NON_CASH_PAYMENT line, null otherwise/backfilled. */
    var paymentType by PaymentType optionalReferencedOn KassenbuchLineItemTable.paymentTypeId

    // === Business Logic Methods ===

    /**
     * Returns the numeric amount (signed BigDecimal).
     *
     * @return Numeric amount as BigDecimal (can be negative)
     */
    fun getNumericAmount(): BigDecimal = vorzBetrag

    /**
     * Gets the effective belegtext for this line.
     *
     * Combines header belegtext with line-specific belegtext if available.
     * Truncates to DATEV 60-character limit.
     *
     * @return Combined description truncated to 60 characters
     */
    fun getEffectiveBelegtext(): String =
        if (belegtext.isNullOrBlank()) {
            // Use header text only
            header.belegtext.take(60)
        } else {
            // Combine: "Header - Line" format
            "${header.belegtext} - $belegtext".take(60)
        }

    /**
     * Generates DATEV export line for this specific line item.
     *
     * Combines parent header's shared fields with this line's specific fields.
     *
     * @return Formatted DATEV export string
     */
    fun toDatevExportLine(): String {
        val h = header
        val fields =
            listOf(
                h.waehrung,
                formatVorzBetragForDatev(vorzBetrag),
                h.belegNr,
                h.belegDatum,
                getEffectiveBelegtext(),
                ustSatz ?: "",
                h.bu ?: "",
                h.gegenkonto ?: "",
                h.kost1 ?: "",
                h.kost2 ?: "",
                kostmenge?.toString() ?: "",
                skonto?.toString() ?: "",
                h.nachricht ?: "",
            )
        return fields.joinToString(";")
    }
}
