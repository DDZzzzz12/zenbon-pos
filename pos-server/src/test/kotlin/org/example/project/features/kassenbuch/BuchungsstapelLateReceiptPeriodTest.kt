package org.example.project.features.kassenbuch

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.network.ExportWarningHeaders
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelBelegeExportService
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelExportService
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A receipt handed in after a close keeps its own Beleg date and books into the open period, so a
 * Buchungsstapel selected by booking time can carry a row dated before the period that was asked
 * for. The file must still describe itself truthfully, and the operator must be told.
 *
 * E2E-2026-09-22 finding R3-1: the header stated `Datum vom 20260922 bis 20260922` over a row dated
 * `21092026`, and nothing said so.
 */
class BuchungsstapelLateReceiptPeriodTest {
    private fun seedDatevConfig(shopId: Long) {
        transaction {
            val configJson =
                buildJsonObject {
                    put("enabled", true)
                    put("kassenName", "Kasse")
                    put("exportPath", "")
                    put("consultantNumber", "12345")
                    put("clientNumber", "67890")
                    put("datevExportCadence", "MONTHLY")
                }
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shopId
                it[ShopConfigurationTable.key] = ConfigKey.DATEV_XML_CONFIG.key
                it[ShopConfigurationTable.value] = configJson
                it[ShopConfigurationTable.description] = "test"
                it[ShopConfigurationTable.updatedAt] = null
            }
            val mapping = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, nonCashPaymentTypes = emptyList())
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shopId
                it[ShopConfigurationTable.key] = ConfigKey.DATEV_ACCOUNT_MAPPING.key
                it[ShopConfigurationTable.value] = Json.encodeToJsonElement(mapping).jsonObject
                it[ShopConfigurationTable.description] = "test"
                it[ShopConfigurationTable.updatedAt] = null
            }
        }
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
    }

    /**
     * One entry booked inside the exported period (15.04.) carrying the Beleg date of a receipt
     * from the month before (15.03.) — the late receipt this finding is about.
     */
    private fun seedLateReceipt() {
        transaction {
            val shop = ShopInfo.all().first()
            val employee = Employee.all().first()
            val header =
                KassenbuchHeader.new {
                    this.shop = shop
                    this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                    this.employee = employee
                    this.entryType = KassenbuchEntryType.CASH_EXPENSE
                    this.belegNr = "2026-04-0001"
                    this.belegDatum = "15.03.2026"
                    this.belegtext = "Lieferantenrechnung, spät eingereicht"
                    this.waehrung = "EUR"
                    this.bu = null
                    this.gegenkonto = null
                    this.kost1 = null
                    this.kost2 = null
                    this.nachricht = null
                    this.totalAmount = BigDecimal("-10.0000")
                    this.currentBalance = BigDecimal("-10.0000")
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                    this.linkedDocumentId = null
                    this.isLocked = false
                    this.createdAt = LocalDateTime(2026, 4, 15, 12, 0)
                }
            KassenbuchLineItem.new {
                this.header = header
                this.lineNumber = 0
                this.vorzBetrag = BigDecimal("-10.00")
                this.ustSatz = "19"
                this.belegtext = null
                this.kostmenge = null
                this.skonto = null
                this.subBalance = BigDecimal("-10.0000")
            }
        }
    }

    private fun setUp() {
        val shop = TestData.createShopInfo()
        TestData.createAdmin()
        seedDatevConfig(shop.id.value)
        seedLateReceipt()
    }

    @Test
    fun `the header period covers a row whose Beleg date precedes the exported period`() =
        testApp(setupData = { setUp() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val csv =
                client
                    .get("/api/v1/datev/export/ad-hoc?type=BUCHUNGSSTAPEL&startDate=2026-04-01&endDate=2026-04-30") {
                        bearerAuth(token)
                    }.also { assertEquals(HttpStatusCode.OK, it.status) }
                    .bodyAsText()

            val headerCells = csv.lineSequence().first().split(";")
            assertEquals("20260315", headerCells[14], "Datum vom must reach back to the earliest Beleg date")
            assertEquals("20260430", headerCells[15], "Datum bis keeps the requested end of the period")
            assertTrue(csv.contains("15032026"), "the row itself still carries the receipt's own date")
        }

    @Test
    fun `the export reports the late receipt as a warning`() =
        testApp(setupData = { setUp() }) { client ->
            TestAuthHelper.loginAsAdmin(client)

            val service = GlobalContext.get().get<DatevBuchungsstapelExportService>()
            val result = service.exportTransient("2026-04-01", "2026-04-30", UUID.randomUUID())

            assertTrue(
                result.warnings.any { it.contains("outside the exported period") && it.contains("15.03.2026") },
                "the operator must be told which date left the period; got ${result.warnings}",
            )
        }

    /**
     * The Buchungsstapel-mit-Belegen export writes the same CSV through the same builder, so it
     * inherits the widened header — but it computes its own warnings, and a sibling export that
     * stays silent about the same receipt is the half-fix this guards against.
     */
    @Test
    fun `the export with attached receipts reports the late receipt too`() =
        testApp(setupData = { setUp() }) { client ->
            TestAuthHelper.loginAsAdmin(client)

            val service = GlobalContext.get().get<DatevBuchungsstapelBelegeExportService>()
            val result = service.exportTransient("2026-04-01", "2026-04-30", UUID.randomUUID())

            assertTrue(
                result.warnings.any { it.contains("outside the exported period") && it.contains("15.03.2026") },
                "the Belege variant must warn like its sibling; got ${result.warnings}",
            )
        }

    /**
     * E2E round 4, R4-4: both services above warn, and the ad-hoc route — the only one adminApp's
     * Datenexport uses, and the only one for *mit Belegen* — wrote the file and dropped the warnings.
     * The operator's path is the route, so the route is what is checked here.
     */
    @Test
    fun `the ad-hoc download carries the warning, for both variants`() =
        testApp(setupData = { setUp() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            for (type in listOf("BUCHUNGSSTAPEL", "BUCHUNGSSTAPEL_MIT_BELEGEN")) {
                val response =
                    client.get("/api/v1/datev/export/ad-hoc?type=$type&startDate=2026-04-01&endDate=2026-04-30") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status, type)

                val warnings = ExportWarningHeaders.decode(response.headers[ExportWarningHeaders.TEXT])
                assertEquals(warnings.size.toString(), response.headers[ExportWarningHeaders.COUNT], type)
                assertTrue(
                    warnings.any { it.contains("outside the exported period") && it.contains("15.03.2026") },
                    "$type: the download must carry the late-receipt warning; got $warnings",
                )
            }
        }
}
