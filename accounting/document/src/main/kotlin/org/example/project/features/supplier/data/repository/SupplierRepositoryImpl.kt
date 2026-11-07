package org.example.project.features.supplier.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.utils.TaxNumberFormat
import org.example.project.core.utils.VatIdFormat
import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.document.data.repository.InvoiceResidual
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.service.normalizeSupplierName
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.models.SupplierBankAccount
import org.example.project.features.supplier.domain.repository.SupplierRepository
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.dao.with
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.math.BigDecimal

/** Exposed implementation of [SupplierRepository]. Requires an ambient transaction. */
class SupplierRepositoryImpl : SupplierRepository {
    override fun create(
        name: String,
        vatId: String?,
        taxNumber: String?,
        street: String?,
        zip: String?,
        city: String?,
        country: String?,
        email: String?,
        phone: String?,
        isProvisional: Boolean,
    ): Supplier {
        // A creditor without a party has no identity to carry, so the party is created first and in
        // the same transaction. The role row holds only creditor-specific fields.
        val party =
            BusinessPartner.new {
                // Single-shop assumption, stated rather than hidden: this method takes no shop, so
                // the party takes the only one that exists. Multi-shop work must pass a shop in
                // explicitly — the way `DebtorRepository.create` already does — because an
                // arbitrary `first()` would file a creditor under the wrong shop, and no row-level
                // policy can catch a write that names a shop it is entitled to write to.
                this.shop = ShopInfo.all().first()
                this.name = name
                this.normalizedName = normalizeSupplierName(name)
                // Canonicalized, not stored raw, exactly as the debtor path does: the party
                // lookup matches this column with an exact `eq`, so "DE 123 456 789" typed
                // off a letterhead would otherwise never meet the "DE123456789" a
                // structured invoice asserts, and the company would be created twice.
                this.vatId = vatId?.let { VatIdFormat.canonical(it) }?.takeIf { it.isNotBlank() }
                // Canonicalized for the same reason the VAT id above is: the tax number is the
                // resolver's second rung and is compared with an exact `eq`, so "12/345/67890"
                // typed by an operator has to meet the "1234567890" another path stored.
                this.taxNumber = taxNumber?.let { TaxNumberFormat.canonical(it) }?.takeIf { it.isNotBlank() }
                this.street = street
                this.zip = zip
                this.city = city
                this.country = country
                this.email = email
                this.phone = phone
            }
        return Supplier.new {
            this.partner = party
            this.isProvisional = isProvisional
        }
    }

    override fun findById(id: Long): Supplier? = Supplier.findById(id)

    /**
     * The creditor whose **company** carries this VAT ID.
     *
     * The contract is unchanged — still "which creditor is this?" — but the value is read from the
     * party, because that is where identity lives. One caveat the caller should know:
     * `business_partner.vat_id` is deliberately not unique, since duplicate parties exist
     * transiently until a human merges them. So the `firstOrNull` is a genuine pick-one rather than
     * a formality, and it stays one on purpose: a unique index on the party would make the merge
     * workflow unreachable.
     */
    override fun findByVatId(vatId: String?): Supplier? {
        // Canonicalized on the way in too: the stored key is canonical, so a raw search string
        // would miss it. A half-canonicalized pair is no better than none.
        val key = vatId?.let { VatIdFormat.canonical(it) }?.takeIf { it.isNotBlank() } ?: return null
        return findOneByParty(BusinessPartnerTable.vatId eq key)
    }

    /**
     * The party itself, down the same ladder — VAT id, then tax number, then normalized name.
     *
     * The rungs are tried in order and the first hit wins, so a caller that wants only one of them
     * passes nulls for the rest; that is how the resolver keeps the IBAN rung wedged between the tax
     * number and the name.
     *
     * None of the three columns carries a unique index, and that is deliberate: a creditor and a
     * debtor that share a VAT id are two parties until a **human** merges them, so several parties
     * can legitimately answer one rung. Which one comes back therefore matters, and this picks the
     * party that **already carries a creditor role** — see [preferringCreditorRole]. The caller is
     * the invoice resolver, which attaches a creditor role to whatever party it is handed: handing
     * it the role-less twin would mint a *second* creditor for a company that already has one, down
     * a path that never consults `requireVatIdFree`. That is precisely what the dropped
     * `supplier.vat_id` unique index used to reject.
     */
    override fun findPartner(
        vatId: String?,
        taxNumber: String?,
        name: String?,
    ): BusinessPartner? {
        // The search key gets the same treatment the writers give the stored one — this is the
        // rung the resolver trusts most, and it is fed straight from `invoice.sellerVatId`, i.e.
        // from whatever the supplier's document happened to assert.
        vatId?.let { VatIdFormat.canonical(it) }?.takeIf { it.isNotBlank() }?.let { key ->
            preferringCreditorRole(BusinessPartnerTable.vatId eq key)?.let { return it }
        }
        taxNumber?.let { TaxNumberFormat.canonical(it) }?.takeIf { it.isNotBlank() }?.let { key ->
            preferringCreditorRole(BusinessPartnerTable.taxNumber eq key)?.let { return it }
        }
        val normalized = name?.takeIf { it.isNotBlank() }?.let { normalizeSupplierName(it) }
        return normalized?.takeIf { it.isNotBlank() }?.let { key ->
            preferringCreditorRole(BusinessPartnerTable.normalizedName eq key)
        }
    }

