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
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertTrue

/**
 * m12 (isolated audit 2026-07-22, re-diagnosed on the primary text): `vat.csv` declared a STATIC
 * list of all 15 Anlage-2 keys for every closing.
 *
 * §3.2.6: „Die Zuordnung der **verwendeten** Umsatzsteuerschlüssel wird innerhalb der DSFinV-K in
 * den Stammdaten festgelegt." — the keys in USE, not the whole catalogue.
 *
 * Two symptoms the original audit reported separately both reduce to this:
 *
 *  - Key 7 („UmsatzsteuerNichtErmittelbar") was declared unconditionally. Tz. 3.2.6 attaches a
 *    documentation obligation to *using* it („Wird die ID 7 **eingesetzt**, ist dies … im Datenfeld
 *    KEINE_UST_ZUORDNUNG zu dokumentieren"). The flag itself is correct — it is set exactly when
 *    key 7 appears in that closing's rows — so declaring an unused key was never a contradiction,
 *    just noise. Not declaring it removes the question entirely.
 *  - Keys 1–4 were declared with TODAY's statutory rate even when re-exporting a historical
 *    closing. That is harmless in itself, because §3.2.6 routes a previously valid rate to the
 *    historical IDs from 11 upward (`getVatKey` maps 9.00 % → key 33, never key 3), so no line ever
 *    referenced a key whose declared rate was untrue. But an UNUSED key 3 carrying 7,80 in a 2024
 *    closing is still an untrue catalogue line — and it disappears once only used keys are declared.
 *
 * The invariant that matters in both directions: every declared key is used, and every used key is
 * declared. The second half is what keeps the joins intact.
 */
@DisplayName("Stamm_USt declares the keys actually used (m12)")
class DsfinvkStammUstUsedKeysTest {
    private val deviceId = "stammust-device"
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    /**
     * `testApp` sets up a fresh in-memory DB but does not tear one down. Without this, the shop and
     * closing seeded here survive into the next class — `DsfinvkStammdatenSnapshotTest` reads
     * `location.csv` with `.single()` and sees two rows. Tests must not depend on who runs next.
     */
    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `vat-csv declares exactly the keys the closing uses`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "SU_MGR", username = "su_mgr")
                val shop = TestData.createShopInfo()
                TestData.createDefaultVatRates() // A = 19 %, B = 7 %
                val tws = TestData.createTableWithSession("SUST")
                val category = TestData.createCategory("StammUst Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "StammUst Product",
                        productNumber = "SUST",
                        price = BigDecimal("10.0000"),
                    )
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = this@DsfinvkStammUstUsedKeysTest.deviceId
                        this.clientId = "TERM-SUST"
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

                // A single 19 % line — so key 1 is used and nothing else needs to be.
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

                val declared = readRows(zip, "vat.csv").map { it["UST_SCHLUESSEL"]!! }.toSet()

                // Every key any row actually references, across all four files that carry one.
                val used =
                    buildSet {
                        addAll(readRows(zip, "lines_vat.csv").map { it["UST_SCHLUESSEL"]!! })
                        addAll(readRows(zip, "transactions_vat.csv").map { it["UST_SCHLUESSEL"]!! })
                        addAll(readRows(zip, "businesscases.csv").map { it["UST_SCHLUESSEL"]!! })
                        addAll(readRows(zip, "itemamounts.csv").map { it["UST_SCHLUESSEL"]!! })
                    }.filter { it.isNotBlank() }.toSet()

                assertTrue(used.isNotEmpty(), "fixture sanity: the closing must reference at least one key")

                // Direction 1 — no dangling join. This is the half that must never regress.
                assertTrue(
                    declared.containsAll(used),
                    "every USt key a row references must be declared in vat.csv, or the join dangles. " +
                        "used=$used declared=$declared",
                )

                // Direction 2 — §3.2.6's „verwendeten": no catalogue padding.
                assertTrue(
                    declared == used,
                    "vat.csv must declare the keys the closing USES, not the whole Anlage-2 catalogue " +
                        "(§3.2.6). Undeclared-but-unused surplus: ${declared - used}",
                )

                // The two specific keys the original audit called out, now simply absent.
                assertTrue(
                    "7" !in declared,
                    "key 7 carries a KEINE_UST_ZUORDNUNG documentation obligation when USED; an unused " +
                        "declaration only invites the question",
                )
                assertTrue(
                    "3" !in declared,
                    "key 3 is declared with TODAY's §24 rate — untrue for a historical closing, and " +
                        "unnecessary when the closing never uses it",
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
