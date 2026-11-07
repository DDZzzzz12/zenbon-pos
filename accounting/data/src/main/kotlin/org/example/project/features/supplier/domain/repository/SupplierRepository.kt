package org.example.project.features.supplier.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.supplier.domain.models.Supplier
import java.math.BigDecimal

/**
 * Data access for creditor master data.
 *
 * Separate from `DocumentRepository` on purpose — that interface already spans documents, links,
 * legacy metadata, invoices and bank statements.
 *
 * **Every method requires an ambient transaction.**
 */
interface SupplierRepository {
    /** Creates a supplier, deriving and storing `normalized_name` from [name]. */
    fun create(
        name: String,
        vatId: String? = null,
        taxNumber: String? = null,
        street: String? = null,
        zip: String? = null,
        city: String? = null,
        country: String? = null,
        email: String? = null,
        phone: String? = null,
        isProvisional: Boolean = false,
    ): Supplier

    fun findById(id: Long): Supplier?

    /** Exact match on BT-31. Null when [vatId] is null or blank. */
    fun findByVatId(vatId: String?): Supplier?

    /**
     * The party matching this invoice's seller, by VAT id, then tax number, then normalized name.
     *
     * Separate from the supplier lookups because a party may exist with **no creditor role at all** —
     * a company we currently only sell to. That is the case the two-stage resolution exists for.
     *
     * `business_partner.vat_id` is deliberately not unique (two parties may carry the same id until
     * a human merges them), so where several match, this picks one rather than failing — a unique
     * index would make the merge workflow unreachable.
     */
    fun findPartner(
        vatId: String?,
        taxNumber: String?,
        name: String?,
    ): BusinessPartner?

    /** The party owning this IBAN, or null. An IBAN belongs to the company, not to one of its roles. */
    fun findPartnerByIban(iban: String?): BusinessPartner?

    /**
     * The creditor role on this party, or null when it has none yet.
     *
     * Exact rather than a pick-one: `idx_supplier_partner_unique` says one creditor per company.
     */
    fun creditorRoleFor(partner: BusinessPartner): Supplier?

    /** Adds a creditor role to an existing party, leaving the party's identity untouched. */
    fun createCreditorRole(
        partner: BusinessPartner,
        isProvisional: Boolean,
    ): Supplier

    /**
     * Active suppliers. [provisionalOnly] narrows to the confirmation list; [search] matches name,
     * VAT ID or creditor number case-insensitively and is **ignored when blank**, so an empty search
     * box widens the list back rather than emptying it.
     *
     * Both narrowing arguments apply here rather than on the caller's side because the result is
     * paged: a filter applied after [limit] would hide every match past the first page.
     */
    fun list(
        provisionalOnly: Boolean = false,
        search: String? = null,
        sort: SupplierSort = SupplierSort.NAME,
        ascending: Boolean = true,
        /**
         * The lower bound for [SupplierSort.YTD_SPEND]'s sum. Required only for that ordering — see
         * [SupplierSort.needsYtdBoundary]; every other sort ignores it.
         */
        ytdSince: LocalDateTime? = null,
        limit: Int = 200,
        offset: Long = 0,
    ): List<Supplier>

    /** Counts what [list] would return for the same two filters, ignoring paging. */
    fun count(
        provisionalOnly: Boolean = false,
        search: String? = null,
    ): Long

    /**
     * Adds an IBAN to the supplier's known set, or returns quietly if it is already there.
     * [invoiceId] records where it was first seen and is not overwritten on a repeat.
     */
    fun registerBankAccount(
        supplier: Supplier,
        iban: String,
        bic: String?,
        invoiceId: Long?,
    )

    /** True when [iban] is already in this supplier's known set. Drives the fraud warning. */
    fun knowsIban(
        supplier: Supplier,
        iban: String?,
    ): Boolean

    /**
     * Open total per supplier — the sum of `invoice.open_amount` over each supplier's invoices whose
     * residual is still positive. One grouped query, keyed by supplier id; a supplier with nothing
     * open is simply absent from the map (the caller reads it as zero). [supplierIds] scopes the
     * query so the list endpoint pays one query, not one per row.
     */
    fun openTotalsFor(supplierIds: List<Long>): Map<Long, BigDecimal>

    /**
     * Year-to-date spend per supplier — the sum of `payment_allocation.amount` allocated to each
     * supplier's invoices (target kind `INVOICE`) on or after [since]. One grouped query, keyed by
     * supplier id; a supplier with no spend in the window is absent from the map.
     */
    fun ytdSpendFor(
        supplierIds: List<Long>,
        since: LocalDateTime,
    ): Map<Long, BigDecimal>
}