    /**
     * One party matching [predicate], **preferring one that already trades as a creditor**.
     *
     * Applied to all three rungs, including the normalized name, which has no uniqueness at all and
     * is the likeliest of them to match several parties.
     *
     * The tiebreak within each leg is the lowest id, stated rather than left to the engine: an
     * undirected `firstOrNull()` over a set is what made the choice depend on how H2 or Postgres
     * happened to scan the table, so the same data could resolve one way in a test and the other in
     * production.
     *
     * Merged parties are excluded here, in the one place all three rungs pass through, so the rungs
     * cannot drift apart as they are extended.
     */
    private fun preferringCreditorRole(predicate: Op<Boolean>): BusinessPartner? {
        // A merged party has been declared a duplicate by a human. Resolving to it would attach an
        // incoming invoice's creditor role to a company that is deliberately no longer visible.
        // `.isNull()` rather than `eq null`: the nullable `EntityID` column has no such overload.
        val live = predicate and BusinessPartnerTable.mergedIntoPartnerId.isNull()
        return lowestIdPartner(BusinessPartnerTable.innerJoin(SupplierTable), live)
            ?: lowestIdPartner(BusinessPartnerTable, live)
    }

    private fun lowestIdPartner(
        from: ColumnSet,
        predicate: Op<Boolean>,
    ): BusinessPartner? =
        BusinessPartner
            .wrapRows(
                from
                    .selectAll()
                    .where { predicate }
                    .orderBy(BusinessPartnerTable.id to SortOrder.ASC)
                    .limit(1),
            ).firstOrNull()

    /**
     * The party that banks under this IBAN — the resolver's third rung, and a fourth way in that
     * bypasses [preferringCreditorRole] entirely, so it carries the merge exclusion itself.
     *
     * In a consistent database this can never fire: a merge re-points the losing party's bank
     * accounts onto the survivor, so the survivor answers this rung and the merged row owns no
     * accounts to be found through. The guard is here for the inconsistent case — a row left behind
     * by a partial merge, or an account written against the loser afterwards — because silently
     * resolving to a merged party is exactly the outcome the other three rungs already refuse, and a
     * fourth door that stays open makes the guarantee worthless.
     */
    override fun findPartnerByIban(iban: String?): BusinessPartner? {
        val key = iban?.takeIf { it.isNotBlank() } ?: return null
        return SupplierBankAccount
            .find { SupplierBankAccountTable.iban eq key }
            .firstOrNull()
            ?.partner
            // Null rather than the survivor: the caller tries the remaining rungs, which resolve the
            // company by its own identity instead of trusting a stale account row.
            ?.takeIf { it.mergedInto == null }
    }

    override fun creditorRoleFor(partner: BusinessPartner): Supplier? = Supplier.find { SupplierTable.partnerId eq partner.id.value }.firstOrNull()

    override fun createCreditorRole(
        partner: BusinessPartner,
        isProvisional: Boolean,
    ): Supplier =
        Supplier.new {
            this.partner = partner
            this.isProvisional = isProvisional
        }

    /**
     * One creditor matching [predicate], which names the party's columns.
     *
     * `Supplier.find { }` cannot express the join — the same reason [list] drops to the DSL — so the
     * row is read through the join and wrapped back into an entity.
     */
    private fun findOneByParty(predicate: Op<Boolean>): Supplier? =
        Supplier
            .wrapRows(
                SupplierTable
                    .innerJoin(BusinessPartnerTable)
                    .selectAll()
                    .where { predicate }
                    .limit(1),
            ).firstOrNull()

