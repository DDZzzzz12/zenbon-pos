package org.example.project.features.datevexportlog

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.datevexportlog.data.repository.DatevExportLogRepositoryImpl
import org.example.project.features.datevexportlog.domain.models.DatevExportLog
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("GET /api/v1/datev/export-log/{id}/download")
class DatevExportLogDownloadRouteTest {
    @TempDir
    lateinit var tempDir: File

    private val repo = DatevExportLogRepositoryImpl()

    /** Seeds the DATEV_XML_CONFIG so getDatevXmlExportPath() returns [exportPath]. */
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
        // Force ShopConfigurationManager to reload from DB so the new exportPath is picked up
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
    }

    /** Seeds a DatevExportLog row and returns the inserted domain model. */
    private suspend fun seedLog(
        periodFolder: String,
        fileName: String,
        userId: UUID = UUID.randomUUID(),
    ): DatevExportLog =
        dbQuery {
            repo.insert(
                exportType = DatevExportType.KASSENBUCH,
                periodStart = LocalDateTime.parse("2026-06-01T00:00:00"),
                periodEnd = LocalDateTime.parse("2026-07-01T00:00:00"),
                exportedByUserId = userId,
                source = DatevExportSource.AUTO,
                fileHash = "deadbeef",
                fileName = fileName,
                rowCount = 1,
                entryCount = 1,
                periodFolder = periodFolder,
            )
        }

    // -------------------------------------------------------------------------
    // Test 1 — happy path: file exists on disk → 200 + bytes + Content-Disposition
    // -------------------------------------------------------------------------

    @Test
    fun `happy path returns 200 with file bytes and attachment header`() {
        val periodFolder = "2026-06"
        val fileName = "kassenbuch-2026-06.zip"
        val fileContent = "FAKE_ZIP_CONTENT".toByteArray()

        var logId = 0L

        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createManager()
                seedDatevExportPath(shop.id.value, tempDir.absolutePath)

                // Write the file to disk at <tempDir>/<periodFolder>/<fileName>
                val dir = File(tempDir, periodFolder)
                dir.mkdirs()
                File(dir, fileName).writeBytes(fileContent)

                logId =
                    runBlocking {
                        seedLog(periodFolder = periodFolder, fileName = fileName).id
                    }
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.get("/api/v1/datev/export-log/$logId/download") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val contentDisposition = response.headers["Content-Disposition"]
            assertTrue(
                contentDisposition?.contains("attachment") == true,
                "Expected Content-Disposition: attachment, got: $contentDisposition",
            )
            assertTrue(
                contentDisposition?.contains(fileName) == true,
                "Expected filename=$fileName in Content-Disposition, got: $contentDisposition",
            )

            assertContentEquals(fileContent, response.bodyAsBytes())
        }
    }

    // -------------------------------------------------------------------------
    // Test 2 — log row exists but file deleted from disk → 404
    // -------------------------------------------------------------------------

    @Test
    fun `log row exists but file missing on disk returns 404`() {
        val periodFolder = "2026-06"
        val fileName = "missing-file.zip"

        var logId = 0L

        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createManager()
                seedDatevExportPath(shop.id.value, tempDir.absolutePath)

                // Do NOT create the file on disk
                logId =
                    runBlocking {
                        seedLog(periodFolder = periodFolder, fileName = fileName).id
                    }
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.get("/api/v1/datev/export-log/$logId/download") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // -------------------------------------------------------------------------
    // Test 3 — unknown log id → 404
    // -------------------------------------------------------------------------

    @Test
    fun `unknown log id returns 404`() {
        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                TestData.createManager()
                seedDatevExportPath(shop.id.value, tempDir.absolutePath)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.get("/api/v1/datev/export-log/9999/download") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // -------------------------------------------------------------------------
    // Test 4 — non-manager token → 403
    // -------------------------------------------------------------------------

    @Test
    fun `cashier token returns 403`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val response =
                client.get("/api/v1/datev/export-log/1/download") {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }
}
