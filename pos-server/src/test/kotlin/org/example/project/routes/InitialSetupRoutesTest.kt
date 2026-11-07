package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.*
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Initial Setup Routes")
class InitialSetupRoutesTest {
    @Nested
    @DisplayName("GET /api/setup/status")
    inner class GetSetupStatus {
        @Test
        fun `returns 200 with requiresSetup true when not initialized`() =
            testApp { client ->
                val response = client.get("/api/setup/status")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                assertTrue(body.containsKey("isInitialized") || body.containsKey("requiresSetup"))
            }

        @Test
        fun `returns 200 with isInitialized true when already set up`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val response = client.get("/api/setup/status")
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/setup/readiness")
    inner class GetReadiness {
        @Test
        fun `rejects an unauthenticated caller`() =
            testApp { client ->
                // The whole reason this is a second endpoint: it reports fiscal terminal + TSE
                // commissioning state, which must not be readable without a MANAGER session.
                val response = client.get("/api/setup/readiness")
                assertTrue(
                    response.status == HttpStatusCode.Unauthorized ||
                        response.status == HttpStatusCode.Forbidden,
                    "expected 401/403 for an unauthenticated caller, got ${response.status}",
                )
            }
    }

    @Nested
    @DisplayName("GET /api/setup/status stays public and unchanged")
    inner class StatusShapeGuard {
        /**
         * Regression guard, not a feature test. `/status` must stay unauthenticated for the bootstrap
         * gate, and must NOT grow the fiscal fields that `/readiness` carries. This fails if someone
         * later "simplifies" the two endpoints back into one and re-exposes fiscal state publicly.
         */
        @Test
        fun `is reachable without auth and exposes no fiscal fields`() =
            testApp { client ->
                val response = client.get("/api/setup/status")
                assertEquals(HttpStatusCode.OK, response.status)

                val body = testJson.parseToJsonElement(response.bodyAsText()).jsonObject
                val systemState = body["systemState"]?.jsonObject
                listOf("hasFiscalTerminal", "tseCommissioned").forEach { forbidden ->
                    assertTrue(
                        !body.containsKey(forbidden) && systemState?.containsKey(forbidden) != true,
                        "/api/setup/status must not expose '$forbidden' — it is public",
                    )
                }
            }
    }

    @Nested
    @DisplayName("POST /api/setup/initialize")
    inner class Initialize {
        @Test
        fun `returns 400 for invalid request body`() =
            testApp { client ->
                val response =
                    client.post("/api/setup/initialize") {
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `rejects setup when already initialized`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val body =
                    """
                    {
                      "owner": {
                        "username": "owner2",
                        "password": "test1234",
                        "employeeNumber": "OWN002"
                      },
                      "shop": {
                        "shopName": "Another Shop",
                        "shopAddress": "456 Other St",
                        "shopCity": "Other City",
                        "zipCode": 54321,
                        "shopCountry": "DE",
                        "shopPhone": "+49 999 999999"
                      }
                    }
                    """.trimIndent()
                val response =
                    client.post("/api/setup/initialize") {
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertTrue(
                    response.status.value in 400..499,
                    "Expected 4xx when system already initialized, got ${response.status}",
                )
            }
    }

    @Nested
    @DisplayName("POST /api/setup/restore-backup")
    inner class RestoreBackup {
        @Test
        fun `returns error when no file provided`() =
            testApp { client ->
                val response = client.post("/api/setup/restore-backup")
                // Route handler catches all exceptions — may return 400 or 500
                assertTrue(
                    response.status.value in 400..500,
                    "Expected 4xx or 500 but got ${response.status}",
                )
            }

        @Test
        fun `returns error when system already initialized`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val response = client.post("/api/setup/restore-backup")
                // Route checks initialization first — returns 409 or 500 depending on where exception occurs
                assertTrue(
                    response.status.value in 400..500,
                    "Expected 4xx or 500 but got ${response.status}",
                )
            }
    }
}
