package org.example.project.features.bill

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.mapper.BillRelationLoader
import org.example.project.features.bill.mapper.RebilledOriginals
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sale that settles items a "re-open the table" Storno handed back names the stornoed original(s)
 * on its relations (`rebilledFromBills`, "(teilweise)" via `rebillIsPartial`). Derived from the
 * bill's own items' `duplicated_from_bill_id` — no bill column — through the real bill service.
 */
@DisplayName("Bill relations — a re-bill names the stornoed originals its items came from")
class BillRebillRelationsTest {
    private val deviceId = "rebill-relations-device"

    @AfterEach
    fun tearDown() {
        BillNumberTracker.clearAll()
    }

    private fun relationsOf(vararg ids: Long): Map<Long, BillDto.BillRelationsDto> =
        transaction {
            BillRelationLoader.load(ids.toList(), SalesInvoiceBillInvoiceLink())
        }

    private fun numberOf(id: Long): String = transaction { Bill.findById(id)!!.billNumber }

    private fun link(id: Long) = BillDto.BillLinkDto(id, numberOf(id))

    private fun stornoOf(id: Long): Long =
        transaction {
            Bill
                .find { BillTable.deletedBillId eq id }
                .single()
                .id.value
        }

    @Test
    fun `a mixed bill links to the original and reads as partial`() =
        testApp { _ ->
            val f = RebillFixture(deviceId, "RBM").seed()
            val original = f.bill(f.order("10.00", "5.00"))
            f.stornoReopen(original)
            val added = f.order("3.00")
            val rebill = f.bill(f.handedBackFrom(original) + added)

            val relations = relationsOf(rebill, original, stornoOf(original))
            with(relations.getValue(rebill)) {
                assertEquals(BillKind.SALE, kind, "a re-bill is still an ordinary sale")
                assertEquals(listOf(link(original)), rebilledFromBills)
                assertTrue(rebillIsPartial, "the bill also holds a line ordered after the table was re-opened")
                assertNull(rebooksBill)
            }
            // Negative controls: neither the original nor its Storno re-bills anything — the Storno's
            // items are the original's, and the original's lines were ordered, not handed back.
            assertTrue(relations.getValue(original).rebilledFromBills.isEmpty())
            assertTrue(relations.getValue(stornoOf(original)).rebilledFromBills.isEmpty())
            assertFalse(relations.getValue(original).rebillIsPartial)
        }

    @Test
    fun `a bill of new items only links nothing, and handed-back items split over two bills link from both`() =
        testApp { _ ->
            val f = RebillFixture(deviceId, "RBS").seed()
            val original = f.bill(f.order("10.00", "5.00"))
            f.stornoReopen(original)
            val newOnly = f.bill(f.order("3.00"))
            val (first, second) = f.handedBackFrom(original)
            val rebillA = f.bill(listOf(first))
            val rebillB = f.bill(listOf(second))

            val relations = relationsOf(newOnly, rebillA, rebillB)
            assertEquals(BillDto.BillRelationsDto(), relations.getValue(newOnly), "a bill of new items is a plain sale")
            listOf(rebillA, rebillB).forEach { id ->
                assertEquals(listOf(link(original)), relations.getValue(id).rebilledFromBills, "bill $id holds a handed-back line")
                assertFalse(relations.getValue(id).rebillIsPartial, "bill $id holds only handed-back lines")
            }
        }

    @Test
    fun `a table re-opened by two Stornos is re-billed with links to both originals`() =
        testApp { _ ->
            val f = RebillFixture(deviceId, "RB2").seed()
            // Two partial bills of one round: a bill that pays every open line completes the session.
            val (line1, line2) = f.order("10.00", "5.00")
            val first = f.bill(listOf(line1))
            val second = f.bill(listOf(line2))
            f.stornoReopen(first)
            f.stornoReopen(second)
            val rebill = f.bill(f.handedBackFrom(first) + f.handedBackFrom(second))

            with(relationsOf(rebill).getValue(rebill)) {
                assertEquals(listOf(link(first), link(second)), rebilledFromBills, "each original once, oldest first")
                assertFalse(rebillIsPartial)
            }
        }

    @Test
    fun `a Zahlartwechsel keeps its own link and names no original twice`() =
        testApp { _ ->
            val f = RebillFixture(deviceId, "RBZ").seed()
            val original = f.bill(f.order("10.00"))
            // Plain Zahlartwechsel: its copies carry the original too, which rebooksBill already names.
            val neubuchung = f.rebookToCard(original, "10.00")
            with(relationsOf(neubuchung.billId).getValue(neubuchung.billId)) {
                assertEquals(BillKind.REBOOKING, kind)
                assertEquals(link(original), rebooksBill)
                assertTrue(rebilledFromBills.isEmpty(), "the rebooked original is not repeated, got $rebilledFromBills")
                assertFalse(rebillIsPartial)
            }
        }

    @Test
    fun `a Zahlartwechsel of a re-bill names the re-bill, not the earlier original`() =
        testApp { _ ->
            val f = RebillFixture(deviceId, "RBC").seed()
            val original = f.bill(f.order("10.00"))
            f.stornoReopen(original)
            val rebill = f.bill(f.handedBackFrom(original) + f.order("3.00"))
            val neubuchung = f.rebookToCard(rebill, "13.00")

            with(relationsOf(neubuchung.billId).getValue(neubuchung.billId)) {
                assertEquals(link(rebill), rebooksBill)
                assertTrue(rebilledFromBills.isEmpty(), "the copies point at the re-bill, which rebooksBill names, got $rebilledFromBills")
            }
        }

    @Test
    fun `the shared rule merges a Neubuchung's own link with its items' sources`() {
        assertEquals(listOf(7L, 3L, 9L), RebilledOriginals.of(rebooksId = 7L, itemSourceIds = listOf(9L, null, 7L, 3L, 3L)))
        assertEquals(emptyList(), RebilledOriginals.of(rebooksId = null, itemSourceIds = listOf(null, null)))
        assertEquals(listOf(4L), RebilledOriginals.of(rebooksId = null, itemSourceIds = listOf(4L, 4L)))
    }
}
