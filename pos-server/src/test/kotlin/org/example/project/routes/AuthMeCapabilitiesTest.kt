package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import org.example.project.auth.data.LoginResponse
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/auth/me` reports the capabilities the server will actually enforce.
 *
 * This is the seam the client access-policy track (C1–C3) is built on. Until it exists, composeApp
 * and adminApp gate on `role` — an ordinal `minRole` comparison — while the server gates on the
 * employee's permission GROUP. Since Stage 3 those legitimately disagree: an accountant carries role
 * `MANAGER` and group `ACCOUNTANT`, so a role-gated client offers them a manager's screens and the
 * server answers 403. Serving the real set is what lets the client stop guessing.
 *
 * Deliberately built before the client's `RoleBackedAccessPolicy` shim that the C1 plan sketches. A
 * role-backed policy would reproduce exactly the disagreement above, so it would be wrong for the
 * three Stage 3 groups on the day it was written; with this endpoint in place the client can be
 * wired to the real answer directly and the shim skipped.
 */
@DisplayName("GET /api/auth/me capabilities")
class AuthMeCapabilitiesTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    private fun parse(body: String): EmployeeInfoDto.EmployeeInfo = json.decodeFromString(body)

    @Test
    fun `reports the capabilities of the caller's group, not of their role`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "ACC900", username = "bookkeeper")
            },
        ) { client ->
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            val target = idOf("bookkeeper")

            client.put("/api/employees/$target/permission-group") {
                bearerAuth(adminToken)
                setBody(TextContent("""{"groupCode":"ACCOUNTANT"}""", ContentType.Application.Json))
            }

            // They are still role MANAGER — the assignment endpoint does not touch the role — so a
            // role-derived answer would return the manager bundle here. This is the assertion the
            // whole endpoint exists for.
            val token = TestAuthHelper.loginAs(client, "bookkeeper")
            val me = parse(client.get("/api/auth/me") { bearerAuth(token) }.bodyAsText())

            assertEquals(EmployeeRole.MANAGER.name, me.role)
            assertEquals(
                CapabilityBundles.forGroup("ACCOUNTANT")?.map { it.name }?.toSet(),
                me.capabilities,
                "capabilities must come from the permission group, not from the role",
            )
            assertFalse(
                Capability.CATALOG_MANAGE.name in me.capabilities,
                "an accountant does not manage the catalog, whatever their role says",
            )
        }

    // R6-4: adminApp's sidebar footer named an Auditor by their base role, "Kassierer", because
    // nothing a low-privilege caller could read reported the group. /me and login now carry it.
    @Test
    fun `me and login report the caller's permission group, not only their role`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier(employeeNumber = "AUD900", username = "auditor")
            },
        ) { client ->
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            client.put("/api/employees/${idOf("auditor")}/permission-group") {
                bearerAuth(adminToken)
                setBody(TextContent("""{"groupCode":"AUDITOR"}""", ContentType.Application.Json))
            }

            val token = TestAuthHelper.loginAs(client, "auditor")
            val me = parse(client.get("/api/auth/me") { bearerAuth(token) }.bodyAsText())
            assertEquals(EmployeeRole.CASHIER.name, me.role)
            assertEquals("AUDITOR", me.permissionGroupCode)
            assertEquals("Auditor", me.permissionGroupName)

            val loginBody =
                client
                    .post("/api/auth/login") {
                        setBody(
                            TextContent(
                                """{"identifier":"auditor","secret":"${TestData.DEFAULT_PASSWORD}"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }.bodyAsText()
            val login = json.decodeFromString<LoginResponse>(loginBody)
            assertEquals("AUDITOR", login.employee.permissionGroupCode)
            assertEquals("Auditor", login.employee.permissionGroupName)
        }

    @Test
    fun `a manager gets the manager group`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val me = parse(client.get("/api/auth/me") { bearerAuth(token) }.bodyAsText())

            assertEquals(
                CapabilityBundles.forRole(EmployeeRole.MANAGER).map { it.name }.toSet(),
                me.capabilities,
            )
        }

    @Test
    fun `every reported name maps to a real capability`() =
        testApp(setupData = { TestData.createCashier() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val me = parse(client.get("/api/auth/me") { bearerAuth(token) }.bodyAsText())

            val known = Capability.entries.map { it.name }.toSet()
            assertEquals(emptySet(), me.capabilities - known, "unrecognised capability name on the wire")
            assertTrue(me.capabilities.isNotEmpty())
        }

    @Test
    fun `the login response carries them too, so there is no blind window`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val body =
                client
                    .post("/api/auth/login") {
                        setBody(
                            TextContent(
                                """{"identifier":"manager","secret":"${TestData.DEFAULT_PASSWORD}"}""",
                                ContentType.Application.Json,
                            ),
                        )
                    }.bodyAsText()

            // Asserted on the raw body rather than the typed LoginResponse: what matters is that the
            // names are on the wire at login, not only from a follow-up /me call.
            assertTrue(
                body.contains(Capability.ORDER_WRITE.name),
                "login response should already carry the capability set",
            )
        }

    @Test
    fun `the endpoint still requires authentication`() =
        testApp(setupData = { TestData.createCashier() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me").status)
        }

    @Test
    fun `an unknown capability name does not break deserialization`() {
        // Forward compatibility, asserted on the DTO rather than over HTTP: an older client meeting a
        // newer server must ignore a capability it has never heard of, not fail the whole response.
        // That is why the field is Set<String> and not Set<Capability>.
        val body = """{"id":"1","username":"u","employeeNumber":"E1","role":"CASHIER","capabilities":["ORDER_READ","FUTURE_THING"]}"""
        val parsed = parse(body)
        assertEquals(setOf("ORDER_READ", "FUTURE_THING"), parsed.capabilities)

        val usable = parsed.capabilities.mapNotNull { name -> Capability.entries.firstOrNull { it.name == name } }
        assertEquals(listOf(Capability.ORDER_READ), usable, "the client keeps what it understands and drops the rest")
    }

    @Test
    fun `an older server's response still deserializes`() {
        // The field defaults to empty, which a client must read as "not reported" rather than "holds
        // nothing" — otherwise pointing a new client at an un-upgraded server would lock every user
        // out of their own UI.
        val body = """{"id":"1","username":"u","employeeNumber":"E1","role":"MANAGER"}"""
        assertEquals(emptySet(), parse(body).capabilities)
    }
}
