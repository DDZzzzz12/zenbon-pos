package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
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
 * M4 (isolated audit 2026-07-22): `generateItemAmounts` returned `emptyList()` unconditionally, so
 * `itemamounts.csv` (Bonpos_Preisfindung) was always header-only.
 *
 * §3.1.1.2 makes that conditional, not absolute:
 *
 *   „Eintragungen in dieser Detailtabelle sind nur für die Positionen erforderlich, bei denen
 *    tatsächlich Rabatte oder Aufschläge verwendet werden **und der Preis in der Datei Bonpos
 *    bereits der rabattierte Preis ist**. Wenn der Rabatt über eine separate Buchung abgebildet
 *    wird, muss kein Eintrag erfolgen."
 *
 * The BILL path satisfies the second sentence — it books every Entgeltminderung as its own Bonpos
 * `Rabatt` line via `discountRabattAmount`, so no Preisfindung entry is owed. The ORDER path does
 * not: `generateOrderLineItems` emits `STK_BR = item.finalPrice`, already discounted by
 * `OrderServiceImpl` (`finalPrice = (unitPrice + extrasDelta) * discount`), and emits **no** Rabatt
 * line at all. That is exactly the first sentence's case, so the entry IS required.
 *
 * Without it a 20 %-discounted item ordered at 10.00 exports as `STK_BR 8,00` with nothing
 * anywhere recording the 10.00 base or the 2.00 reduction — the discount is invisible at order
 * level, and the same goods then appear on the Beleg as `10,00` plus a separate `Rabatt −2,00`.
 * The two Vorgänge for the same goods disagree in shape.
 */
@DisplayName("Bonpos_Preisfindung for pre-discounted order lines (M4)")
class DsfinvkOrderDiscountPreisfindungTest {
    private val deviceId = "preisfindung-device"
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @Test
    fun `a discounted order line records its base price and reduction`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "PF_MGR", username = "pf_mgr")
                val shop = TestData.createShopInfo()
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("PFIND")
                val category = TestData.createCategory("Preisfindung Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "Preisfindung Product",
                        productNumber = "PFIND",
                        price = BigDecimal("10.0000"),
                    )
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = this@DsfinvkOrderDiscountPreisfindungTest.deviceId
                        this.clientId = "TERM-PFIND"
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

                // 10.00 at 20 % off → finalPrice 8.00. STK_BR will be 8,00, so the base and the
                // reduction have to be recoverable from Bonpos_Preisfindung.
                runBlocking {
                    GlobalContext.get().get<OrderService>().createOrder(
                        request =
                            OrderDto.CreateOrderRequestDto(
                                tableInfoId = tws.session.id.value,
                                orderItems =
                                    listOf(
                                        OrderItemDto.CreateOrderItemRequestDto(
                                            productId = product.id.value,
                                            unitPrice = "10.0",
                                            discount = 0.80,
                                        ),
                                    ),
                            ),
                        employeeId = manager.id.value.toString(),
                        deviceId = deviceId,
                        actorCapabilities = emptySet(),
                    )
                }

                TestData.createZBonFullDay(shop, manager)

                val zip =
                    runBlocking {
                        service.generateExport(
                            DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                        )
                    }

                val orderLine = readRows(zip, "lines.csv").single { it["BON_ID"]?.startsWith("O-") == true }
                assertEquals(
                    "8,00",
                    orderLine["STK_BR"],
                    "fixture sanity: the exported order line must carry the DISCOUNTED price, which is " +
                        "what makes a Preisfindung entry owed",
                )

                val pf =
                    readRows(zip, "itemamounts.csv")
                        .filter { it["BON_ID"] == orderLine["BON_ID"] && it["POS_ZEILE"] == orderLine["POS_ZEILE"] }

                assertTrue(
                    pf.isNotEmpty(),
                    "§3.1.1.2: a Preisfindung entry is required when the Bonpos price is already the " +
                        "discounted one and no separate Rabatt booking exists. itemamounts.csv held: " +
                        "${readRows(zip, "itemamounts.csv")}",
                )

                val base = pf.single { it["TYP"] == DsfinvkExportDto.PriceFindingTypes.BASISPREIS }
                val rabatt = pf.single { it["TYP"] == DsfinvkExportDto.PriceFindingTypes.RABATT }

                // Anhang E p. 90: „Beträge sind mit zwei Dezimalstellen darzustellen, obwohl das
                // Datenfeld eigentlich auf 5 Dezimalstellen ausgelegt ist."
                assertEquals("10,00", base["PF_BRUTTO"], "the base price before the reduction")
                assertEquals("-2,00", rabatt["PF_BRUTTO"], "the reduction, signed negative")

                // The decomposition must reconstruct exactly what Bonpos charges.
                val sum = pf.sumOf { BigDecimal(it["PF_BRUTTO"]!!.replace(',', '.')) }
                assertEquals(
                    0,
                    BigDecimal("8.00").compareTo(sum),
                    "Σ PF_BRUTTO must equal the line's STK_BR, or the breakdown does not explain the " +
                        "charged price; got $pf",
                )
            },
        ) { }

    @Test
    fun `an undiscounted order line gets no Preisfindung rows`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "PF_MG2", username = "pf_mg2")
                val shop = TestData.createShopInfo()
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession("PFIND2")
                val category = TestData.createCategory("Preisfindung Cat2")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "Preisfindung Product2",
                        productNumber = "PFIND2",
                        price = BigDecimal("10.0000"),
                    )
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = this@DsfinvkOrderDiscountPreisfindungTest.deviceId + "2"
                        this.clientId = "TERM-PFIND2"
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

                runBlocking {
                    GlobalContext.get().get<OrderService>().createOrder(
                        request =
                            OrderDto.CreateOrderRequestDto(
                                tableInfoId = tws.session.id.value,
                                orderItems =
                                    listOf(
                                        OrderItemDto.CreateOrderItemRequestDto(
                                            productId = product.id.value,
                                            unitPrice = "10.0",
                                        ),
                                    ),
                            ),
                        employeeId = manager.id.value.toString(),
                        deviceId = deviceId + "2",
                        actorCapabilities = emptySet(),
                    )
                }

                TestData.createZBonFullDay(shop, manager)

                val zip =
                    runBlocking {
                        service.generateExport(
                            DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                        )
                    }

                assertTrue(
                    readRows(zip, "itemamounts.csv").isEmpty(),
                    "§3.1.1.2 requires entries ONLY where a Rabatt or Aufschlag was actually used — an " +
                        "undiscounted line must stay out of the file",
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
