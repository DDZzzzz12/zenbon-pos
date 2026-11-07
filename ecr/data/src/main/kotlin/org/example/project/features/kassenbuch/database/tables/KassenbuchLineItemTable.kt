/**
 * Database table definition for Kassenbuch Line Items.
 *
 * DATEV-compliant line item table for multi-VAT support.
 * Each line item belongs to a header and contains amount/VAT-specific fields.
 * Cascade deletes ensure line items are removed when header is deleted.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.kassenbuch.database.tables

import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * DATEV-compliant table for storing cash book entry line items.
 *
 * Represents individual VAT line items within a single receipt.
 * Each line has its own amount, VAT rate, and running sub-balance.
 *
 * Key characteristics:
 * - Belongs to a single [KassenbuchHeaderTable] header
 * - Contains per-line DATEV fields (amount, VAT rate)
 * - Tracks incremental sub-balance for running total display
 * - Cascade delete when parent header is deleted
 */
object KassenbuchLineItemTable : LongIdTable("kassenbuch_line_item", "id") {
    /** Reference to parent header (cascade delete) */
    val headerId =
        reference(
            name = "header_id",
            foreign = KassenbuchHeaderTable,
            onDelete = ReferenceOption.CASCADE,
        )

    /** Line number within the parent header (0-indexed) */
    val lineNumber = integer("line_number")

    // === Per-Line DATEV Fields ===

    /**
     * VorzBetrag - Signed transaction amount as DECIMAL(12,2).
     *
     * - Positive values: Income, receipts, cash inflows
     * - Negative values: Expenses, payments, cash outflows
     * - DATEV formatting ("+100,50" / "-75,25") is applied at export time only
     */
    val vorzBetrag = decimal("vorz_betrag", 12, 2)

    /**
     * UStSatz - VAT (Umsatzsteuer) rate percentage
     *
     * German VAT rates applied to the transaction:
     * - "19" or "19,00": Standard VAT rate (19%)
     * - "7" or "7,00": Reduced VAT rate (7%) for food, books, etc.
     * - "0" or "0,00": VAT-exempt transactions
     * - Empty/null: No VAT applicable
     */
    val ustSatz = varchar("ust_satz", 5).nullable()

    /**
     * Belegtext - Optional per-line description
     *
     * Can override or supplement the header's belegtext for this specific line.
     * Useful for itemized receipts where each line has a different description.
     */
    val belegtext = varchar("belegtext", 60).nullable()

    /**
     * Kostmenge - Cost quantity or units for this line item
     *
     * Quantitative measure associated with this specific line:
     * - Number of items sold (e.g., 5 coffees)
     * - Hours worked
     * - Kilograms, liters, or other units
     */
    val kostmenge = decimal("kostmenge", precision = 16, scale = 4).nullable()

    /**
     * Skonto - Cash discount amount in EUR for this line item
     *
     * Discount applied specifically to this line:
     * - Early payment discounts
     * - Volume discounts
     * - Promotional discounts
     */
    val skonto = decimal("skonto", precision = 10, scale = 2).nullable()

    /**
     * SubBalance - Running balance after this specific line item
     *
     * Used for displaying incremental balance changes in multi-line entries.
     * Shows the intermediate balance state as each line is applied.
     */
    val subBalance = decimal("sub_balance", precision = 19, scale = 4)

    /**
     * Line kind classification (STANDARD/PFAND/GUTSCHEIN/NON_CASH_PAYMENT).
     * Drives per-line DATEV account resolution at export. Legacy rows default
     * to STANDARD; historical Z-Bon "Unbare Zahlung" rows are backfilled by V88.
     */
    val kind =
        enumerationByName("kind", 30, KassenbuchLineKind::class)
            .default(KassenbuchLineKind.STANDARD)

    /**
     * Payment type behind a NON_CASH_PAYMENT line (null on legacy/backfilled
     * rows — those resolve whole-line to legacy accounts at export).
     */
    val paymentTypeId = optReference("payment_type_id", PaymentTypeTable)

    init {
        // Unique constraint: one line number per header
        uniqueIndex(headerId, lineNumber)

        // Performance index for header lookup
        index(false, headerId)
    }
}
