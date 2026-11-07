package org.example.project.features.salesinvoice.data.repository

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.data.repository.OverdueCutoff
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.salesinvoice.domain.repository.SalesInvoiceRepository
import org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceContent
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.math.BigDecimal
import java.util.UUID

/** Exposed implementation of [SalesInvoiceRepository]. Requires an ambient transaction. */
class SalesInvoiceRepositoryImpl : SalesInvoiceRepository {
    override fun createDraft(
        shop: ShopInfo,
        revenueOrigin: RevenueOrigin,
        buyerName: String,
        issueDate: LocalDateTime,
        serviceDate: LocalDateTime,
        billId: Long?,
    ): SalesInvoice {
        require(buyerName.isNotBlank()) { "Buyer name must not be blank" }
        return SalesInvoice.new {
            this.shop = shop
            this.revenueOrigin = revenueOrigin
            this.buyerName = buyerName.trim()
            this.issueDate = issueDate
            this.serviceDate = serviceDate
            this.billId = billId?.let { EntityID(it, BillTable) }
        }
    }

    override fun findById(id: Long): SalesInvoice? = SalesInvoice.findById(id)

    override fun updateDraft(
        invoice: SalesInvoice,
        buyerName: String?,
        buyerVatId: String?,
        buyerStreet: String?,
        buyerZip: String?,
        buyerCity: String?,
        notes: String?,
    ) {
        requireDraft(invoice, "edited")
        buyerName?.takeIf { it.isNotBlank() }?.let { invoice.buyerName = it.trim() }
        buyerVatId?.let { invoice.buyerVatId = it.trim().ifBlank { null } }
        buyerStreet?.let { invoice.buyerStreet = it.trim().ifBlank { null } }
        buyerZip?.let { invoice.buyerZip = it.trim().ifBlank { null } }
        buyerCity?.let { invoice.buyerCity = it.trim().ifBlank { null } }
        notes?.let { invoice.notes = it.trim().ifBlank { null } }
        invoice.updatedAt = currentTimeStamp()
    }

    override fun deleteDraft(invoice: SalesInvoice) {
        requireDraft(invoice, "deleted")
        invoice.delete()
    }

    override fun replaceDraftContent(
        invoice: SalesInvoice,
        content: StandaloneInvoiceContent,
    ) {
        requireDraft(invoice, "edited")

        invoice.buyerName = content.buyerName.trim()
        invoice.buyerVatId = content.buyerVatId?.trim()?.ifBlank { null }
        invoice.buyerStreet = content.buyerStreet?.trim()?.ifBlank { null }
        invoice.buyerZip = content.buyerZip?.trim()?.ifBlank { null }
        invoice.buyerCity = content.buyerCity?.trim()?.ifBlank { null }
        invoice.buyerCountry = content.buyerCountry?.trim()?.ifBlank { null }
        invoice.buyerEmail = content.buyerEmail?.trim()?.ifBlank { null }
        invoice.serviceDate = content.serviceDate
        invoice.dueDate = content.dueDate
        invoice.paymentTermsText = content.paymentTermsText?.trim()?.ifBlank { null }
        invoice.notes = content.notes?.trim()?.ifBlank { null }
        invoice.lineNetTotal = content.lineNetTotal
        // A typed invoice carries no document-level allowance or charge (only a from-bill one does).
        invoice.allowanceTotal = null
        invoice.chargeTotal = null
        invoice.taxExclusiveTotal = content.lineNetTotal
        invoice.taxTotal = content.taxTotal
        invoice.taxInclusiveTotal = content.taxInclusiveTotal

        // The children are deleted and rewritten in the same transaction — a replace, not a merge.
        invoice.lines.toList().forEach { it.delete() }
        invoice.vatBreakdown.toList().forEach { it.delete() }
        invoice.allowanceCharges.toList().forEach { it.delete() }
        content.lines.forEachIndexed { index, line ->
            SalesInvoiceLine.new {
                this.salesInvoice = invoice
                this.lineNo = index + 1
                this.itemName = line.itemName
                this.itemDescription = line.itemDescription
                this.quantity = line.quantity
                this.unitCode = line.unitCode
                this.netUnitPrice = line.netUnitPrice
                this.lineNetAmount = line.lineNetAmount
                this.vatRate = line.vatRate
                this.vatCategoryCode = line.vatCategoryCode
                this.vatAmount = line.vatAmount
                this.lineGrossAmount = line.lineGrossAmount
                this.grossUnitPrice = line.grossUnitPrice
            }
        }
        content.vatRows.forEach { row ->
            SalesInvoiceVatBreakdown.new {
                this.salesInvoice = invoice
                this.vatCategoryCode = row.vatCategoryCode
                this.vatRate = row.vatRatePercent
                this.taxableAmount = row.taxableAmount
                this.vatAmount = row.vatAmount
            }
        }
        invoice.updatedAt = currentTimeStamp()
    }

