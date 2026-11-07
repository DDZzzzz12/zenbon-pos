package org.example.project.features.datevexportlog

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.network.ExportWarningHeaders
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
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
 * Contract tests for GET /api/v1/datev/export/ad-hoc: the client derives the
 * saved file's name and MIME type from Content-Disposition/Content-Type, so
 * BUCHUNGSSTAPEL must announce a .csv/text-csv payload and the XML-based types
 * a .zip/application-zip payload.
 */
@DisplayName("GET /api/v1/datev/export/ad-hoc")
class DatevAdHocExportRouteTest {
    /**
     * Seeds DATEV_XML_CONFIG with the consultant/client numbers the export needs, plus the
     * DATEV_ACCOUNT_MAPPING the Buchungsstapel export refuses to run without.
     *
     * The mapping is taken from [DatevAccountMappingDefaults.seed] rather than hand-written here:
     * [DatevBuchungsstapelMappingGate] only requires *a* configured mapping, and deriving it from
     * the same seed the settings UI pre-fills means new mapping slots cannot rot this fixture into
     * a 409 again. Without it the export answers 409 DATEV_ACCOUNT_MAPPING_REQUIRED — correctly:
     * an unconfigured shop would otherwise get a file that imports cleanly and books the wrong
     * accounts. The KASSENBUCH test needs no mapping (the gate is deliberately not applied to the
     * XML export, which carries the operator's own Gegenkonto through unchanged).
     */
    private fun seedDatevConfig(
        shopId: Long,
        mapping: DatevAccountMappingDto.MappingDto = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, nonCashPaymentTypes = emptyList()),
    ) {
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

    /** Seeds one exportable Kassenbuch entry (header + line item) inside the test period. */
    private fun seedKassenbuchEntry() {
        transaction {
            val shop = ShopInfo.all().first()
            val employee = Employee.all().first()
            val header =
                KassenbuchHeader.new {
                    this.shop = shop
                    this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                    this.employee = employee
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
                    this.totalAmount = BigDecimal("10.0000")
                    this.currentBalance = BigDecimal("10.0000")
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                    this.linkedDocumentId = null
                    this.isLocked = false
                    // Fixed, inside the 2026-04-01..2026-04-30 window both tests query, and
                    // matching belegDatum. `currentTimeStamp()` made this a time bomb:
                    // getEntriesByDateRange filters on createdAt.date, so the seeded row fell
                    // outside the window from 2026-05-01 onward and both tests 404'd on the
                    // calendar rather than on anything the export did.
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

    @Test
    fun `BUCHUNGSSTAPEL responds with text-csv and a csv filename`() =
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createAdmin()
                seedDatevConfig(shop.id.value)
                seedKassenbuchEntry()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.get(
                    "/api/v1/datev/export/ad-hoc?type=BUCHUNGSSTAPEL&startDate=2026-04-01&endDate=2026-04-30",
                ) { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType("text", "csv"), response.contentType()?.withoutParameters())

            val contentDisposition = response.headers["Content-Disposition"]
            assertTrue(
                contentDisposition?.contains("datev_buchungsstapel_2026-04-01_2026-04-30.csv") == true,
                "Expected .csv filename in Content-Disposition, got: $contentDisposition",
            )
        }

    @Test
    fun `KASSENBUCH responds with application-zip and a zip filename`() =
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createAdmin()
                seedDatevConfig(shop.id.value)
                seedKassenbuchEntry()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.get(
                    "/api/v1/datev/export/ad-hoc?type=KASSENBUCH&startDate=2026-04-01&endDate=2026-04-30",
                ) { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Zip, response.contentType()?.withoutParameters())

            val contentDisposition = response.headers["Content-Disposition"]
            assertTrue(
                contentDisposition?.contains("datev_xml_kassenbuch_2026-04-01_2026-04-30.zip") == true,
                "Expected .zip filename in Content-Disposition, got: $contentDisposition",
            )
        }

    /**
     * A line whose mapping cell has „Konto automatisch zuordnen" switched off is exported with an
     * empty Gegenkonto, and the download says so in a structured header the client localizes.
     */
    @Test
    fun `BUCHUNGSSTAPEL with a switched-off cell writes an empty Gegenkonto and reports the line`() =
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createAdmin()
                val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, nonCashPaymentTypes = emptyList())
                val standard = seed.income.getValue(KassenbuchLineKind.STANDARD) + ("19" to AccountCell(konto = 8400, autoAssign = false))
                seedDatevConfig(shop.id.value, seed.copy(income = seed.income + (KassenbuchLineKind.STANDARD to standard)))
                seedKassenbuchEntry()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.get(
                    "/api/v1/datev/export/ad-hoc?type=BUCHUNGSSTAPEL&startDate=2026-04-01&endDate=2026-04-30",
                ) { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val summary = ExportWarningHeaders.decodeUnassigned(response.headers[ExportWarningHeaders.UNASSIGNED])
            assertEquals(1, summary?.lineCount, "header: ${response.headers[ExportWarningHeaders.UNASSIGNED]}")
            assertEquals(
                CellRef(side = DatevMappingSide.INCOME, kind = KassenbuchLineKind.STANDARD, rate = "19"),
                summary?.bookings?.single()?.cell,
            )
            val row = response.bodyAsText().split("\r\n")[2].split(";")
            assertEquals("1000", row[6])
            assertEquals("", row[7], "Gegenkonto must be empty, not 8400 and not 0")
        }
}
