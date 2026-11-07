package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2: an employee with no permission group is refused at sign-in with 403 `NO_PERMISSION_GROUP`,
 * instead of receiving a session with `capabilities: []` that 403s on every call.
 */
@DisplayName("Sign-in without a permission group")
class NoPermissionGroupLoginTest {
    private suspend fun login(
        client: HttpClient,
        username: String,
        secret: String = TestData.DEFAULT_PASSWORD,
    ): HttpResponse =
        client.post("/api/auth/login") {
            setBody(TextContent("""{"identifier":"$username","secret":"$secret"}""", ContentType.Application.Json))
        }

    private fun removeGroup(id: UUID) = transaction { EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq id } }

    @Test
    fun `an employee without a group is refused with 403 NO_PERMISSION_GROUP`() =
        testApp(
            setupData = {
                val e = TestData.createCashier(employeeNumber = "NG001", username = "nogroup")
                removeGroup(e.id.value)
            },
        ) { client ->
            val response = login(client, "nogroup")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("NO_PERMISSION_GROUP"), response.bodyAsText())
        }

    @Test
    fun `a wrong secret still answers 401, so group membership does not leak`() =
        testApp(
            setupData = {
                val e = TestData.createCashier(employeeNumber = "NG002", username = "nogroup2")
                removeGroup(e.id.value)
            },
        ) { client ->
            assertEquals(HttpStatusCode.Unauthorized, login(client, "nogroup2", "wrong-secret").status)
        }

    @Test
    fun `an employee with a group signs in, and so does the terminal-device principal`() =
        testApp(
            setupData = {
                TestData.createCashier()
                TestData.createTerminal()
            },
        ) { client ->
            assertEquals(HttpStatusCode.OK, login(client, "cashier").status)
            // The terminal fixture is in the TERMINAL_DEVICE group, exactly as the seeded device account is.
            assertEquals(HttpStatusCode.OK, login(client, "terminal").status)
            TestAuthHelper.loginAsCashier(client)
        }
}
