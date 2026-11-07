package org.example.project.features.contract.database.tables

import org.example.project.features.document.database.tables.DocumentTable
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A recurring obligation — rent, Strom, Telefon, a leasing agreement.
 *
 * [expectsInvoice] is the flag separating the two kinds this model must both support. False is a
 * standing order that never produces a document: contract → instalment → bank transaction. True
 * means an invoice arrives each period and the direct debit settles *that invoice*; the contract's
 * job is to expect it, and slice 4d complains when it does not come.
 *
 * [tolerancePct] and [toleranceAbs] exist because variable obligations (Strom, Telefon) legitimately
 * differ month to month. Both are nullable: a fixed rent wants neither, and a band of zero is not
 * the same statement as "no band expected".
 */
object ContractTable : LongIdTable("contract", "id") {
    val shopId = reference("shop_id", ShopInfoTable)
    val supplierId = optReference("supplier_id", SupplierTable, onDelete = ReferenceOption.SET_NULL)

    val name = varchar("name", 255)
    val description = text("description").nullable()

    val expectedAmount = decimal("expected_amount", 19, 4).nullable()
    val tolerancePct = decimal("tolerance_pct", 9, 4).nullable()
    val toleranceAbs = decimal("tolerance_abs", 19, 4).nullable()

    /** [org.example.project.features.contract.domain.models.ContractInterval] name. */
    val interval = varchar("interval", 20)

    /**
     * Day of the month the instalment falls due, 1–31.
     *
     * A value past the end of a short month **clamps to the last day** rather than skipping it —
     * rent is still owed in February. See `InstalmentSchedule`.
     */
    val dueDay = integer("due_day")

    /** ISO-8601 weekday, 1 = Monday .. 7 = Sunday. Set only for a WEEKLY interval. */
    val dueWeekday = integer("due_weekday").nullable()

    val startDate = datetime("start_date")
    val endDate = datetime("end_date").nullable()
    val noticePeriodDays = integer("notice_period_days").nullable()

    /** True when a document is expected each period. See the class KDoc. */
    val expectsInvoice = bool("expects_invoice").default(false)

    /**
     * SEPA mandate reference (`MndtId`, up to 35 characters) — the identifier the bank quotes on
     * every direct debit collected under this agreement.
     *
     * It sits on the contract, not the supplier, because a mandate is granted per agreement: two
     * contracts with the same supplier carry different mandates, and that is exactly what lets
     * matching signal 3 reach *this instalment* rather than stopping at the supplier.
     *
     * Null until an operator records it, and signal 3 stays silent while it is. A later slice could
     * learn it from an allocated direct debit — a possibility, not a promise.
     */
    val sepaMandateId = varchar("sepa_mandate_id", 35).nullable()

    val defaultAccountNo = varchar("default_account_no", 20).nullable()
    val defaultBuCode = varchar("default_bu_code", 10).nullable()
    val defaultCostCenter = varchar("default_cost_center", 20).nullable()

    /** The contract PDF itself, if one was filed. */
    val documentId = optReference("document_id", DocumentTable, onDelete = ReferenceOption.SET_NULL)

    val isActive = bool("is_active").default(true)

    /**
     * MUST stay `clientDefault { }`, not `default(value)` — the latter freezes every row's timestamp
     * to server start, and an entity `init` block cannot write it because Exposed assigns
     * `Entity.klass` only after the constructor has run.
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").nullable()

    init {
        index("idx_contract_shop_id", false, shopId)
        index("idx_contract_supplier_id", false, supplierId)
        index("idx_contract_is_active", false, isActive)
    }
}
