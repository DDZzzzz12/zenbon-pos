package org.example.project.features.datevexportlog

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F64: a date query parameter in the wrong format (`06.05.2026`) reached `LocalDate.parse` in the
 * service and came back as a 500. Every DATEV/Kassenbuch period route now answers 400
 * VALIDATION_FAILED naming `yyyy-MM-dd`; and a missing DATEV export path is a 400, not the 503 the
 * route KDoc used to promise.
 */
@DisplayName("DATEV / Kassenbuch period routes reject malformed dates with 400")
class DatevExportDateParamValidationTest {
    private val malformed =
        listOf(
            "/api/v1/datev/export/ad-hoc?type=BUCHUNGSSTAPEL&startDate=06.05.2026&endDate=2026-05-31",
            "/api/v1/datev/export/ad-hoc?type=KASSENBUCH&startDate=2026-05-01&endDate=31.05.2026",
            "/api/v1/datev/export/ad-hoc?type=AP_LEDGER&startDate=2026-02-30&endDate=2026-03-31",
            "/api/v1/kassenbuch/entries?startDate=06.05.2026&endDate=2026-05-31",
            "/api/v1/kassenbuch/export/datev-xml?startDate=06.05.2026&endDate=2026-05-31",
            "/api/v1/kassenbuch/export/buchungsstapel?startDate=2026-05-01&endDate=31.05.2026",
            "/api/v1/kassenbuch/export/datev-xml/statistics?startDate=06.05.2026&endDate=2026-05-31",
            "/api/documents/export/datev-ap?startDate=06.05.2026&endDate=2026-05-31",
        )

    private fun seedDatevConfig(
        shopId: Long,
        exportPath: String,
    ) {
        transaction {
            val configJson =
                buildJsonObject {
                    put("enabled", true)
                    put("kassenName", "Kasse")
                    put("exportPath", exportPath)
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
        }
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
    }

    private fun seedKassenbuchEntry() {
        transaction {
            val shop = ShopInfo.all().first()
            val header =
                KassenbuchHeader.new {
                    this.shop = shop
                    this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                    this.employee = Employee.all().first()
                    this.entryType = KassenbuchEntryType.CASH_SALE
                    this.belegNr = "2026-04-0001"
                    this.belegDatum = "15.04.2026"
                    this.belegtext = "Test entry"
                    this.waehrung = "EUR"
                    this.bu = null
                    this.gegenkonto = null
                    this.kost1 = null
                    this.kost2 = null
                    this.nachricht = null
                    this.linkedDocumentId = null
                    this.totalAmount = BigDecimal("10.0000")
                    this.currentBalance = BigDecimal("10.0000")
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                    this.isLocked = false
                    this.createdAt = LocalDateTime(2026, 4, 15, 12, 0)
                }
            KassenbuchLineItem.new {
                this.header = header
                this.lineNumber = 0
                this.vorzBetrag = BigDecimal("10.00")
                this.ustSatz = "19"
                this.belegtext = null
                this.kostmenge = null
                this.skonto = null
                this.subBalance = BigDecimal("10.0000")
            }
        }
    }

    private suspend fun HttpClient.getAs(
        token: String,
        url: String,
    ): HttpResponse = get(url) { bearerAuth(token) }

    @Test
    fun `a malformed date is a 400 VALIDATION_FAILED naming yyyy-MM-dd on every period route`() =
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createAdmin()
                seedDatevConfig(shop.id.value, exportPath = "")
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            malformed.forEach { url ->
                val response = client.getAs(token, url)
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.BadRequest, response.status, "$url -> $body")
                assertTrue(body.contains("VALIDATION_FAILED"), "$url names the code: $body")
                assertTrue(body.contains("yyyy-MM-dd"), "$url names the expected format: $body")
            }
        }

    /** Negative control: the same routes with ISO dates are not refused by the date check. */
    @Test
    fun `well-formed dates pass the date check`() =
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createAdmin()
                seedDatevConfig(shop.id.value, exportPath = "")
                seedKassenbuchEntry()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            listOf(
                "/api/v1/kassenbuch/entries?startDate=2026-04-01&endDate=2026-04-30",
                "/api/v1/kassenbuch/export/datev-xml/statistics?startDate=2026-04-01&endDate=2026-04-30",
                "/api/v1/datev/export/ad-hoc?type=KASSENBUCH&startDate=2026-04-01&endDate=2026-04-30",
            ).forEach { url ->
                val response = client.getAs(token, url)
                assertEquals(HttpStatusCode.OK, response.status, "$url -> ${response.bodyAsText()}")
            }
        }

    @Test
    fun `a missing date is still a 400`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.getAs(token, "/api/v1/datev/export/ad-hoc?type=KASSENBUCH&endDate=2026-04-30")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("startDate"))
        }

    /** The route KDoc promised 503 here; the code answers 400 because a bad path is bad configuration. */
    @Test
    fun `an unconfigured DATEV export path is a 400, not a 503`() =
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createAdmin()
                seedDatevConfig(shop.id.value, exportPath = "")
                seedKassenbuchEntry()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.getAs(token, "/api/v1/kassenbuch/export/datev-xml?startDate=2026-04-01&endDate=2026-04-30")
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue(body.contains("VALIDATION_FAILED"), body)
            assertTrue(body.contains("export path not configured"), body)
        }
}
