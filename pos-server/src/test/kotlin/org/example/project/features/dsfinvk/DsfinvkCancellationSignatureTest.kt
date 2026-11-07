package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 (isolated audit 2026-07-22): cancelling N items in one action signed **once** over a combined
 * Bestellung-V1 payload, but the export emitted N Vorgänge (`C-1`, `C-2`, …) — one per per-item
 * `Cancellation` row — all carrying that same `TSE_TANR` / `TSE_TA_SIGZ` / `TSE_TA_SIG`.
 *
 * The defect was never "one signature covers several items": **an order of N items already works
 * that way** — one `Orders` row, one `AVBestellung` Vorgang, one signature over all its lines. The
 * defect was the MISMATCH. Because the signature attests to the aggregate payload, a verifier
 * recomputing it over any single `C-n` Beleg gets a different value, so none was individually
 * verifiable, and an auditor saw one transaction number claimed by N Vorgänge (§3.1.2.5:
 * `transactions_tse` holds "die Daten der Transaktionen", one per Vorgang).
 *
 * Fixed by making the export match the signature rather than the other way round: the rows from one
 * cancel action share a `batchId` and are emitted as ONE Vorgang carrying N Bonpos lines — the same
 * shape as an order. Signing per item would also have been consistent, but would have left the
 * system lopsided (ordering 3 items = 1 signature, cancelling them = 3) and burned the device's
 * finite signature counter for no fiscal gain.
 */
@DisplayName("Each cancellation Vorgang gets its own TSE transaction (M2)")
class DsfinvkCancellationSignatureTest {
    private val deviceId = "cancel-sig-device"

    @Test
    fun `three cancelled items form ONE Vorgang sharing ONE TSE transaction`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "CS_MGR", username = "cs_mgr")
                TestData.createShopInfo()
                val vats = TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("CSIG")
                val category = TestData.createCategory("CancelSig Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "CancelSig Product",
                        productNumber = "CSIG",
                        price = BigDecimal("4.0000"),
                    )
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = this@DsfinvkCancellationSignatureTest.deviceId
                        this.clientId = "TERM-CSIG"
                        this.registeredAt = now
                        this.lastSeenAt = now
                    }
                }

                // The factory picks RealTseTransactionServiceImpl for SIMULATION, so signing actually
                // persists transactions instead of returning null (DISABLED is the default).
                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(
                            timeAdminPin = "12345",
                            mode = TseMode.SIMULATION,
                        ),
                    )
                }

                val itemIds =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = manager
                        (1..3).map { n ->
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = product
                                    this.unitPrice = BigDecimal("4.00")
                                    this.finalPrice = BigDecimal("4.00")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vats.first()
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = "line $n"
                                }.id.value
                        }
                    }

                runBlocking {
                    GlobalContext.get().get<OrderItemService>().cancelOrderItems(
                        item = itemIds,
                        employeeId = manager.id.value.toString(),
                        deviceId = deviceId,
                        reason = "M2 test",
                        sourceTableId = tws.table.id.value,
                    )
                }

                transaction {
                    val cancellations =
                        itemIds.map { id ->
                            Cancellation.find { CancellationTable.orderItemId eq id }.single()
                        }
                    assertEquals(3, cancellations.size, "fixture sanity: three items were cancelled")

                    val txNumbers = cancellations.map { it.tseTransaction?.transactionNumber }
                    assertTrue(
                        txNumbers.none { it == null },
                        "the cancel action must be signed; got $txNumbers",
                    )
                    assertEquals(
                        1,
                        txNumbers.toSet().size,
                        "one cancel action is ONE Vorgang, so it carries ONE signature — exactly as an " +
                            "order of N items does. Got $txNumbers",
                    )

                    val batchIds = cancellations.map { it.batchId }
                    assertTrue(
                        batchIds.none { it == null },
                        "every row must carry the batch id the export groups on; got $batchIds",
                    )
                    assertEquals(
                        1,
                        batchIds.toSet().size,
                        "all rows from ONE cancel action must share ONE batch id, or the export splits " +
                            "them back into separate Vorgänge. Got $batchIds",
                    )
                }
            },
        ) { }

    /**
     * The signed payload must describe the WHOLE Vorgang — every line the exported Beleg carries —
     * or the signature cannot be recomputed from the Beleg's own data.
     */
    @Test
    fun `the signed payload covers every line of the Vorgang`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "CS_MG2", username = "cs_mg2")
                TestData.createShopInfo()
                val vats = TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("CSIG2")
                val category = TestData.createCategory("CancelSig Cat2")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "Eisbecher",
                        productNumber = "CSIG2",
                        price = BigDecimal("3.9900"),
                    )
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = this@DsfinvkCancellationSignatureTest.deviceId + "2"
                        this.clientId = "TERM-CSIG2"
                        this.registeredAt = now
                        this.lastSeenAt = now
                    }
                }
                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(
                            timeAdminPin = "12345",
                            mode = TseMode.SIMULATION,
                        ),
                    )
                }

                val itemIds =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = manager
                        (1..2).map {
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = product
                                    this.unitPrice = BigDecimal("3.99")
                                    this.finalPrice = BigDecimal("3.99")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vats.first()
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        }
                    }

                runBlocking {
                    GlobalContext.get().get<OrderItemService>().cancelOrderItems(
                        item = itemIds,
                        employeeId = manager.id.value.toString(),
                        deviceId = deviceId + "2",
                        reason = "M2 payload test",
                        sourceTableId = tws.table.id.value,
                    )
                }

                transaction {
                    val payloads =
                        itemIds.map { id ->
                            Cancellation
                                .find { CancellationTable.orderItemId eq id }
                                .single()
                                .tseTransaction!!
                                .processDataHash!!
                        }
                    assertEquals(
                        1,
                        payloads.toSet().size,
                        "both lines belong to one Vorgang, so both rows point at the same signed payload",
                    )
                    // The two items are identical, so toGroupedOrderItemResponseDto collapses them into
                    // one line with MENGE -2 — the reversal sign lives in MENGE (Anhang I p. 113/117).
                    assertTrue(
                        payloads.first().startsWith("-2;"),
                        "the payload must describe the whole Vorgang; got: ${payloads.first()}",
                    )
                }
            },
        ) { }
}
