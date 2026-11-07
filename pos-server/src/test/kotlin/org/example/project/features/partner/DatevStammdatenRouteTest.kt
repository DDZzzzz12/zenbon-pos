package org.example.project.features.partner

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Debitoren-/Kreditorenstamm endpoint, and the gate in front of it.
 *
 * Two things are unproven anywhere else. The first is the gate: `PartnerRules` requires an
 * [org.example.project.infrastructure.security.AccessLevel.AllOf] of `CREDITOR_READ` **and**
 * `DEBTOR_READ`, because the file carries both kinds of master data in one download — and a declared
 * arm that is not wired reads to everyone as protection the server does not apply. `AccessDecision`
 * unit tests cannot show the wiring; only a real request from a principal holding one half can.
 *
 * The second is the charset. [DatevStammdatenExportTest] proves the *resolver*, and the fiscal-export
 * tests prove the *bytes*, but neither proves the route hands those bytes over as ISO-8859-1 and
 * intact. That is the defect this slice has been guarding against throughout: a file that decodes as
 * UTF-8 without error and reaches the advisor with every umlaut mangled.
 */
@DisplayName("DATEV Stammdaten export route")
class DatevStammdatenRouteTest {
    private val suppliers = SupplierRepositoryImpl()

    /** A creditor whose name only survives the round trip if the charset is right. */
    private val umlautName = "Käsehändler Müller GmbH"

    private fun baseData() {
        TestData.createShopInfo()
        TestData.createAdmin()
        TestData.createManager()
    }

    /**
     * Berater-/Mandantennummer and the account mapping the Sachkontenrahmen is read from.
     *
     * The export refuses rather than defaults when these are missing, so without them every case
     * here would fail on a 400/409 and prove nothing about the gate.
     */
    private fun seedDatevConfig() {
        transaction {
            val shopId =
                ShopInfo
                    .all()
                    .first()
                    .id.value
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shopId
                it[ShopConfigurationTable.key] = ConfigKey.DATEV_XML_CONFIG.key
                it[ShopConfigurationTable.value] =
                    buildJsonObject {
                        put("enabled", true)
                        put("consultantNumber", "12345")
                        put("clientNumber", "67890")
                    }
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

    private fun seedCreditor() {
        transaction {
            suppliers.create(name = umlautName, vatId = "DE123456789").creditorNo = "70001"
        }
    }

    /**
     * A group holding the creditor half of the pair and deliberately not the debtor half.
     *
     * Built as a row rather than taken from [org.example.project.core.auth.CapabilityBundles],
     * because no seeded bundle splits these two — MANAGER and ACCOUNTANT both carry `CREDITOR_READ`
     * and `DEBTOR_READ`, which is precisely how an unwired `AllOf` would look fine forever.
     * `SELF_READ` is in the set only so the principal can log in and read its own profile; without
     * it the login flow, not the gate, is what would fail.
     */
    private fun seedCreditorReadOnlyGroup() {
        transaction {
            val now = currentTimeStamp()
            val groupId =
                PermissionGroupTable.insertAndGetId {
                    it[code] = "CREDITOR_READ_ONLY"
                    it[name] = "Creditor read only"
                    it[description] = "holds CREDITOR_READ and deliberately not DEBTOR_READ"
                    it[isSystem] = false
                    it[isDeletable] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            listOf(
                Capability.SELF_READ,
                Capability.CREDITOR_READ,
            ).forEach { capability ->
                PermissionGroupCapabilityTable.insert {
                    it[group] = groupId
                    it[this.capability] = capability.name
                }
            }
        }
    }

    private fun employeeId(username: String): String =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    @Test
    fun `a caller holding both read capabilities downloads the file`() =
        testApp(
            setupData = {
                baseData()
            },
        ) { client ->
            seedDatevConfig()
            seedCreditor()
            // MANAGER carries CREDITOR_READ and DEBTOR_READ both.
            val token = TestAuthHelper.loginAsManager(client)

            val response = client.get("/api/partners/export/datev-stammdaten") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            // Decoded as ISO-8859-1 on purpose — the charset the builder writes and the header
            // declares. UTF-8 here would either throw or, worse, quietly produce mojibake.
            val body = String(response.readRawBytes(), Charsets.ISO_8859_1)
            assertTrue(
                body.startsWith("\"EXTF\";700;16;\"Debitoren/Kreditoren\";5;"),
                "the file must open with the Debitoren/Kreditoren metadata header, was: ${body.take(60)}",
            )
            assertTrue(
                body.contains(umlautName),
                "an umlaut in a party name must survive the round trip byte for byte",
            )

            val contentType = response.headers[HttpHeaders.ContentType]
            assertEquals(
                ContentType("text", "csv").withCharset(Charsets.ISO_8859_1).toString(),
                contentType,
                "DATEV reads ISO-8859-1; declaring UTF-8 would make every correct byte render wrong",
            )
            // The name is what the operator ends up with on disk, so it is part of the contract.
            assertTrue(
                response.headers[HttpHeaders.ContentDisposition]?.contains("EXTF_DebitorenKreditoren.csv") == true,
                "the download must be named by the export, was: ${response.headers[HttpHeaders.ContentDisposition]}",
            )
            // The report a CSV body cannot carry.
            assertEquals("1", response.headers["X-Export-Account-Count"])
            assertEquals("0", response.headers["X-Export-Skipped-Count"])
            assertEquals("0", response.headers["X-Export-Warning-Count"])
        }

    @Test
    fun `a caller holding only CREDITOR_READ is refused`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "CRD002", username = "creditorreadonly")
                seedCreditorReadOnlyGroup()
            },
        ) { client ->
            seedDatevConfig()
            seedCreditor()

            // Assigned through the API rather than by hand, so the resolver's caches are invalidated
            // exactly as they are in production.
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            assertEquals(
                HttpStatusCode.OK,
                client
                    .put("/api/employees/${employeeId("creditorreadonly")}/permission-group") {
                        bearerAuth(adminToken)
                        setBody(TextContent("""{"groupCode":"CREDITOR_READ_ONLY"}""", ContentType.Application.Json))
                    }.status,
                "precondition: the narrowed group must be assignable",
            )

            val token = TestAuthHelper.loginAs(client, "creditorreadonly")

            // The same principal on the creditor-only sibling route must pass. Without this, a 403
            // below would equally be explained by a broken login, an unassigned group or a stale
            // capability cache — anything but the missing DEBTOR_READ.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/partners/duplicates") { bearerAuth(token) }.status,
                "precondition: CREDITOR_READ alone must still reach a CREDITOR_READ route",
            )

            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/partners/export/datev-stammdaten") { bearerAuth(token) }.status,
                "the file carries debtor master data too, so one half of the pair must not be enough",
            )
        }
}