    override fun list(
        provisionalOnly: Boolean,
        search: String?,
        sort: SupplierSort,
        ascending: Boolean,
        ytdSince: LocalDateTime?,
        limit: Int,
        offset: Long,
    ): List<Supplier> {
        // The aggregates read rows the service may have written through the DAO in this same
        // transaction; without a flush the subquery would order by the pre-write state.
        if (sort == SupplierSort.OPEN_TOTAL || sort == SupplierSort.YTD_SPEND) {
            TransactionManager.current().entityCache.flush(listOf(InvoiceTable, PaymentAllocationTable))
        }
        // The search term and the name ordering read the party's identity, so the query has to
        // reach the party table — and `Supplier.find { }` cannot express a join. Hence the DSL
        // query, with the rows wrapped back into entities afterwards.
        val rows =
            SupplierTable
                .innerJoin(BusinessPartnerTable)
                .selectAll()
                .where { listFilter(provisionalOnly, search) }
                .orderBy(*orderBy(sort, ascending, ytdSince))
                .limit(limit)
                .offset(offset)
        return Supplier
            .wrapRows(rows)
            // The list DTO renders `knownIbans`, so without this the page costs one extra query per
            // row — up to the route's 500-row limit. The IBANs hang off the party, so the preload
            // has to travel the same path the mapper does: the reference first, then the collection
            // on it. Getting this wrong changes no output, only the query count, so nothing else
            // would notice.
            .with(Supplier::partner, BusinessPartner::bankAccounts)
            .toList()
    }

    /**
     * The ordering, with **name as a tiebreak** on every aggregate sort.
     *
     * Without it, the many suppliers that sum to the same value (nearly always zero) come back in
     * whatever order the engine felt like, which differs between H2 and Postgres — so paging could
     * repeat or skip a row in production while the tests stayed green.
     */
    private fun orderBy(
        sort: SupplierSort,
        ascending: Boolean,
        ytdSince: LocalDateTime?,
    ): Array<Pair<Expression<*>, SortOrder>> {
        val direction = if (ascending) SortOrder.ASC else SortOrder.DESC
        return when (sort) {
            SupplierSort.OPEN_TOTAL ->
                arrayOf(
                    openTotalExpression() to nullsPlaced(ascending),
                    BusinessPartnerTable.name to SortOrder.ASC,
                )
            SupplierSort.YTD_SPEND ->
                // Falling back to name rather than throwing: an ordering is a presentation choice,
                // and a caller that forgot the boundary should get a readable list, not a 500.
                ytdSince?.let {
                    arrayOf(
                        ytdSpendExpression(it) to nullsPlaced(ascending),
                        BusinessPartnerTable.name to SortOrder.ASC,
                    )
                } ?: arrayOf(BusinessPartnerTable.name to direction)
            else -> arrayOf(sortColumn(sort) to direction)
        }
    }

    /** What one supplier is still owed — NULL when we owe them nothing. See [nullsPlaced]. */
    private fun openTotalExpression(): Expression<BigDecimal?> =
        wrapAsExpression(
            InvoiceTable
                .leftJoin(InvoiceBookingTable)
                .select(InvoiceResidual.expression().sum())
                .where {
                    (InvoiceTable.supplierId eq SupplierTable.id) and
                        InvoiceResidual.stillOpen() and
                        InvoiceResidual.unpaid()
                },
        )

    /** `SUM(payment_allocation.amount)` since [since] for one supplier — NULL when there is none. */
    private fun ytdSpendExpression(since: LocalDateTime): Expression<BigDecimal?> =
        wrapAsExpression(
            PaymentAllocationTable
                .innerJoin(InvoiceTable, { PaymentAllocationTable.invoiceId }, { InvoiceTable.id })
                .select(PaymentAllocationTable.amount.sum())
                .where {
                    (InvoiceTable.supplierId eq SupplierTable.id) and
                        (PaymentAllocationTable.targetKind eq "INVOICE") and
                        (PaymentAllocationTable.createdAt greaterEq since)
                },
        )

    /**
     * Where a `SUM` over no rows lands.
     *
     * SQL gives NULL, not zero, and left to itself each engine places NULLs differently — H2 and
     * Postgres disagree — so a supplier we owe *nothing* could head a list ordered by what is
     * outstanding. Stating the placement makes "no invoices" sort as the smallest amount, which is
     * what it means, and makes the two engines agree.
     */
    private fun nullsPlaced(ascending: Boolean): SortOrder = if (ascending) SortOrder.ASC_NULLS_FIRST else SortOrder.DESC_NULLS_LAST

    override fun count(
        provisionalOnly: Boolean,
        search: String?,
    ): Long =
        // Shares [listFilter], which names party columns, so it needs the same join to resolve them.
        SupplierTable
            .innerJoin(BusinessPartnerTable)
            .selectAll()
            .where { listFilter(provisionalOnly, search) }
            .count()

    override fun registerBankAccount(
        supplier: Supplier,
        iban: String,
        bic: String?,
        invoiceId: Long?,
    ) {
        if (iban.isBlank() || knowsIban(supplier, iban)) return
        SupplierBankAccount.new {
            // The IBAN is the company's, not the creditor role's.
            this.partner = supplier.partner
            this.iban = iban
            this.bic = bic
            this.firstSeenOnInvoiceId = invoiceId
            this.verified = false
        }
    }

