package org.example.project.features.debtor.domain.repository

import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.setup.domain.models.ShopInfo

/**
 * Customers we invoice.
 *
 * **Every method requires an ambient transaction.** VAT-id arguments may carry any spacing or case —
 * implementations canonicalise before comparing, because a VAT id is printed grouped as often as not.
 */
interface DebtorRepository {
    fun create(
        shop: ShopInfo,
        name: String,
        legalName: String? = null,
        vatId: String? = null,
        taxNumber: String? = null,
        street: String? = null,
        zip: String? = null,
        city: String? = null,
        country: String? = null,
        email: String? = null,
        phone: String? = null,
        debtorNo: String? = null,
        paymentTermsDays: Int? = null,
        defaultRevenueAccountNo: String? = null,
        defaultBuCode: String? = null,
        einvoiceFormat: EInvoiceFormatPreference = EInvoiceFormatPreference.NONE,
    ): Debtor

    fun findById(id: Long): Debtor?

    /** Null when [vatId] is null, blank, or matches nothing. Spacing and case are ignored. */
    fun findByVatId(vatId: String?): Debtor?

    /** Null when [debtorNo] is null, blank, or matches nothing. */
    fun findByDebtorNo(debtorNo: String?): Debtor?

    fun listActive(): List<Debtor>

    /**
     * Replaces every editable field. A full replace, not a patch: an omitted optional is written as
     * null, mirroring `PUT /api/suppliers/{id}`.
     */
    fun update(
        debtor: Debtor,
        name: String,
        legalName: String? = null,
        vatId: String? = null,
        taxNumber: String? = null,
        street: String? = null,
        zip: String? = null,
        city: String? = null,
        country: String? = null,
        email: String? = null,
        phone: String? = null,
        debtorNo: String? = null,
        paymentTermsDays: Int? = null,
        defaultRevenueAccountNo: String? = null,
        defaultBuCode: String? = null,
        notes: String? = null,
        isActive: Boolean = true,
        einvoiceFormat: EInvoiceFormatPreference = EInvoiceFormatPreference.NONE,
    ): Debtor

    /**
     * Name-ordered debtors. [search] matches name, city, VAT ID or debtor number case-insensitively
     * and is **ignored when blank**, so an emptied search box widens the list rather than clearing
     * it.
     *
     * Both narrowing arguments apply here rather than at the caller because the result is paged: a
     * filter applied after [limit] would hide every match past the first page.
     */
    fun list(
        activeOnly: Boolean = false,
        search: String? = null,
        limit: Int = 200,
        offset: Long = 0,
    ): List<Debtor>

    /** Counts what [list] would return for the same two filters, ignoring paging. */
    fun count(
        activeOnly: Boolean = false,
        search: String? = null,
    ): Long

    /**
     * How many `sales_invoice` rows point at this debtor, **cancelled ones included** — those are
     * retention data under §147 AO exactly like the live ones.
     *
     * The service refuses a hard delete when this is non-zero; such a debtor is deactivated instead.
     */
    fun salesInvoiceCount(debtorId: Long): Long

    /** Removes the row. Callers must check [salesInvoiceCount] first — this does not. */
    fun delete(debtor: Debtor)

    /**
     * Whether [debtorNo] is already assigned.
     *
     * A released number is retired, never reused: reassigning 10247 to a second customer merges two
     * debtors' history in the tax advisor's books.
     */
    fun debtorNoIsTaken(debtorNo: String): Boolean
}
