package org.example.project.features.salesinvoice.database.tables

import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.document.database.tables.DocumentTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * An invoice **we** issued — the AR root, and deliberately not a `direction` column on `invoice`.
 *
 * `invoice` means *what a counterparty asserted*, with our decisions quarantined in
 * `invoice_booking`. Here the roles invert: every field is ours, it is legally binding under §14
 * UStG, and it is **immutable once issued** — while [SalesInvoiceBookingTable] stays editable until
 * posted. A shared table would make that immutability rule conditional on a column value.
 *
 * Both party blocks are snapshots. `buyer_*` for the obvious reason, `seller_*` because `shop_info`
 * is editable and a shop that moves would otherwise silently reprint a 2027 invoice with a 2029
 * address.
 */
object SalesInvoiceTable : LongIdTable("sales_invoice", "id") {
    val shopId = reference("shop_id", ShopInfoTable)

    /**
     * Null until issued, unique once set. This is what makes the sequence gapless: a discarded draft
     * never consumed a number.
     */
    val invoiceNumber = varchar("invoice_number", 50).nullable().uniqueIndex()

    val status =
        enumerationByName<SalesInvoiceStatus>("status", 20)
            .default(SalesInvoiceStatus.DRAFT)

    val revenueOrigin = enumerationByName<RevenueOrigin>("revenue_origin", 20)

    /** BT-3 UNTDID 1001 — `380` Rechnung, `381` Gutschrift, `384` Korrektur. */
    val invoiceTypeCode = varchar("invoice_type_code", 10).default("380")

    /** BT-25 — the invoice a 381 or 384 reverses. */
    val precedingSalesInvoiceId =
        optReference("preceding_sales_invoice_id", SalesInvoiceTable, onDelete = ReferenceOption.RESTRICT)

    /**
     * The till bill this invoice was rendered from. RESTRICT, not SET NULL: losing the link would
     * strand an invoice that claims a sale it can no longer point at.
     */
    val billId = optReference("bill_id", BillTable, onDelete = ReferenceOption.RESTRICT)

    /** Nullable on purpose — a one-off guest gets a typed address, not a master-data record. */
    val debtorId = optReference("debtor_id", DebtorTable, onDelete = ReferenceOption.SET_NULL)

    val buyerName = varchar("buyer_name", 255)
    val buyerVatId = varchar("buyer_vat_id", 50).nullable()
    val buyerStreet = varchar("buyer_street", 255).nullable()
    val buyerZip = varchar("buyer_zip", 20).nullable()
    val buyerCity = varchar("buyer_city", 100).nullable()
    val buyerCountry = varchar("buyer_country", 10).nullable()
    val buyerEmail = varchar("buyer_email", 255).nullable()

    val sellerName = varchar("seller_name", 255).nullable()
    val sellerVatId = varchar("seller_vat_id", 50).nullable()
    val sellerTaxNumber = varchar("seller_tax_number", 50).nullable()
    val sellerStreet = varchar("seller_street", 255).nullable()
    val sellerZip = varchar("seller_zip", 20).nullable()
    val sellerCity = varchar("seller_city", 100).nullable()
    val sellerCountry = varchar("seller_country", 10).nullable()

    val issueDate = datetime("issue_date")

    /** Leistungszeitpunkt, §14 Abs. 4 Nr. 6. For a POS invoice this is the bill's date. */
    val serviceDate = datetime("service_date")
    val dueDate = datetime("due_date").nullable()
    val currency = varchar("currency", 3).default("EUR")

    val lineNetTotal = decimal("line_net_total", 19, 4).nullable()

    /** BT-107 — Σ document-level allowances; null when the invoice has none. */
    val allowanceTotal = decimal("allowance_total", 19, 4).nullable()

    /** BT-108 — Σ document-level charges; null when the invoice has none. */
    val chargeTotal = decimal("charge_total", 19, 4).nullable()
    val taxExclusiveTotal = decimal("tax_exclusive_total", 19, 4).nullable()
    val taxTotal = decimal("tax_total", 19, 4).nullable()
    val taxInclusiveTotal = decimal("tax_inclusive_total", 19, 4).nullable()

    /** BT-113. For a `POS_SETTLED` invoice this equals [taxInclusiveTotal] and [amountDue] is 0. */
    val prepaidAmount = decimal("prepaid_amount", 19, 4).nullable()

    /** BT-115. */
    val amountDue = decimal("amount_due", 19, 4).nullable()

    /** Residual, maintained by the allocation writer. 0 from birth for `POS_SETTLED`. */
    val openAmount = decimal("open_amount", 19, 4).nullable()

    val paymentTermsText = varchar("payment_terms_text", 255).nullable()

    /** Ours to choose, and printed as the Verwendungszweck — which is why AR matching beats AP. */
    val paymentReference = varchar("payment_reference", 255).nullable()
    val notes = text("notes").nullable()

    // --- TSE block: the ORIGINAL bill's signature, snapshotted at issue. -------------------------
    //
    // Under §6 KassenSichV the Beleg for a sale discloses this. When the guest walks out holding the
    // Rechnung, the Rechnung is that document. Snapshotted rather than read live so a reprint shows
    // the same block forever.
    val tseSerialNumber = varchar("tse_serial_number", 100).nullable()
    val tseTransactionNumber = long("tse_transaction_number").nullable()
    val tseSignatureCounter = long("tse_signature_counter").nullable()
    val tseSignature = varchar("tse_signature", 1000).nullable()
    val tseSignatureAlgorithm = varchar("tse_signature_algorithm", 100).nullable()
    val tseLogTimeFormat = varchar("tse_log_time_format", 31).nullable()
    val tseProcessType = varchar("tse_process_type", 100).nullable()
    val tseStartTime = datetime("tse_start_time").nullable()
    val tseEndTime = datetime("tse_end_time").nullable()
    val tseTimestamp = long("tse_timestamp").nullable()
    val tseStartTimestamp = long("tse_start_timestamp").nullable()
    val tseQrCodePayload = text("tse_qr_code_payload").nullable()

    val issuedAt = datetime("issued_at").nullable()
    val issuedBy = optReference("issued_by", EmployeeTable, onDelete = ReferenceOption.RESTRICT)
    val cancelledAt = datetime("cancelled_at").nullable()
    val cancelledBy = optReference("cancelled_by", EmployeeTable, onDelete = ReferenceOption.RESTRICT)
    val cancellationReason = varchar("cancellation_reason", 500).nullable()

    /** The archived PDF. Reprint serves this file; it never re-renders. */
    val documentId = optReference("document_id", DocumentTable, onDelete = ReferenceOption.SET_NULL)

    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").nullable()

    init {
        index("idx_sales_invoice_shop_id", false, shopId)
        index("idx_sales_invoice_status", false, status)
        index("idx_sales_invoice_bill_id", false, billId)
        index("idx_sales_invoice_debtor_id", false, debtorId)
        index("idx_sales_invoice_issue_date", false, issueDate)
        index("idx_sales_invoice_open_amount", false, openAmount)

        // One LIVE invoice per bill (V81). Partial, so a cancelled invoice frees the slot and a
        // corrected one can be issued. PostgreSQL only: Exposed skips a filtered index on H2.
        uniqueIndex(
            "sales_invoice_one_live_per_bill",
            billId,
            filterCondition = { billId.isNotNull() and (status neq SalesInvoiceStatus.CANCELLED) },
        )
    }
}
