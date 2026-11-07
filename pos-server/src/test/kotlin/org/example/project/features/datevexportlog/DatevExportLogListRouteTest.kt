package org.example.project.features.datevexportlog

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.datevexportlog.data.repository.DatevExportLogRepositoryImpl
import org.example.project.features.datevexportlog.database.tables.DatevExportLogTable
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals

/**
 * T9-16: the export log a back-office user reads must list what they exported by hand.
 *
 * The route used the service's `sourceFilter = AUTO` default — meant for the catch-up coverage
 * check — so a MANUAL row was never returned and the back office could never show "Manuell".
 */
@DisplayName("GET /api/v1/datev/export-log returns every source unless narrowed")
class DatevExportLogListRouteTest {
    @TempDir
    lateinit var tempDir: File

    private val repo = DatevExportLogRepositoryImpl()
    private val json = Json { ignoreUnknownKeys = true }
    private val range = "periodStart=2026-01-01T00:00:00&periodEnd=2026-12-31T23:59:59"

    private fun seed() {
        runBlocking {
            dbQuery {
                DatevExportSource.entries.forEach { source ->
                    repo.insert(
                        exportType = DatevExportType.KASSENBUCH,
                        periodStart = LocalDateTime.parse("2026-06-01T00:00:00"),
                        periodEnd = LocalDateTime.parse("2026-06-30T23:59:59"),
                        exportedByUserId = UUID.randomUUID(),
                        source = source,
                        fileHash = "hash-$source",
                        fileName = "kassenbuch-$source.zip",
                        rowCount = 1,
                        entryCount = 1,
                        periodFolder = "2026-06",
                    )
                }
            }
        }
    }

    @Test
    fun `without a source filter both AUTO and MANUAL rows are listed`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createManager()
            seed()
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response = client.get("/api/v1/datev/export-log?$range") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val sources = json.decodeFromString<List<DatevExportLogDto>>(response.bodyAsText()).map { it.source }.toSet()
            assertEquals(setOf("AUTO", "MANUAL"), sources, "a MANUAL export must be visible in the log")
        }

    @Test
    fun `source narrows to one kind and an unknown source is a 400`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createManager()
            seed()
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")

            val manual = client.get("/api/v1/datev/export-log?$range&source=MANUAL") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, manual.status, manual.bodyAsText())
            assertEquals(
                listOf("MANUAL"),
                json.decodeFromString<List<DatevExportLogDto>>(manual.bodyAsText()).map { it.source },
            )

            val bogus = client.get("/api/v1/datev/export-log?$range&source=NIGHTLY") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, bogus.status)
        }

    @Test
    fun `a manual Kassenbuch XML export is logged as MANUAL`() =
        testApp(setupData = {
            val shop = TestData.createShopInfo()
            val admin = TestData.createAdmin()
            seedDatevExportPath(shop.id.value, tempDir.absolutePath)
            transaction {
                KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_DEPOSIT,
                            belegNr = "2026-09-0001",
                            vorzBetrag = "10.00",
                            belegDatum = "25.09.2026",
                            belegtext = "Einlage",
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "10.00", ustSatz = null)),
                )
            }
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.get("/api/v1/kassenbuch/export/datev-xml?startDate=2020-01-01&endDate=2035-12-31") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val sources = transaction { DatevExportLogTable.selectAll().map { it[DatevExportLogTable.exportSource] } }
            assertEquals(listOf("MANUAL"), sources, "an operator-requested export must not be logged as the scheduled one")
        }

    private fun seedDatevExportPath(
        shopId: Long,
        exportPath: String,
    ) {
        transaction {
            val configJson =
                buildJsonObject {
                    put("enabled", true)
                    put("kassenName", "Kasse")
                    put("exportPath", exportPath)
                    put("consultantNumber", "")
                    put("clientNumber", "")
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
}
