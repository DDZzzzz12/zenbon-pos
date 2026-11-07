package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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
import kotlin.test.assertNull

/**
 * The role matrix for creating employees and changing their role (QA admin19 NOTE-i).
 *
 * A role moves the employee into the role's permission group, so the role field must obey the group
 * endpoint's rule: nobody confers capabilities they do not hold. A manager may create and move
 * cashiers and managers; only an administrator may create, promote to or demote from ADMIN.
 */
@DisplayName("Employee role escalation")
class EmployeeRoleEscalationRoutesTest {
    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    private fun roleOf(username: String) = transaction { Employee.find { EmployeeTable.username eq username }.first().role }

    private suspend fun HttpClient.create(
        token: String,
        username: String,
        role: String,
    ): HttpResponse =
        post("/api/employees") {
            bearerAuth(token)
            setBody(
                TextContent(
                    """{"username":"$username","password":"secret123","role":"$role"}""",
                    ContentType.Application.Json,
                ),
            )
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

    private suspend fun assertStatus(
        expected: HttpStatusCode,
        response: HttpResponse,
    ) = assertEquals(expected, response.status, response.bodyAsText())

    @Test
    fun `a manager cannot create an administrator`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createManager()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertStatus(HttpStatusCode.Forbidden, client.create(token, "sneaky", "ADMIN"))
            assertNull(transaction { Employee.find { EmployeeTable.username eq "sneaky" }.firstOrNull() })
        }

    @Test
    fun `a manager can create cashiers and managers`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertStatus(HttpStatusCode.Created, client.create(token, "newcashier", "CASHIER"))
            assertStatus(HttpStatusCode.Created, client.create(token, "newmanager", "MANAGER"))
        }

    @Test
    fun `an administrator can create an administrator`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            assertStatus(HttpStatusCode.Created, client.create(token, "secondadmin", "ADMIN"))
        }

    @Test
    fun `a manager cannot promote a cashier to administrator`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createManager()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertStatus(HttpStatusCode.Forbidden, client.changeRole(token, "cashier", "ADMIN"))
            assertEquals(EmployeeRole.CASHIER, roleOf("cashier"))
        }

    @Test
    fun `a manager can promote a cashier to manager`() =
        testApp(setupData = {
            TestData.createManager()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertStatus(HttpStatusCode.OK, client.changeRole(token, "cashier", "MANAGER"))
            assertEquals(EmployeeRole.MANAGER, roleOf("cashier"))
        }

    @Test
    fun `a manager cannot demote an administrator even when another admin exists`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createAdmin(employeeNumber = "ADM002", username = "admin2")
            TestData.createManager()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertStatus(HttpStatusCode.Forbidden, client.changeRole(token, "admin2", "CASHIER"))
            assertEquals(EmployeeRole.ADMIN, roleOf("admin2"))
        }

    @Test
    fun `an administrator can promote a manager to administrator`() =
        testApp(setupData = {
            TestData.createAdmin()
            TestData.createManager()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            assertStatus(HttpStatusCode.OK, client.changeRole(token, "manager", "ADMIN"))
            assertEquals(EmployeeRole.ADMIN, roleOf("manager"))
        }
}
