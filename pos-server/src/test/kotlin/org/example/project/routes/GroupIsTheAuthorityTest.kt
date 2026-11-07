package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `permission_group` table is the authority — for stations as well as for people.
 *
 * Two review findings were deferred to this stage on the grounds that they were "correct today and
 * wrong the moment groups are editable". Both are fixed here, and both are the same mistake: reading
 * the COMPILED definitions where the DATABASE is what actually decides.
 *
 *  - The route plugin resolved a login-less station through `CapabilityBundles.forRole(...)`, so an
 *    operator narrowing the TERMINAL_DEVICE group would have seen it apply to every employee and to
 *    not one station.
 *  - `assignPermissionGroup` validated the actor's authority against `CapabilityBundles`, so once a
 *    group is editable it would check a set nobody would be granted — and reject an
 *    operator-created group outright, since that exists only as a row.
 */
@DisplayName("The permission group is the authority")
class GroupIsTheAuthorityTest {
    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    private fun removeCapabilityFromGroup(
        code: String,
        capability: Capability,
    ) = transaction {
        val groupId =
            PermissionGroupTable
                .selectAll()
                .where { PermissionGroupTable.code eq code }
                .first()[PermissionGroupTable.id]
        PermissionGroupCapabilityTable.deleteWhere {
            (PermissionGroupCapabilityTable.group eq groupId) and
                (PermissionGroupCapabilityTable.capability eq capability.name)
        }
    }

    @Test
    fun `a station resolves through the group table, not the compiled bundle`() =
        testApp(setupData = { TestData.createAdmin() }) { _ ->
            // Edit the row the way Stage 5's API will, then ask the resolver what TERMINAL_DEVICE
            // confers. Reading the bundle would still answer ORDER_WRITE, because the bundle is
            // compiled in and cannot change.
            val resolver = GroupCapabilityResolver()
            runBlocking {
                assertTrue(
                    Capability.ORDER_WRITE in resolver.forGroupCode(EmployeeRole.TERMINAL_DEVICE.name)!!,
                    "precondition: a station may place an order",
                )
            }

            removeCapabilityFromGroup(EmployeeRole.TERMINAL_DEVICE.name, Capability.ORDER_WRITE)
            resolver.invalidateAll()

            runBlocking {
                assertFalse(
                    Capability.ORDER_WRITE in resolver.forGroupCode(EmployeeRole.TERMINAL_DEVICE.name)!!,
                    "an edit to the group row must reach a login-less station — this is the finding",
                )
            }
        }

    @Test
    fun `invalidateAll clears the group cache as well as the employee cache`() =
        testApp(setupData = { TestData.createAdmin() }) { _ ->
            // A version that cleared only the employee map would leave every station resolving the
            // OLD group after an edit — the same shape as the conditional eviction that once left
            // revoked stations working until restart.
            val resolver = GroupCapabilityResolver()
            runBlocking { resolver.forGroupCode(EmployeeRole.CASHIER.name) }

            removeCapabilityFromGroup(EmployeeRole.CASHIER.name, Capability.BILL_WRITE)
            runBlocking {
                assertTrue(
                    Capability.BILL_WRITE in resolver.forGroupCode(EmployeeRole.CASHIER.name)!!,
                    "still cached, which is the point of the cache",
                )
            }

            resolver.invalidateAll()
            runBlocking {
                assertFalse(Capability.BILL_WRITE in resolver.forGroupCode(EmployeeRole.CASHIER.name)!!)
            }
        }

    @Test
    fun `an unknown group code resolves to null rather than to the bundle`() =
        testApp(setupData = { TestData.createAdmin() }) { _ ->
            // Fails closed. A fallback to `CapabilityBundles` is exactly what let the two
            // authorities diverge, so a missing row must be a refusal and not a silent substitution.
            val resolver = GroupCapabilityResolver()
            runBlocking { assertEquals(null, resolver.forGroupCode("A_GROUP_NOBODY_SEEDED")) }
        }

    @Test
    fun `assignment validates against the row, so an operator-created group is assignable`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createManager(employeeNumber = "AUT001", username = "target")
            },
        ) { client ->
            // A group that exists ONLY as a row — no compiled definition. Before this change the
            // service looked it up in `CapabilityBundles.definitions()` and answered "Unknown
            // permission group", which would have made every Stage 5 group unusable the moment it
            // was created.
            transaction {
                val now =
                    org.example.project.utils.formatter
                        .currentTimeStamp()
                val id =
                    PermissionGroupTable.insertAndGetId {
                        it[code] = "OPERATOR_MADE"
                        it[name] = "Operator Made"
                        it[description] = "created through the API, not seeded"
                        it[isSystem] = false
                        it[isDeletable] = true
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                // SELF_READ as well as DOCUMENT_READ. A group without SELF_READ cannot read its own
                // profile — correct, and it made the first version of this test fail on
                // `/api/auth/me` rather than on anything to do with the finding.
                listOf(Capability.SELF_READ, Capability.DOCUMENT_READ).forEach { cap ->
                    PermissionGroupCapabilityTable.insert {
                        it[group] = id
                        it[capability] = cap.name
                    }
                }
            }

            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/employees/${idOf("target")}/permission-group") {
                    bearerAuth(token)
                    setBody(TextContent("""{"groupCode":"OPERATOR_MADE"}""", ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.OK, response.status, "a group that exists as a row must be assignable")

            // And it takes effect: the target now holds only what that row confers.
            val targetToken = TestAuthHelper.loginAs(client, "target")
            val me = client.get("/api/auth/me") { bearerAuth(targetToken) }
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/employees/summary") { bearerAuth(targetToken) }.status,
                "role MANAGER would allow this; the operator-made group confers no EMPLOYEE_READ",
            )
        }
}
