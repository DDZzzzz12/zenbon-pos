package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.encodeToString
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DisplayName("Configuration Routes")
class ConfigurationRoutesTest {
    @Nested
    @DisplayName("GET /api/v1/config/{shopId}/vat-mapping")
    inner class GetVatMapping {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/invalid/vat-mapping") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid shop ID`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/1/vat-mapping") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/config/1/vat-mapping")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/config/{shopId}/vat-mapping")
    inner class UpdateVatMapping {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/invalid/vat-mapping") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/config/1/vat-mapping") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/config/{shopId}/voucher-config")
    inner class GetVoucherConfig {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/invalid/voucher-config") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid shop ID`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/1/voucher-config") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/config/1/voucher-config")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/config/{shopId}/voucher-config")
    inner class UpdateVoucherConfig {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/invalid/voucher-config") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        /**
         * The server half of the guard behind adminApp's disabled delete on the INTERNAL source: an
         * enabled voucher system without an INTERNAL source is refused with 400 (it used to surface as
         * a 500 because `validate()`'s `require` fell into the route's catch-all), and nothing is saved.
         */
        @Test
        fun `removing the INTERNAL source while vouchers are enabled is refused with 400`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/1/voucher-config") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                """{"voucherConfig":{"enabled":true,"sources":[{"type":"LEGACY","name":"Altgutscheine","enabled":true}]}}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/config/1/voucher-config") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/config/{shopId}/print-settings")
    inner class GetPrintSettings {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/invalid/print-settings") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid shop ID`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/1/print-settings") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/config/1/print-settings")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/config/{shopId}/print-settings")
    inner class UpdatePrintSettings {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/invalid/print-settings") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        /** admin20 FAIL-C20: the server half of the guard adminApp's disabled master switch mirrors. */
        @Test
        fun `a cashier without CONFIG_WRITE is refused with 403`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response =
                    client.put("/api/v1/config/1/print-settings") {
                        bearerAuth(token)
                        setBody(TextContent("""{"enabled":false}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/config/1/print-settings") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/config/{shopId}/datev-xml")
    inner class GetDatevXml {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/invalid/datev-xml") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/config/1/datev-xml")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/config/{shopId}/datev-xml")
    inner class UpdateDatevXml {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/invalid/datev-xml") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/config/1/datev-xml") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        /**
         * An export path the server cannot use is bad input, not a server fault. This used to come
         * back as a bare `500 {"error","message"}` from a catch-all — and worse, the configuration
         * had already been written by then, because the directory check ran after the save with its
         * Boolean result discarded. A restored production archive reproduces it every time: its
         * Linux export path does not exist on the machine that restored it.
         */
        @Test
        fun `returns 400 problem+json for an unusable export path and does not store the config`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // A regular file as the parent makes the directory un-creatable on every OS.
                val blocker = File.createTempFile("datev-export-blocker", ".tmp").apply { deleteOnExit() }
                val unusablePath = File(blocker, "exports").absolutePath

                val response =
                    client.put("/api/v1/config/1/datev-xml") {
                        bearerAuth(token)
                        setBody(
                            TextContent(
                                testJson.encodeToString(
                                    ShopConfigurationDto.UpdateDatevXmlConfigRequestDto(
                                        kassenName = "Kasse Regression",
                                        exportPath = unusablePath,
                                    ),
                                ),
                                ContentType.Application.Json,
                            ),
                        )
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals(
                    ContentType.Application.ProblemJson.contentType,
                    response.contentType()?.contentType,
                )
                assertContains(response.bodyAsText(), unusablePath)

                // The rejected path must not have been persisted on the way to the error.
                val stored = client.get("/api/v1/config/1/datev-xml") { bearerAuth(token) }.bodyAsText()
                assertFalse(stored.contains("Kasse Regression"))
            }

        private fun datevBody(exportPath: String) =
            TextContent(
                testJson.encodeToString(
                    ShopConfigurationDto.UpdateDatevXmlConfigRequestDto(kassenName = "Kasse SET-05", exportPath = exportPath),
                ),
                ContentType.Application.Json,
            )

        /** SET-05b: a relative export path used to be created under the server's working directory. */
        @Test
        fun `returns 400 for a relative export path and creates nothing`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val relative = "set05-relative-${System.nanoTime()}/exports"

                val response =
                    client.put("/api/v1/config/1/datev-xml") {
                        bearerAuth(token)
                        setBody(datevBody(relative))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertContains(response.bodyAsText(), "not an absolute path")
                assertFalse(File(relative).exists(), "a refused relative path must not be created")
            }

        @Test
        fun `returns 400 for an export path that names a file`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val file = File.createTempFile("datev-export-file", ".tmp").apply { deleteOnExit() }

                val response =
                    client.put("/api/v1/config/1/datev-xml") {
                        bearerAuth(token)
                        setBody(datevBody(file.absolutePath))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertContains(response.bodyAsText(), "not a directory")
            }

        /**
         * SET-05a role matrix. Saving the DATEV configuration is `CONFIG_WRITE`, which the MANAGER
         * bundle holds by design (ConfigRules, route KDoc, adminApp `showsDatevConfiguration`); the
         * backup configuration is `BACKUP_CREATE`, ADMIN-only. A cashier holds neither.
         */
        @Test
        fun `a manager may save it with an absolute path, a cashier may not`() =
            testApp(
                setupData = {
                    TestData.createManager()
                    TestData.createCashier()
                    TestData.createShopInfo()
                },
            ) { client ->
                val dir =
                    kotlin.io.path
                        .createTempDirectory("datev-set05")
                        .toFile()
                        .apply { deleteOnExit() }

                val manager =
                    client.put("/api/v1/config/1/datev-xml") {
                        bearerAuth(TestAuthHelper.loginAsManager(client))
                        setBody(datevBody(dir.absolutePath))
                    }
                val cashier =
                    client.put("/api/v1/config/1/datev-xml") {
                        bearerAuth(TestAuthHelper.loginAsCashier(client))
                        setBody(datevBody(dir.absolutePath))
                    }

                assertEquals(HttpStatusCode.OK, manager.status, manager.bodyAsText())
                assertEquals(HttpStatusCode.Forbidden, cashier.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/config/{shopId}/order-rules")
    inner class GetOrderRules {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/invalid/order-rules") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid shop ID`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/1/order-rules") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/config/1/order-rules")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/config/{shopId}/order-rules")
    inner class UpdateOrderRules {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/invalid/order-rules") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/config/1/order-rules") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/config/{shopId}/break-rules")
    inner class GetBreakRules {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/invalid/break-rules") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid shop ID`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/1/break-rules") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/config/1/break-rules")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/config/{shopId}/break-rules")
    inner class UpdateBreakRules {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/invalid/break-rules") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/config/1/break-rules") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/config/{shopId}/document-storage")
    inner class GetDocumentStorage {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/config/invalid/document-storage") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/config/1/document-storage")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/config/{shopId}/document-storage")
    inner class UpdateDocumentStorage {
        @Test
        fun `returns 400 for invalid shop ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/config/invalid/document-storage") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/config/1/document-storage") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET/PUT /api/v1/config/{shopId}/datev-account-mapping")
    inner class DatevAccountMapping {
        private val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())

        @Test
        fun `round-trips the mapping and reports null when unconfigured`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val before = client.get("/api/v1/config/1/datev-account-mapping") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, before.status)
                assertNull(
                    testJson.decodeFromString<DatevAccountMappingDto.MappingResponseDto>(before.bodyAsText()).mapping,
                )

                val put =
                    client.put("/api/v1/config/1/datev-account-mapping") {
                        bearerAuth(token)
                        setBody(TextContent(testJson.encodeToString(seed), ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, put.status, "body: ${put.bodyAsText()}")

                val after = client.get("/api/v1/config/1/datev-account-mapping") { bearerAuth(token) }
                val mapping = testJson.decodeFromString<DatevAccountMappingDto.MappingResponseDto>(after.bodyAsText()).mapping
                assertNotNull(mapping)
                assertEquals(1000, mapping.kasseKonto)
            }

        @Test
        fun `rejects invalid mapping and denies cashier writes`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createCashier()
                    TestData.createShopInfo()
                },
            ) { client ->
                val admin = TestAuthHelper.loginAsAdmin(client)
                val invalid =
                    client.put("/api/v1/config/1/datev-account-mapping") {
                        bearerAuth(admin)
                        setBody(TextContent(testJson.encodeToString(seed.copy(kasseKonto = 0)), ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)

                val cashier = TestAuthHelper.loginAsCashier(client)
                val denied =
                    client.put("/api/v1/config/1/datev-account-mapping") {
                        bearerAuth(cashier)
                        setBody(TextContent(testJson.encodeToString(seed), ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, denied.status)
            }

        /**
         * QA finding: a refused mapping used to come back as a bare `{"error": …}` carrying only the
         * FIRST validation error, so an operator with three bad cells had to save three times. It is
         * now the standard problem+json with every failure as a field error.
         */
        @Test
        fun `refuses an invalid mapping with problem+json naming every failing field`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val twoErrors = seed.copy(kasseKonto = 0, special = seed.special - KassenbuchSpecialBooking.CASH_SURPLUS)

                val response =
                    client.put("/api/v1/config/1/datev-account-mapping") {
                        bearerAuth(token)
                        setBody(TextContent(testJson.encodeToString(twoErrors), ContentType.Application.Json))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals(ContentType.Application.ProblemJson.contentType, response.contentType()?.contentType)
                val problem = testJson.decodeFromString<ProblemDetail>(response.bodyAsText())
                assertEquals(PosErrorCode.VALIDATION_FAILED.name, problem.code)
                val fields = problem.errors.orEmpty().map { it.field }
                assertEquals(listOf("kasseKonto", "special.CASH_SURPLUS"), fields)
                assertEquals("no account configured", problem.errors!!.last().code)

                // The refused mapping was not stored on the way to the refusal. (Whether some other
                // mapping is present depends on the process-wide config cache, so only the refused
                // value is checked.)
                val stored = client.get("/api/v1/config/1/datev-account-mapping") { bearerAuth(token) }.bodyAsText()
                val mapping = testJson.decodeFromString<DatevAccountMappingDto.MappingResponseDto>(stored).mapping
                assertNotEquals(0, mapping?.kasseKonto)
            }

        /** A body of the wrong shape (here wrapped in `{"mapping": …}`) is a 400 problem+json, never a bare 500. */
        @Test
        fun `answers a malformed body with a 400 problem+json`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val wrapped = """{"mapping":${testJson.encodeToString(seed)}}"""

                val response =
                    client.put("/api/v1/config/1/datev-account-mapping") {
                        bearerAuth(token)
                        setBody(TextContent(wrapped, ContentType.Application.Json))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
                assertEquals(ContentType.Application.ProblemJson.contentType, response.contentType()?.contentType)
                val problem = testJson.decodeFromString<ProblemDetail>(response.bodyAsText())
                assertEquals(PosErrorCode.INVALID_REQUEST.name, problem.code)

                val badShop =
                    client.put("/api/v1/config/abc/datev-account-mapping") {
                        bearerAuth(token)
                        setBody(TextContent(testJson.encodeToString(seed), ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, badShop.status)
                assertEquals(ContentType.Application.ProblemJson.contentType, badShop.contentType()?.contentType)
            }

        /**
         * „Konto automatisch zuordnen": a switched-off cell may be saved empty, a cell with the switch
         * ON may not — the server half of the rule the settings screen enforces (rule 8).
         */
        @Test
        fun `saves an empty switched-off cell and refuses an empty ON cell`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val slot = KassenbuchSpecialBooking.CASH_SURPLUS
                val off = seed.copy(special = seed.special + (slot to AccountCell(konto = null, autoAssign = false)))
                val saved =
                    client.put("/api/v1/config/1/datev-account-mapping") {
                        bearerAuth(token)
                        setBody(TextContent(testJson.encodeToString(off), ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, saved.status, "body: ${saved.bodyAsText()}")
                val read =
                    client.get("/api/v1/config/1/datev-account-mapping") { bearerAuth(token) }.bodyAsText().let {
                        testJson.decodeFromString<DatevAccountMappingDto.MappingResponseDto>(it).mapping
                    }
                assertEquals(AccountCell(konto = null, autoAssign = false), read?.special?.get(slot))
                assertEquals(true, read?.special?.get(KassenbuchSpecialBooking.GELDTRANSIT)?.autoAssign)

                val emptyOn = seed.copy(special = seed.special + (slot to AccountCell(konto = null)))
                val refused =
                    client.put("/api/v1/config/1/datev-account-mapping") {
                        bearerAuth(token)
                        setBody(TextContent(testJson.encodeToString(emptyOn), ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, refused.status)
                assertContains(refused.bodyAsText(), "special.CASH_SURPLUS")
            }
    }
}
