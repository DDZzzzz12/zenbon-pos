package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3 (isolated audit 2026-07-22): a cancellation Vorgang went out with NO `Bon_Referenzen` row.
 *
 * `generateReferences(bills, zBons, cashRegisterId, bonposPlans, exportableKassenbuchEntries)` was
 * never passed `cancellations`. The only cancellation-reference branch lived inside
 * `bills.forEach { … resolveOrderItems(bill) … item.cancellation }`, which resolves items by
 * `billId` — but `cancelOrderItems` only permits cancelling `OPEN` items, and an OPEN item has
 * `billId == null`. So the branch was structurally unreachable through the service guards; the
 * existing test for it says so in its own KDoc and seeds raw entities to reach it.
 *
 * Result: every cancelled line exported as `C-<batch>` with a negative `UMS_BRUTTO` and
 * `BON_NAME = "Storno"`, and nothing recording WHICH Vorgang it reverses — contrary to Anhang B
 * p. 45 („eine Referenz auf den ursprünglichen Vorgang muss erfolgen") and Tz. 4.2.2.
 *
 * That dead branch also pointed `REF_BON_ID` at a BILL. For an order-item cancellation the original
 * Vorgang is the ORDER (`O-<id>`): the item was cancelled while still OPEN, so it was never billed.
 *
 * This test deliberately drives the REAL service path — create an order, cancel its open items —
 * so it cannot pass against an unreachable branch.
 */
@DisplayName("Cancellation Vorgang references the order it reverses (M3)")
class DsfinvkCancellationReferenceReachableTest {
    private val deviceId = "cancel-ref-device"
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @Test
    fun `a cancelled order line emits a Transaktion reference to its order`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "CR_MGR", username = "cr_mgr")
                val shop = TestData.createShopInfo()
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("CREF")
                val category = TestData.createCategory("CancelRef Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "CancelRef Product",
                        productNumber = "CREF",
                        price = BigDecimal("5.0000"),
                    )
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = this@DsfinvkCancellationReferenceReachableTest.deviceId
                        this.clientId = "TERM-CREF"
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

                // Real path: an ORDER of two lines, then cancel both while still OPEN.
                val order =
                    runBlocking {
                        GlobalContext.get().get<OrderService>().createOrder(
                            request =
                                OrderDto.CreateOrderRequestDto(
                                    tableInfoId = tws.session.id.value,
                                    orderItems =
                                        listOf(
                                            OrderItemDto.CreateOrderItemRequestDto(productId = product.id.value, unitPrice = "5.0"),
                                            OrderItemDto.CreateOrderItemRequestDto(productId = product.id.value, unitPrice = "5.0"),
                                        ),
                                ),
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            actorCapabilities = emptySet(),
                        )
                    }
                val itemIds = order.orderItems.flatMap { it.orderItemIds }
                assertEquals(2, itemIds.size, "fixture sanity: the order has two lines")

                runBlocking {
                    GlobalContext.get().get<OrderItemService>().cancelOrderItems(
                        item = itemIds,
                        employeeId = manager.id.value.toString(),
                        deviceId = deviceId,
                        reason = "M3 test",
                        sourceTableId = tws.table.id.value,
                    )
                }

                // The export is closing-driven. Cancellations are selected by Z-Bon association
                // (CancellationTable.zBonId inList zBonIds), which ZBonServiceImpl.createReport sets;
                // this fixture does the same attach so the Vorgang is in scope.
                val zbon = TestData.createZBonFullDay(shop, manager)
                transaction {
                    itemIds.forEach { id ->
                        Cancellation
                            .find { CancellationTable.orderItemId eq id }
                            .forEach { it.zBon = zbon }
                    }
                }

                val zip =
                    runBlocking {
                        service.generateExport(
                            DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                        )
                    }

                val txRows = readRows(zip, "transactions.csv")
                val cancellationBonId =
                    txRows.singleOrNull { it["BON_NAME"] == "Storno" }?.get("BON_ID")
                        ?: error("no Storno Vorgang in transactions.csv; rows=${txRows.map { it["BON_ID"] to it["BON_NAME"] }}")
                val orderBonId =
                    txRows.singleOrNull { it["BON_TYP"] == "AVBestellung" && it["BON_NAME"] != "Storno" }?.get("BON_ID")
                        ?: error("no order Vorgang in transactions.csv; rows=${txRows.map { it["BON_ID"] to it["BON_NAME"] }}")

                val refs = readRows(zip, "references.csv").filter { it["BON_ID"] == cancellationBonId }

                assertTrue(
                    refs.isNotEmpty(),
                    "Anhang B p. 45 requires a reference to the reversed Vorgang; references.csv had " +
                        "none for $cancellationBonId. Rows present: ${readRows(zip, "references.csv")}",
                )
                assertEquals(
                    1,
                    refs.size,
                    "the cancel action is ONE Vorgang, so it carries ONE reference row; got $refs",
                )
                assertEquals(
                    DsfinvkExportDto.ReferenceTypes.TRANSAKTION,
                    refs.single()["REF_TYP"],
                    "an internal back-reference is REF_TYP Transaktion",
                )
                assertEquals(
                    orderBonId,
                    refs.single()["REF_BON_ID"],
                    "REF_BON_ID is „die ursprüngliche BON_ID\" — the ORDER the items were cancelled " +
                        "from. They were OPEN, so they were never billed and no bill can be referenced.",
                )
                assertTrue(
                    refs.single()["REF_Z_NR"]?.isNotBlank() == true,
                    "Anhang E p. 102-103: REF_Z_NR is to be filled for REF_TYP Transaktion; got ${refs.single()}",
                )
            },
        ) { }

    private fun readRows(
        zip: ByteArray,
        name: String,
    ): List<Map<String, String>> {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    if (lines.size < 2) return emptyList()
                    val header = lines.first().split(";").map { it.trim('"') }
                    return lines.drop(1).map { line ->
                        header.zip(line.split(";").map { it.trim('"') }).toMap()
                    }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found")
    }
}
