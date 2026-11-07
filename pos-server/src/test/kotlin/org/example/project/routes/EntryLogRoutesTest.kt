package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Entry Log Routes")
class EntryLogRoutesTest {
    @Nested
    @DisplayName("GET /api/entry-logs/paginated")
    inner class GetPaginatedLogs {
        @Test
        fun `returns 200 with default params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/entry-logs/paginated") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports filter params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/entry-logs/paginated?type=PRODUCT&operation=CREATE&page=0&size=10") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `accepts search param`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/entry-logs/paginated?search=coffee&page=0&size=10") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/entry-logs/paginated")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