    override fun markIssued(
        invoice: SalesInvoice,
        invoiceNumber: String,
        issuedBy: UUID?,
    ) {
        requireDraft(invoice, "issued")
        require(invoiceNumber.isNotBlank()) { "Invoice number must not be blank" }
        invoice.invoiceNumber = invoiceNumber
        invoice.status = SalesInvoiceStatus.ISSUED
        invoice.issuedAt = currentTimeStamp()
        invoice.issuedBy = issuedBy?.let { EntityID(it, EmployeeTable) }
        invoice.updatedAt = currentTimeStamp()
    }

    override fun markCancelled(
        invoice: SalesInvoice,
        cancelledBy: UUID?,
        reason: String?,
    ) {
        if (invoice.status != SalesInvoiceStatus.ISSUED) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_NOT_CANCELLABLE,
                HttpStatusCode.Conflict,
                "Only an ISSUED invoice can be cancelled; invoice ${invoice.id.value} is ${invoice.status}",
            )
        }
        invoice.status = SalesInvoiceStatus.CANCELLED
        invoice.cancelledAt = currentTimeStamp()
        invoice.cancelledBy = cancelledBy?.let { EntityID(it, EmployeeTable) }
        invoice.cancellationReason = reason?.trim()?.ifBlank { null }
        invoice.updatedAt = currentTimeStamp()
    }

    override fun updateOpenAmount(
        invoice: SalesInvoice,
        openAmount: BigDecimal,
    ) {
        invoice.openAmount = openAmount
        invoice.updatedAt = currentTimeStamp()
    }

    override fun list(
        status: SalesInvoiceStatus?,
        invoiceTypeCode: String?,
        open: Boolean,
        overdue: Boolean,
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): Pair<List<SalesInvoice>, Long> {
        val now = currentTimeStamp()
        // `overdue` implies `open`: an overdue filter over drafts or cancellations is meaningless —
        // only an issued row with a positive residual can be late — so a caller asking for overdue
        // rows gets the open bucket narrowed, never the whole table with the flag dropped.
        val wantsOpen = open || overdue
        val condition: Op<Boolean> =
            listOfNotNull(
                status?.let { SalesInvoiceTable.status eq it },
                invoiceTypeCode?.let { SalesInvoiceTable.invoiceTypeCode eq it },
                wantsOpen.takeIf { it }?.let { openCondition(now, overdue) },
                debtorId?.let { SalesInvoiceTable.debtorId eq it },
            ).reduceOrNull { a, b -> a and b } ?: Op.TRUE

        val query = SalesInvoice.find { condition }
        val total = query.count()
        val page =
            query
                .orderBy(
                    SalesInvoiceTable.issueDate to SortOrder.DESC,
                    SalesInvoiceTable.id to SortOrder.DESC,
                ).limit(limit)
                .offset(offset)
                .toList()
        return page to total
    }

    /**
     * [SalesInvoiceResidual.stillOpen] on an [issued][SalesInvoiceResidual.issued] invoice —
     * `open_amount > 0`, else `amount_due > 0`, else `tax_inclusive_total > 0`, with drafts and
     * cancellations never qualifying. [overdue] additionally requires the due day to be over ([OverdueCutoff]: `due_date < start of today`).
     *
     * The cascade itself is not written here: it is the shared definition the allocation queue's
     * `openSalesInvoiceCondition` also calls, so this list and that queue cannot disagree about the
     * same invoice.
     */
    private fun openCondition(
        now: LocalDateTime,
        overdue: Boolean,
    ): Op<Boolean> {
        val overdueClause: Op<Boolean> =
            if (overdue) SalesInvoiceTable.dueDate.isNotNull() and (SalesInvoiceTable.dueDate less OverdueCutoff.of(now)) else Op.TRUE
        return SalesInvoiceResidual.issued() and SalesInvoiceResidual.stillOpen() and overdueClause
    }

    override fun hasLiveInvoiceForBill(
        billId: Long,
        excludingInvoiceId: Long?,
    ): Boolean =
        SalesInvoice
            .find {
                (SalesInvoiceTable.billId eq EntityID(billId, BillTable)) and
                    (SalesInvoiceTable.status neq SalesInvoiceStatus.CANCELLED) and
                    (excludingInvoiceId?.let { SalesInvoiceTable.id neq it } ?: Op.TRUE)
            }.empty()
            .not()

    private fun requireDraft(
        invoice: SalesInvoice,
        attemptedAction: String,
    ) {
        if (invoice.status != SalesInvoiceStatus.DRAFT) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_IMMUTABLE,
                HttpStatusCode.Conflict,
                "Invoice ${invoice.id.value} is ${invoice.status} and cannot be $attemptedAction. " +
                    "An issued invoice changes only through a 381 Gutschrift.",
            )
        }
    }
}