    /**
     * Whether this IBAN is already on record for the supplier's **company**.
     *
     * Scoped by party, which also sharpens the question: an IBAN is the company's, so "have we seen
     * this account before?" is answered once for the firm rather than once per role it plays.
     */
    override fun knowsIban(
        supplier: Supplier,
        iban: String?,
    ): Boolean {
        val key = iban?.takeIf { it.isNotBlank() } ?: return false
        return SupplierBankAccount
            .find { (SupplierBankAccountTable.partnerId eq supplier.partner.id) and (SupplierBankAccountTable.iban eq key) }
            .empty()
            .not()
    }

    override fun openTotalsFor(supplierIds: List<Long>): Map<Long, BigDecimal> {
        if (supplierIds.isEmpty()) return emptyMap()
        // The service writes invoices via the DAO before asking for these sums; without a flush the
        // aggregate would read the pre-write state (same reason AllocationRepositoryImpl flushes).
        TransactionManager.current().entityCache.flush(listOf(InvoiceTable))
        val total = InvoiceResidual.expression().sum()
        return InvoiceTable
            .leftJoin(InvoiceBookingTable)
            .select(InvoiceTable.supplierId, total)
            .where {
                (InvoiceTable.supplierId inList supplierIds) and
                    InvoiceResidual.stillOpen() and
                    InvoiceResidual.unpaid()
            }.groupBy(InvoiceTable.supplierId)
            .mapNotNull { row ->
                val supplierId = row[InvoiceTable.supplierId]?.value ?: return@mapNotNull null
                supplierId to (row[total] ?: BigDecimal.ZERO)
            }.toMap()
    }

    override fun ytdSpendFor(
        supplierIds: List<Long>,
        since: LocalDateTime,
    ): Map<Long, BigDecimal> {
        if (supplierIds.isEmpty()) return emptyMap()
        TransactionManager.current().entityCache.flush(listOf(PaymentAllocationTable, InvoiceTable))
        val total = PaymentAllocationTable.amount.sum()
        return PaymentAllocationTable
            .innerJoin(
                InvoiceTable,
                { PaymentAllocationTable.invoiceId },
                { InvoiceTable.id },
            ).select(InvoiceTable.supplierId, total)
            .where {
                (InvoiceTable.supplierId inList supplierIds) and
                    (PaymentAllocationTable.targetKind eq "INVOICE") and
                    (PaymentAllocationTable.createdAt greaterEq since)
            }.groupBy(InvoiceTable.supplierId)
            .mapNotNull { row ->
                val supplierId = row[InvoiceTable.supplierId]?.value ?: return@mapNotNull null
                supplierId to (row[total] ?: BigDecimal.ZERO)
            }.toMap()
    }

    /**
     * The list predicate: the active/provisional filter, narrowed by the search term when there is
     * one.
     *
     * A blank term is **not** a filter — the trim-to-null is what stops an empty search box from
     * emptying the list. The term is matched against the raw `name` rather than `normalized_name`
     * because normalisation strips legal forms, so searching "GmbH" against the normalised column
     * would match nothing while the operator is looking straight at it.
     */
    private fun listFilter(
        provisionalOnly: Boolean,
        search: String?,
    ): Op<Boolean> {
        val base = activeFilter(provisionalOnly)
        val term = search?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        val pattern = "%${term.lowercase()}%"
        return base and
            (
                (BusinessPartnerTable.name.lowerCase() like pattern) or
                    (BusinessPartnerTable.vatId.lowerCase() like pattern) or
                    (SupplierTable.creditorNo.lowerCase() like pattern)
            )
    }

    /**
     * The stored column for a non-aggregate sort.
     *
     * The two aggregate orderings never reach here — [orderBy] handles them — but they are listed
     * rather than folded into an `else`, so adding a seventh sort is a compile error here instead of
     * a silent fall-through to ordering by name.
     */
    private fun sortColumn(sort: SupplierSort): Column<out Any?> =
        when (sort) {
            SupplierSort.NAME -> BusinessPartnerTable.name
            SupplierSort.VAT_ID -> BusinessPartnerTable.vatId
            SupplierSort.CREDITOR_NO -> SupplierTable.creditorNo
            SupplierSort.STATUS -> SupplierTable.isProvisional
            SupplierSort.OPEN_TOTAL, SupplierSort.YTD_SPEND -> BusinessPartnerTable.name
        }

    private fun activeFilter(provisionalOnly: Boolean): Op<Boolean> =
        if (provisionalOnly) {
            (SupplierTable.isActive eq true) and (SupplierTable.isProvisional eq true)
        } else {
            SupplierTable.isActive eq true
        }
}
