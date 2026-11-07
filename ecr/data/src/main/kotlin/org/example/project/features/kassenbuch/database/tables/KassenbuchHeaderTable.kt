/**
 * Database table definition for Kassenbuch Header (cash book entries).
 *
 * DATEV-compliant cash book header table representing a single logical receipt.
 * Each header can have multiple line items for multi-VAT support.
 * PDF documents link to headers (not individual lines) for clean document association.
 *
 * Fields match German DATEV standard with proper field lengths and formats.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.kassenbuch.database.tables

import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * DATEV-compliant table for storing cash book entry headers.
 *
 * Represents the parent record for a single logical receipt.
 * Line items are stored in [KassenbuchLineItemTable] with header_id reference.
 *
 * Key characteristics:
 * - One header = one logical receipt with one belegNr
 * - Multiple VAT line items reference this header
 * - PDF documents link to headers for clean association
 * - Shared DATEV fields apply to all line items
 */
object KassenbuchHeaderTable : LongIdTable("kassenbuch_header", "id") {
    /** Reference to shop this entry belongs to */
    val shopId = reference("shop_id", ShopInfoTable)

    /** The cash book this entry belongs to. Fiscal book = the register; others are non-fiscal. */
    val cashBookId = reference("cash_book_id", CashBookTable)

    /**
     * The other side of a Geldtransit.
     *
     * **Null means Bank** — exactly what `CASH_TRANSFER_IN/OUT` meant before cash books
     * existed, so every pre-migration row stays correct with no backfill. A non-null value is
     * a book-to-book transfer.
     */
    val counterpartyBookId = optReference("counterparty_book_id", CashBookTable)

    /**
     * Shared by both legs of a transfer; null on ordinary entries.
     *
     * A shared id rather than a self-reference because the relationship is symmetric — a
     * self-reference would need each row to know the other's id at insert, forcing a second
     * update pass. (`correctsHeaderId` is a self-reference because corrections are asymmetric.)
     */
    val transferId = varchar("transfer_id", 36).nullable()

    /** Reference to employee who created this entry */
    val employeeId = reference("employee_id", EmployeeTable.id)

    /** Type of cash book entry (CASH_SALE, EXPENSE, WITHDRAWAL, etc.) */
    val entryType = enumerationByName<KassenbuchEntryType>("entry_type", 50)

    /** belegNr - Document number in YYYY-MM-NNNN format for audit trail */
    val belegNr = varchar("beleg_nr", 20)

    /** BelegDatum - Document date (DD.MM.YYYY format, e.g. "01.02.2026") */
    val belegDatum = varchar("beleg_datum", 10)

    // === Shared DATEV Fields (apply to all line items) ===

    /** Belegtext - Document text (DATEV: max 60 chars, alphanumeric + special chars) */
    val belegtext = varchar("belegtext", 60)

    /** Währung - Currency code (DATEV: Only "EUR" allowed, max 3 chars) */
    val waehrung = varchar("waehrung", 3).default("EUR")

    /** BU - Booking key (DATEV: max 4 chars, numeric) */
    val bu = varchar("bu", 4).nullable()

    /** Gegenkonto - Counter account (DATEV: SKL length + 1, max 5 chars for SKL 4) */
    val gegenkonto = varchar("gegenkonto", 5).nullable()

    /** Kost1 - Cost center 1 (DATEV: max 36 chars, alphanumeric + special chars) */
    val kost1 = varchar("kost1", 36).nullable()

    /** Kost2 - Cost center 2 (DATEV: max 36 chars, alphanumeric + special chars) */
    val kost2 = varchar("kost2", 36).nullable()

    /** Nachricht - Message/Note (DATEV: max 120 chars, alphanumeric + special chars) */
    val nachricht = varchar("nachricht", 120).nullable()

    /**
     * Why an entry was booked with a Beleg date older than the shop's limit — set only when that
     * override was used (capability `CASHBOOK_BACKDATE`, directly or through a manager's approval).
     * Null on every ordinary entry. Kept out of `nachricht`, which is a DATEV export field.
     */
    val lateBookingReason = varchar("late_booking_reason", 500).nullable()

