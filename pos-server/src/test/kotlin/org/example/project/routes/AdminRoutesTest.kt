package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Admin Routes")
class AdminRoutesTest {
    @Nested
    @DisplayName("GET /api/admin/permissions")
    inner class GetPermissions {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/admin/permissions")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 for admin`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/admin/permissions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().isNotBlank())
            }

        @Test
        fun `returns 403 for manager`() =
            testApp(
                setupData = { TestData.createManager() },
            ) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val response = client.get("/api/admin/permissions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }

        @Test
        fun `returns 403 for cashier`() =
            testApp(
                setupData = { TestData.createCashier() },
            ) { client ->
                val token = TestAuthHelper.loginAsCashier(client)
                val response = client.get("/api/admin/permissions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
    }
}
