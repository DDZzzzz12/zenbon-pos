package org.example.project.features.debtor.data.repository

import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.core.utils.TaxNumberFormat
import org.example.project.core.utils.VatIdFormat
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.debtor.domain.repository.DebtorRepository
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.service.normalizeSupplierName
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Exposed implementation of [DebtorRepository]. Requires an ambient transaction. */
class DebtorRepositoryImpl : DebtorRepository {
    override fun create(
        shop: ShopInfo,
        name: String,
        legalName: String?,
        vatId: String?,
        taxNumber: String?,
        street: String?,
        zip: String?,
        city: String?,
        country: String?,
        email: String?,
        phone: String?,
        debtorNo: String?,
        paymentTermsDays: Int?,
        defaultRevenueAccountNo: String?,
        defaultBuCode: String?,
        einvoiceFormat: EInvoiceFormatPreference,
    ): Debtor {
        require(name.isNotBlank()) { "Debtor name must not be blank" }
        // Built after the guard, so a blank name still fails fast rather than leaving an orphan
        // party behind. Identity lives on the party alone; the role row holds only debtor-specific
        // fields.
        val party =
            BusinessPartner.new {
                this.shop = shop
                this.name = name.trim()
                // Deliberately the creditor-side normalizer, reached across features: the party's
                // comparison key has exactly one producer because it has exactly one consumer —
                // the invoice-seller resolver, which searches `normalizedName eq
                // normalizeSupplierName(sellerName)`. A second normalization here would store
                // "metro ag" where that lookup asks for "metro", so a party created through the
                // debtor path would be invisible to it and an invoice from a company we already
                // sell to would silently create a second one.
                this.normalizedName = normalizeSupplierName(name)
                this.legalName = legalName.orNull()
                // Canonicalized, not stored raw: the party lookup matches on an exact `eq`
                // against this column, so " de123 456 " would otherwise never find its own party.
                // The rule lives in `VatIdFormat` rather than here because the creditor paths write
                // this same column — a private copy on one side is how the two drifted apart, and
                // duplicated a company that had both a creditor and a debtor role.
                this.vatId = vatId?.let { VatIdFormat.canonical(it) }.orNull()
                // Canonicalized, not merely trimmed: `orNull` trims, the creditor paths did not,
                // and that difference alone was enough to hide a debtor's party from the invoice
                // resolver's tax-number rung. The rule lives in `TaxNumberFormat` so that cannot
                // recur.
                this.taxNumber = taxNumber?.let { TaxNumberFormat.canonical(it) }.orNull()
                this.street = street.orNull()
                this.zip = zip.orNull()
                this.city = city.orNull()
                this.country = country.orNull()
                this.email = email.orNull()
                this.phone = phone.orNull()
            }
        return Debtor.new {
            this.partner = party
            this.shop = shop
            this.debtorNo = debtorNo.orNull()
            this.paymentTermsDays = paymentTermsDays
            this.defaultRevenueAccountNo = defaultRevenueAccountNo.orNull()
            this.defaultBuCode = defaultBuCode.orNull()
            this.einvoiceFormat = einvoiceFormat
        }
    }

    override fun findById(id: Long): Debtor? = Debtor.findById(id)

    /**
     * The debtor whose **company** carries this VAT ID.
     *
     * Same contract as before — one debtor or none — but read from the party, where identity lives.
     * `create`/`update` canonicalize what they store there, so the exact `eq` finds what it used
     * to.
     */
    override fun findByVatId(vatId: String?): Debtor? {
        val key = vatId?.let { VatIdFormat.canonical(it) }?.takeIf { it.isNotBlank() } ?: return null
        return Debtor
            .wrapRows(
                DebtorTable
                    .innerJoin(BusinessPartnerTable)
                    .selectAll()
                    .where { BusinessPartnerTable.vatId eq key }
                    .limit(1),
            ).firstOrNull()
    }

    override fun findByDebtorNo(debtorNo: String?): Debtor? {
        val key = debtorNo?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return Debtor.find { DebtorTable.debtorNo eq key }.firstOrNull()
    }

    override fun listActive(): List<Debtor> = Debtor.wrapRows(joinedQuery(DebtorTable.isActive eq true)).toList()

