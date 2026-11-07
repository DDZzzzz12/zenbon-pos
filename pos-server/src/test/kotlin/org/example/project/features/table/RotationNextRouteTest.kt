package org.example.project.features.table

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RotationNextRouteTest {
    private fun seedPoolEmployee(number: String) {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        transaction { Employee.findById(emp.id)!!.inRotationPool = true }
    }

    @Test
    fun returnsNextAndVersion() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                seedPoolEmployee("001")
                seedPoolEmployee("002")
                runBlocking { TestData.setRoundRobinAssignment(true) }
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/tables/rotation/next") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = testJson.decodeFromString<RotationDto.NextSnapshotDto>(response.bodyAsText())
            assertEquals("001", body.next?.employeeNumber)
        }

    @Test
    fun returnsNullNextWhenToggleOff() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                seedPoolEmployee("001")
                runBlocking { TestData.setRoundRobinAssignment(false) }
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/tables/rotation/next") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = testJson.decodeFromString<RotationDto.NextSnapshotDto>(response.bodyAsText())
            assertNull(body.next)
        }

    @Test
    fun unauthenticatedIs401() =
        testApp { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/tables/rotation/next").status)
        }

    @Test
    fun employeeWithoutTableReadIs403() {
        lateinit var cashier: Employee
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                cashier = TestData.createCashier()
            },
        ) { client ->
            // Sign-in is refused for a user without a permission group, so the group is taken away
            // after the login: the token outlives it and the route must still say 403.
            val token = TestAuthHelper.loginAsCashier(client)
            transaction {
                EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq cashier.id }
            }
            // The login cached the capabilities; a raw delete does not evict them, the service paths do.
            GlobalContext.get().get<GroupCapabilityResolver>().invalidate(cashier.id.value)
            val response = client.get("/api/tables/rotation/next") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        }
    }
}
