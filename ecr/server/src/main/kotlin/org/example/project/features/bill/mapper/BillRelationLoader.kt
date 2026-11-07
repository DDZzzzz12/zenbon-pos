package org.example.project.features.bill.mapper

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.order.database.tables.OrderItemTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Batch loader for the visible links between related bills ([BillDto.BillRelationsDto]).
 *
 * Every link already exists as a foreign key — `deleted_bill_id` (Storno → original),
 * `deleting_reason` on the cancelled original, `rebooks_bill_id` (Neubuchung → stornoed original),
 * `clears_sales_invoice_id` (clearing → invoice → `sales_invoice.bill_id`) and the bill's items'
 * `duplicated_from_bill_id` (re-billed after a "re-open the table" Storno, [RebilledOriginals]). This
 * loader only reads them, for a whole page of bills at once: at most six SELECTs whatever the page
 * size, never one per bill. The list endpoints call it once per page; the single-bill read calls it with one id.
 *
 * Must be called inside an active transaction.
 */
object BillRelationLoader {
    /** The foreign keys a bill holds itself, read in one query for the whole batch. */
    private data class OwnLinks(
        val reversesId: Long?,
        val rebooksId: Long?,
        val clearsInvoiceId: Long?,
        val isCancelledOriginal: Boolean,
        val stornoReason: StornoReason?,
    )

    /**
     * Relations of every bill in [billIds]. A bill id that does not exist maps to the default
     * (an ordinary sale with no links).
     */
    fun load(
        billIds: Collection<Long>,
        invoices: BillInvoiceLink,
    ): Map<Long, BillDto.BillRelationsDto> {
        if (billIds.isEmpty()) return emptyMap()
        val ids = billIds.distinct()
        val own = ownLinksOf(ids)
        val itemSources = itemSourcesOf(ids)
        val rebilled =
            itemSources.mapValues { (id, sources) ->
                RebilledOriginals.of(rebooksId = null, itemSourceIds = sources).filter { it != own[id]?.rebooksId }
            }
        val parentNumbers =
            numbersOf(
                own.values.mapNotNull { it.reversesId } + own.values.mapNotNull { it.rebooksId } + rebilled.values.flatten(),
            )
        val reversals = reversalsOf(ids)
        val rebookings = rebookedByOf(ids)
        val clearedBills = invoices.billLinksForInvoices(own.values.mapNotNull { it.clearsInvoiceId })
        return ids.associateWith { id ->
            val links = own[id] ?: return@associateWith BillDto.BillRelationsDto()
            BillDto.BillRelationsDto(
                kind = kindOf(links),
                isCancelled = links.isCancelledOriginal,
                reversesBill = links.reversesId?.let { linkTo(it, parentNumbers) },
                reversedBy = reversals[id],
                rebooksBill = links.rebooksId?.let { linkTo(it, parentNumbers) },
                rebookedBy = rebookings[id],
                clearsBill = links.clearsInvoiceId?.let { clearedBills[it] },
                stornoReason = links.stornoReason.takeIf { links.reversesId != null },
                rebilledFromBills = rebilled[id].orEmpty().mapNotNull { linkTo(it, parentNumbers) },
                rebillIsPartial = rebilled[id].orEmpty().isNotEmpty() && null in itemSources[id].orEmpty(),
            )
        }
    }

    private fun kindOf(links: OwnLinks): BillKind =
        BillKind.of(
            reversesBill = links.reversesId != null,
            rebooksBill = links.rebooksId != null,
            clearsInvoice = links.clearsInvoiceId != null,
        )

    private fun linkTo(
        billId: Long,
        numbers: Map<Long, String>,
    ): BillDto.BillLinkDto? = numbers[billId]?.let { BillDto.BillLinkDto(billId, it) }

    private fun ownLinksOf(ids: List<Long>): Map<Long, OwnLinks> =
        BillTable
            .select(
                BillTable.id,
                BillTable.deletedBillId,
                BillTable.rebooksBillId,
                BillTable.clearsSalesInvoiceId,
                BillTable.deletingReason,
                BillTable.stornoReason,
            ).where { BillTable.id.inList(ids.map { EntityID(it, BillTable) }) }
            .associate { row ->
                val reversesId = row[BillTable.deletedBillId]?.value
                row[BillTable.id].value to
                    OwnLinks(
                        reversesId = reversesId,
                        rebooksId = row[BillTable.rebooksBillId]?.value,
                        clearsInvoiceId = row[BillTable.clearsSalesInvoiceId],
                        // A Storno bill carries the reason too; only the ORIGINAL is "cancelled".
                        isCancelledOriginal = row[BillTable.deletingReason] != null && reversesId == null,
                        stornoReason = row[BillTable.stornoReason],
                    )
            }

    /**
     * Per bill in [ids], the distinct `duplicated_from_bill_id` values of its own items — null for
     * an item that was not handed back by a Storno. One grouped query for the whole batch.
     */
    private fun itemSourcesOf(ids: List<Long>): Map<Long, Set<Long?>> =
        OrderItemTable
            .select(OrderItemTable.billId, OrderItemTable.duplicatedFromBillId)
            .where { OrderItemTable.billId.inList(ids.map { EntityID(it, BillTable) }) }
            .groupBy(OrderItemTable.billId, OrderItemTable.duplicatedFromBillId)
            .groupBy({ it[OrderItemTable.billId]!!.value }, { it[OrderItemTable.duplicatedFromBillId]?.value })
            .mapValues { (_, sources) -> sources.toSet() }

    private fun numbersOf(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return BillTable
            .select(BillTable.id, BillTable.billNumber)
            .where { BillTable.id.inList(ids.distinct().map { EntityID(it, BillTable) }) }
            .associate { it[BillTable.id].value to it[BillTable.billNumber] }
    }

    /** The Storno of every bill in [ids], keyed by the reversed bill, in one query. */
    private fun reversalsOf(ids: List<Long>): Map<Long, BillDto.BillLinkDto> {
        val parents = ids.map { EntityID(it, BillTable) }
        return BillTable
            .select(BillTable.id, BillTable.billNumber, BillTable.deletedBillId)
            .where { BillTable.deletedBillId.inList(parents) }
            .associate { it[BillTable.deletedBillId]!!.value to BillDto.BillLinkDto(it[BillTable.id].value, it[BillTable.billNumber]) }
    }

    /** The Neubuchung of every bill in [ids], keyed by the bill it rebooks, in one query. */
    private fun rebookedByOf(ids: List<Long>): Map<Long, BillDto.BillLinkDto> {
        val parents = ids.map { EntityID(it, BillTable) }
        return BillTable
            .select(BillTable.id, BillTable.billNumber, BillTable.rebooksBillId)
            .where { BillTable.rebooksBillId.inList(parents) }
            .associate { it[BillTable.rebooksBillId]!!.value to BillDto.BillLinkDto(it[BillTable.id].value, it[BillTable.billNumber]) }
    }
}