    // === Computed Totals ===

    /** Total amount = sum of all line item amounts */
    val totalAmount = decimal("total_amount", precision = 19, scale = 4)

    /** Running balance after this entry */
    val currentBalance = decimal("current_balance", precision = 19, scale = 4)

    // === TSE Integration Fields ===

    /** Reference to TSE transaction for German fiscal compliance (KassenSichV) */
    val tseTransactionId = reference("tse_transaction_id", TseTransactionTable).nullable()

    /** Reference to Z-Bon period this entry belongs to */
    val zBonId = reference("zbon_id", ZBonTable).nullable()

    /** TSE signing status for tracking queue-based signing (Nachsignieren) */
    val tseSigningStatus =
        enumerationByName<TseSigningStatus>("tse_signing_status", 20)
            .default(TseSigningStatus.PENDING)

    // === Document Link ===

    /**
     * Direct link to the PDF document (nullable one-to-one, unique to prevent same doc on multiple entries).
     *
     * A plain id column: documents belong to the accounting area, which sits above this one, so the
     * foreign key to `documents.id` (`fk_kassenbuch_header_linked_document_id__id`) is declared by the
     * host in `CrossAreaForeignKeys`, not here.
     */
    val linkedDocumentId = long("linked_document_id").nullable().uniqueIndex()

    // === Correction Link ===

    /** The original entry this row corrects (null for ordinary entries). Self-reference. */
    val correctsHeaderId = optReference("corrects_header_id", KassenbuchHeaderTable)

    /** REVERSAL or RE_ENTRY (null for ordinary entries). */
    val correctionRole =
        enumerationByName<CorrectionRole>("correction_role", 20).nullable()

    // === System Fields ===

    /**
     * Whether entry is locked (its period is closed).
     *
     * **Exactly one writer per book class** — the two never overlap, so they cannot race:
     *  * fiscal books: the sweep in `ZBonServiceImpl.createReport`
     *  * non-fiscal books: `CashBookCloseService.close`
     *
     * (Before cash books existed this read "exactly one writer, the Z-Bon sweep". The rule is
     * unchanged in substance — no row is reachable by both writers.)
     *
     * `OpenBusinessCheck` reads `isLocked == false` as "cash movement not yet in a closing"
     * (round-7 C9), and there is no delete path for entries — so anything that writes this
     * column outside those two places decides whether a fiscal Stammdaten edit is allowed.
     * Do not add a third writer.
     *
     * Note `isLocked` and [zBonId] are NOT interchangeable even though the sweep writes both:
     * `zBon` is assigned only when it is still null, and some test fixtures set `zBon` without
     * `isLocked`. `isLocked` is the authoritative flag. Non-fiscal entries never get a `zBonId`
     * at all.
     */
    val isLocked = bool("is_locked").default(false)

    /** True on the Z-Bon's own aggregate CASH_SALE entry (excluded from DSFinV-K, spec §1). */
    val isZbonAggregate = bool("is_zbon_aggregate").default(false)

    /**
     * Entry creation datetime — stamped PER INSERT.
     *
     * MUST be `clientDefault { }`, never `default(currentTimeStamp())`: `Column.default(value)`
     * takes a VALUE evaluated once at table-object init, which froze one wall-clock instant into
     * the DDL default and gave every row the same fabricated creation time (audit KABS-1).
     * `clientDefault` nulls `dbDefaultValue`, so Exposed evaluates the lambda and writes the
     * column explicitly on every insert.
     *
     * Deliberately NOT `defaultExpression(CurrentDateTime)`: that evaluates in the DB server's
     * timezone and bypasses the shop-configured timeProvider, which is the fiscal clock everywhere
     * else in this codebase.
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }

    init {
        // Performance indexes
        index(false, shopId)
        index(false, belegNr)
        index(false, createdAt)
        index(false, employeeId)
        index("idx_kassenbuch_header_cash_book", false, cashBookId)
        index("idx_kassenbuch_header_transfer", false, transferId)
        index("idx_kassenbuch_header_corrects_header_id", false, correctsHeaderId)
    }
}
