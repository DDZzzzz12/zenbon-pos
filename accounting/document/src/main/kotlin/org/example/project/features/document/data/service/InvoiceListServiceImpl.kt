package org.example.project.features.document.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.domain.repository.AllocationQueueRepository
import org.example.project.features.document.data.repository.InvoiceStorno
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.InvoiceListService
import org.example.project.features.finance.domain.service.BookkeepingStartService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.formatter.toExactDecimalString

/**
 * See [InvoiceListService]. Every method maps inside its own [dbQuery], because the rows come from
 * lazy Exposed entities that throw once the transaction has closed.
 *
 * The payment buckets come from [AllocationQueueRepository] rather than a query of their own: the
 * open-invoice predicate is four coalescing clauses plus a `paid_at` check, and a second copy would
 * drift from the first, leaving the Zuordnung editor offering an invoice this list calls settled.
 */
class InvoiceListServiceImpl(
    private val repository: DocumentRepository,
    private val queueRepository: AllocationQueueRepository,
    private val bookkeepingStart: BookkeepingStartService,
) : InvoiceListService {
    override suspend fun list(
        bucket: InvoiceListDto.Bucket,
        limit: Int,
        offset: Long,
    ): InvoiceListDto.ListResponseDto {
        // Outside the dbQuery below: cutoff() opens a transaction of its own, and nesting one is
        // the pattern pos-server/CLAUDE.md forbids.
        val bookedFrom = bookkeepingStart.cutoff()
        return dbQuery {
            val items =
                when (bucket) {
                    InvoiceListDto.Bucket.ZU_ERFASSEN ->
                        repository.findUncapturedInvoiceDocuments(limit, offset).map { it.toRow() }

                    InvoiceListDto.Bucket.ZU_PRUEFEN ->
                        rowsOf(
                            repository.findInvoicesByReviewState(reviewed = false, limit, offset),
                            InvoiceListDto.Bucket.ZU_PRUEFEN,
                            bookedFrom,
                        )

                    InvoiceListDto.Bucket.FERTIG ->
                        rowsOf(
                            repository.findInvoicesByReviewState(reviewed = true, limit, offset),
                            InvoiceListDto.Bucket.FERTIG,
                            bookedFrom,
                        )

                    InvoiceListDto.Bucket.OFFEN ->
                        rowsOf(
                            queueRepository.openInvoices(
                                supplierId = null,
                                overdueAsOf = null,
                                bookedFrom = bookedFrom,
                                limit = limit,
                                offset = offset,
                            ),
                            InvoiceListDto.Bucket.OFFEN,
                            bookedFrom,
                        )

                    InvoiceListDto.Bucket.UEBERFAELLIG ->
                        rowsOf(
                            queueRepository.openInvoices(
                                supplierId = null,
                                overdueAsOf = currentTimeStamp(),
                                bookedFrom = bookedFrom,
                                limit = limit,
                                offset = offset,
                            ),
                            InvoiceListDto.Bucket.UEBERFAELLIG,
                            bookedFrom,
                        )

                    InvoiceListDto.Bucket.UEBERZAHLT ->
                        rowsOf(
                            queueRepository.overpaidInvoices(bookedFrom = bookedFrom, limit = limit, offset = offset),
                            InvoiceListDto.Bucket.UEBERZAHLT,
                            bookedFrom,
                        )
                }
            val counts = bucketCounts(bookedFrom)
            InvoiceListDto.ListResponseDto(
                items = items,
                total = counts.getValue(bucket.name),
                bucketCounts = counts,
                bucketSums = bucketSums(bookedFrom),
            )
        }
    }

    override suspend fun markReviewed(invoiceId: Long): InvoiceListDto.RowDto =
        dbQuery {
            val invoice = requireInvoice(invoiceId)
            // Idempotent on purpose: the first confirmation is the one an auditor asks about, so
            // re-confirming must not move the stamp.
            if (invoice.reviewedAt == null) invoice.reviewedAt = currentTimeStamp()
            rowsOf(listOf(invoice), InvoiceListDto.Bucket.FERTIG, bookedFrom = null).single()
        }

    /**
     * All six counts, always — a UI reading `bucketCounts["FERTIG"]` must get `0` rather than a
     * missing key rendering as a blank chip.
     *
     * They do **not** sum to a total: the capture buckets and the payment buckets answer different
     * questions, so one invoice is routinely counted in one of each. See [InvoiceListDto.Bucket].
     */
    private fun bucketCounts(bookedFrom: LocalDateTime?): Map<String, Long> =
        mapOf(
            InvoiceListDto.Bucket.ZU_ERFASSEN.name to repository.countUncapturedInvoiceDocuments(),
            InvoiceListDto.Bucket.ZU_PRUEFEN.name to repository.countInvoicesByReviewState(reviewed = false),
            InvoiceListDto.Bucket.FERTIG.name to repository.countInvoicesByReviewState(reviewed = true),
            InvoiceListDto.Bucket.OFFEN.name to
                queueRepository.countOpenInvoices(null, overdueAsOf = null, bookedFrom = bookedFrom),
            InvoiceListDto.Bucket.UEBERFAELLIG.name to
                queueRepository.countOpenInvoices(null, overdueAsOf = currentTimeStamp(), bookedFrom = bookedFrom),
            InvoiceListDto.Bucket.UEBERZAHLT.name to queueRepository.countOverpaidInvoices(bookedFrom),
        )

    /**
     * What the three payment buckets are worth, over exactly the rows [bucketCounts] counts.
     *
     * The capture buckets are absent rather than zero — see [InvoiceListDto.ListResponseDto.bucketSums].
     * A count says how many invoices are late; this says how much money is, and an operator opens
     * Rechnungen with the second question.
     */
    private fun bucketSums(bookedFrom: LocalDateTime?): Map<String, String> =
        mapOf(
            InvoiceListDto.Bucket.OFFEN.name to
                queueRepository.sumOpenInvoices(null, overdueAsOf = null, bookedFrom = bookedFrom).toDecimalString(),
            InvoiceListDto.Bucket.UEBERFAELLIG.name to
                queueRepository
                    .sumOpenInvoices(null, overdueAsOf = currentTimeStamp(), bookedFrom = bookedFrom)
                    .toDecimalString(),
            InvoiceListDto.Bucket.UEBERZAHLT.name to queueRepository.sumOverpaidInvoices(bookedFrom).toDecimalString(),
        )

    /**
     * Maps a page and decides its two flags in one batch each: [InvoiceListDto.RowDto.overdue] asks
     * the allocation queue (so it is the Überfällig bucket's own predicate) and
     * [InvoiceListDto.RowDto.cancelled] asks [InvoiceStorno].
     */
    private fun rowsOf(
        invoices: List<Invoice>,
        bucket: InvoiceListDto.Bucket,
        bookedFrom: LocalDateTime?,
    ): List<InvoiceListDto.RowDto> {
        val ids = invoices.map { it.id.value }
        val overdueIds = queueRepository.overdueInvoiceIdsAmong(ids, currentTimeStamp(), bookedFrom)
        val cancelledIds = InvoiceStorno.cancelledIdsAmong(invoices)
        return invoices.map { it.toRow(bucket, overdue = it.id.value in overdueIds, cancelled = it.id.value in cancelledIds) }
    }

    private fun requireInvoice(id: Long): Invoice =
        repository.getInvoice(id)
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Invoice $id not found",
            )

    /** An uncaptured document: document-keyed, with nothing parsed out of it yet. */
    private fun Document.toRow(): InvoiceListDto.RowDto =
        InvoiceListDto.RowDto(
            bucket = InvoiceListDto.Bucket.ZU_ERFASSEN,
            documentId = id.value,
            filename = filename,
        )

    /**
     * A captured invoice: invoice-keyed, since one document may hold several invoices.
     *
     * [InvoiceListDto.RowDto.documentId] still carries the source document when there is one — an
     * invoice extracted from a scanned PDF has one, and the list needs it to offer a preview. What is
     * "one or the other" is which id **identifies** the row, not which ids it may expose: `invoiceId`
     * being non-null is the discriminator.
     *
     * Read from `readValues` rather than `sourceDocument?.id`, which would load the whole `Document`
     * entity for every row — an N+1 across a page of 200.
     */
    private fun Invoice.toRow(
        bucket: InvoiceListDto.Bucket,
        overdue: Boolean,
        cancelled: Boolean,
    ): InvoiceListDto.RowDto =
        InvoiceListDto.RowDto(
            bucket = bucket,
            invoiceId = id.value,
            documentId = readValues[InvoiceTable.sourceDocumentId]?.value,
            supplierName = sellerName,
            invoiceNumber = invoiceNumber,
            issueDate = issueDate?.toString(),
            grossTotal = taxInclusiveTotal?.toDecimalString(),
            currency = currency,
            eInvoiceFormat = eInvoiceFormat,
            dueDate = dueDate?.toString(),
            // The same fallback the open-invoice query uses, and for the same reason: nothing writes
            // `open_amount` on insert, so a freshly parsed invoice has a NULL column and a real debt.
            // `toExactDecimalString` keeps the DECIMAL(19,4) scale the scale-2 formatter would round.
            openAmount = (openAmount ?: amountDue ?: taxInclusiveTotal)?.toExactDecimalString(),
            cancelled = cancelled,
            overdue = overdue,
        )
}