    override fun update(
        debtor: Debtor,
        name: String,
        legalName: String?,
        vatId: String?,
        taxNumber: String?,
        street: String?,
        zip: String?,
        city: String?,
        country: String?,
        email: String?,
        phone: String?,
        debtorNo: String?,
        paymentTermsDays: Int?,
        defaultRevenueAccountNo: String?,
        defaultBuCode: String?,
        notes: String?,
        isActive: Boolean,
        einvoiceFormat: EInvoiceFormatPreference,
    ): Debtor {
        require(name.isNotBlank()) { "Debtor name must not be blank" }
        // The party carries the identity, so an edit has to reach it — and only it.
        val party = debtor.partner
        party.name = name.trim()
        // Same normalizer as `create`, for the same reason: a rename that wrote a differently
        // computed key would drop an existing party out of the resolver's lookup.
        party.normalizedName = normalizeSupplierName(name)
        party.legalName = legalName.orNull()
        party.vatId = vatId?.let { VatIdFormat.canonical(it) }.orNull()
        party.taxNumber = taxNumber?.let { TaxNumberFormat.canonical(it) }.orNull()
        party.street = street.orNull()
        party.zip = zip.orNull()
        party.city = city.orNull()
        party.country = country.orNull()
        party.email = email.orNull()
        party.phone = phone.orNull()
        party.updatedAt = currentTimeStamp()

        debtor.debtorNo = debtorNo.orNull()
        debtor.paymentTermsDays = paymentTermsDays
        debtor.defaultRevenueAccountNo = defaultRevenueAccountNo.orNull()
        debtor.defaultBuCode = defaultBuCode.orNull()
        debtor.notes = notes.orNull()
        debtor.isActive = isActive
        debtor.einvoiceFormat = einvoiceFormat
        debtor.updatedAt = currentTimeStamp()
        return debtor
    }

    override fun list(
        activeOnly: Boolean,
        search: String?,
        limit: Int,
        offset: Long,
    ): List<Debtor> =
        Debtor
            .wrapRows(
                joinedQuery(listFilter(activeOnly, search))
                    .limit(limit)
                    .offset(offset),
            ).toList()

    override fun count(
        activeOnly: Boolean,
        search: String?,
    ): Long =
        // Shares [listFilter], which names party columns, so it needs the same join to resolve them.
        DebtorTable
            .innerJoin(BusinessPartnerTable)
            .selectAll()
            .where { listFilter(activeOnly, search) }
            .count()

    /**
     * The debtors matching [predicate], ordered by the party's name.
     *
     * The search term and the name ordering read the party's identity, so the query has to reach
     * the party table — and `Debtor.find { }` cannot express a join. Hence the DSL query, with the
     * rows wrapped back into entities by the callers, exactly as the creditor side does.
     */
    private fun joinedQuery(predicate: Op<Boolean>) =
        DebtorTable
            .innerJoin(BusinessPartnerTable)
            .selectAll()
            .where { predicate }
            .orderBy(BusinessPartnerTable.name to SortOrder.ASC)

    override fun salesInvoiceCount(debtorId: Long): Long = SalesInvoice.find { SalesInvoiceTable.debtorId eq EntityID(debtorId, DebtorTable) }.count()

    override fun delete(debtor: Debtor) = debtor.delete()

    override fun debtorNoIsTaken(debtorNo: String): Boolean = findByDebtorNo(debtorNo) != null

    /** `Op.TRUE` rather than a null filter keeps [list] and [count] on one code path. */
    private fun activeClause(activeOnly: Boolean): Op<Boolean> = if (activeOnly) (DebtorTable.isActive eq true) else Op.TRUE

    /**
     * The list predicate: the active filter, narrowed by the search term when there is one.
     *
     * A blank term is **not** a filter — the trim-to-null is what stops an empty search box from
     * emptying the list. City is searchable because an operator often remembers where a customer is
     * before what they are called.
     */
    private fun listFilter(
        activeOnly: Boolean,
        search: String?,
    ): Op<Boolean> {
        val base = activeClause(activeOnly)
        val term = search?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        val pattern = "%${term.lowercase()}%"
        return base and
            (
                (BusinessPartnerTable.name.lowerCase() like pattern) or
                    (BusinessPartnerTable.city.lowerCase() like pattern) or
                    (BusinessPartnerTable.vatId.lowerCase() like pattern) or
                    (DebtorTable.debtorNo.lowerCase() like pattern)
            )
    }

    private fun String?.orNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }
}
