package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.errorHandling.ExceptionConverter
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * emu20 NOTE-p: creating an employee, changing their role or permission group and deactivating them
 * wrote no `transaction_audit_log` row — neither on success nor when refused. Each now writes one
 * through the real [ExceptionConverter] path, read back from the table (a recording fake would not
 * see a row that fails to persist).
 */
@DisplayName("Employee changes are audited (emu20 NOTE-p)")
class EmployeeAuditRoutesTest {
    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    private fun rows(operation: String) = transaction { TransactionAuditLog.all().filter { it.operation == operation }.map { it.snapshot() } }

    private data class Row(
        val entityType: String,
        val entityId: String,
        val entityName: String?,
        val employeeId: String,
        val reason: String?,
        val afterState: String?,
    )

    private fun TransactionAuditLog.snapshot() = Row(entityType, entityId, entityName, employeeId, reason, afterState)

    private suspend fun HttpClient.create(
        token: String,
        username: String,
        role: String,
    ): HttpResponse =
        post("/api/employees") {
            bearerAuth(token)
            setBody(TextContent("""{"username":"$username","password":"secret123","role":"$role"}""", ContentType.Application.Json))
        }

    private suspend fun HttpClient.changeRole(
        token: String,
        username: String,
        role: String,
    ): HttpResponse =
        put("/api/employees/${idOf(username)}") {
            bearerAuth(token)
            setBody(TextContent("""{"role":"$role"}""", ContentType.Application.Json))
        }

    @Test
    fun `a successful create writes CREATE_EMPLOYEE naming the new employee`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.create(token, "newcashier", "CASHIER")
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())

            val row = rows("CREATE_EMPLOYEE").single()
            assertEquals("EMPLOYEE", row.entityType)
            assertEquals(idOf("newcashier").toString(), row.entityId)
            assertEquals(idOf("manager").toString(), row.employeeId, "the actor, not the new employee")
            assertTrue(row.afterState.orEmpty().contains("\"role\":\"CASHIER\""), "after_state names the role: ${row.afterState}")
        }

    @Test
    fun `a refused create writes a FAILED CREATE_EMPLOYEE row with the reason`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createManager()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(HttpStatusCode.Forbidden, client.create(token, "sneaky", "ADMIN").status)

            val row = rows("CREATE_EMPLOYEE").single()
            assertEquals(ExceptionConverter.FAILED_ENTITY_NAME, row.entityName)
            assertTrue(row.reason.orEmpty().startsWith("ERROR:"), row.reason)
        }

    @Test
    fun `a role change writes UPDATE_EMPLOYEE, and a refused one names the target employee`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createManager()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val cashierId = idOf("cashier").toString()

            assertEquals(HttpStatusCode.Forbidden, client.changeRole(token, "cashier", "ADMIN").status)
            assertEquals(HttpStatusCode.OK, client.changeRole(token, "cashier", "MANAGER").status)

            val updates = rows("UPDATE_EMPLOYEE")
            assertEquals(2, updates.size, "$updates")
            assertTrue(updates.all { it.entityId == cashierId }, "both rows sit in the cashier's trail: $updates")
            assertEquals(1, updates.count { it.entityName == ExceptionConverter.FAILED_ENTITY_NAME }, "$updates")
            assertTrue(updates.any { it.afterState.orEmpty().contains("\"role\":\"MANAGER\"") }, "$updates")
        }

    @Test
    fun `assigning a permission group is audited, and so is the refused self-assignment`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val body = TextContent("""{"groupCode":"MANAGER"}""", ContentType.Application.Json)

            val ok =
                client.put("/api/employees/${idOf("cashier")}/permission-group") {
                    bearerAuth(token)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            val self =
                client.put("/api/employees/${idOf("admin")}/permission-group") {
                    bearerAuth(token)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Forbidden, self.status)

            val assigned = rows("ASSIGN_PERMISSION_GROUP")
            assertEquals(2, assigned.size, "$assigned")
            assertTrue(assigned.any { it.entityId == idOf("cashier").toString() && it.entityName == "MANAGER" }, "$assigned")
            assertTrue(
                assigned.any { it.entityId == idOf("admin").toString() && it.entityName == ExceptionConverter.FAILED_ENTITY_NAME },
                "$assigned",
            )
        }

    @Test
    fun `deactivating an employee writes DEACTIVATE_EMPLOYEE`() =
        testApp(setupData = {
            TestData.createManager()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.patch("/api/employees/${idOf("cashier")}/deactivate") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            val row = rows("DEACTIVATE_EMPLOYEE").single()
            assertEquals(idOf("cashier").toString(), row.entityId)
            assertEquals(idOf("manager").toString(), row.employeeId)
            // admin19/admin20 NOTE-m: no English system line is appended to the operator's Notizen.
            val notes = transaction { Employee.find { EmployeeTable.username eq "cashier" }.first().notes }
            assertTrue(notes.isNullOrBlank(), "notes were: $notes")
        }

    // emu21 NOTE-u: ASSIGN_PERMISSION_GROUP held the DTO's Kotlin toString() and DEACTIVATE_EMPLOYEE
    // held "kotlin.Unit". Every employee audit row's state must be JSON (or absent).
    @Test
    fun `every employee audit row carries JSON or null as its before and after state`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createManager()
            TestData.createCashier()
        }) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            assertEquals(HttpStatusCode.Created, client.create(admin, "jsoncashier", "CASHIER").status)
            assertEquals(HttpStatusCode.OK, client.changeRole(admin, "jsoncashier", "MANAGER").status)
            val assign =
                client.put("/api/employees/${idOf("cashier")}/permission-group") {
                    bearerAuth(admin)
                    setBody(TextContent("""{"groupCode":"MANAGER"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, assign.status, assign.bodyAsText())
            val deactivate = client.patch("/api/employees/${idOf("jsoncashier")}/deactivate") { bearerAuth(admin) }
            assertEquals(HttpStatusCode.OK, deactivate.status, deactivate.bodyAsText())
            val delete = client.delete("/api/employees/${idOf("cashier")}") { bearerAuth(admin) }
            assertTrue(delete.status.value in 200..299, "${delete.status} ${delete.bodyAsText()}")

            val operations = setOf("CREATE_EMPLOYEE", "UPDATE_EMPLOYEE", "ASSIGN_PERMISSION_GROUP", "DEACTIVATE_EMPLOYEE")
            val states =
                transaction {
                    TransactionAuditLog
                        .all()
                        .filter { it.operation in operations }
                        .map { Triple(it.operation, it.beforeState, it.afterState) }
                }
            assertEquals(operations, states.map { it.first }.toSet(), "$states")
            states.forEach { (operation, before, after) ->
                listOf(before, after).filterNotNull().forEach { state ->
                    assertTrue(runCatching { Json.parseToJsonElement(state) is JsonObject }.getOrDefault(false), "$operation state is not JSON: $state")
                }
            }
            val deactivated = states.filter { it.first == "DEACTIVATE_EMPLOYEE" }
            assertEquals(2, deactivated.size, "the deactivate route and DELETE both write one: $deactivated")
            assertTrue(deactivated.all { it.third.orEmpty().contains("\"employmentStatus\":\"INACTIVE\"") }, "$deactivated")
            val assigned = states.single { it.first == "ASSIGN_PERMISSION_GROUP" }.third.orEmpty()
            assertTrue(assigned.contains("\"permissionGroupCode\":\"MANAGER\""), assigned)
        }
}
