package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.RefreshTokenRequest
import org.example.project.auth.data.ValidationResponse
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("Auth Routes")
class AuthRoutesTest {
    private suspend fun login(
        client: io.ktor.client.HttpClient,
        username: String,
        password: String,
    ): Pair<HttpStatusCode, String> {
        val response =
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(identifier = username, secret = password))
            }
        return response.status to response.bodyAsText()
    }

    private fun parseLoginResponse(body: String): LoginResponse = testJson.decodeFromString<LoginResponse>(body)

    @Nested
    @DisplayName("Login")
    inner class Login {
        @Test
        fun `valid credentials return 200 with tokens`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (status, body) = login(client, "admin", TestData.DEFAULT_PASSWORD)

                assertEquals(HttpStatusCode.OK, status)

                val loginResponse = parseLoginResponse(body)
                assertNotNull(loginResponse.token.accessToken)
                assertNotNull(loginResponse.token.refreshToken)
                assertTrue(loginResponse.token.expiresIn > 0)
                assertEquals("ADM001", loginResponse.employee.employeeNumber)
            }

        @Test
        fun `invalid password returns 401`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (status, _) = login(client, "admin", "wrongpassword")

                assertEquals(HttpStatusCode.Unauthorized, status)
            }

        @Test
        fun `non-existent employee returns 401`() =
            testApp { client ->
                val (status, _) = login(client, "nonexistent", "somepassword")

                assertEquals(HttpStatusCode.Unauthorized, status)
            }

        @Test
        fun `blank username returns 400`() =
            testApp { client ->
                val (status, _) = login(client, "", TestData.DEFAULT_PASSWORD)

                assertEquals(HttpStatusCode.BadRequest, status)
            }

        @Test
        fun `blank password returns 400`() =
            testApp { client ->
                val (status, _) = login(client, "admin", "")

                assertEquals(HttpStatusCode.BadRequest, status)
            }

        @Test
        fun `inactive employee returns 401`() =
            testApp(
                setupData = { TestData.createInactiveEmployee() },
            ) { client ->
                val (status, _) = login(client, "inactive", TestData.DEFAULT_PASSWORD)

                assertEquals(HttpStatusCode.Unauthorized, status)
            }
    }

    @Nested
    @DisplayName("Token Refresh")
    inner class TokenRefresh {
        @Test
        fun `valid refresh token returns new tokens`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (_, loginBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                val loginResponse = parseLoginResponse(loginBody)

                val response =
                    client.post("/api/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = loginResponse.token.refreshToken))
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val refreshResponse = testJson.decodeFromString<LoginResponse>(response.bodyAsText())
                assertNotNull(refreshResponse.token.accessToken)
                assertNotNull(refreshResponse.token.refreshToken)
                assertTrue(refreshResponse.token.expiresIn > 0)
            }

        @Test
        fun `invalid refresh token returns 401`() =
            testApp { client ->
                val response =
                    client.post("/api/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = "invalid-token"))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `blank refresh token returns 400`() =
            testApp { client ->
                val response =
                    client.post("/api/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = ""))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("Validate")
    inner class Validate {
        @Test
        fun `valid token returns 200 with valid true`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (_, loginBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                val loginResponse = parseLoginResponse(loginBody)

                val response =
                    client.get("/api/auth/validate") {
                        bearerAuth(loginResponse.token.accessToken)
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val validationResponse = testJson.decodeFromString<ValidationResponse>(response.bodyAsText())
                assertTrue(validationResponse.valid)
            }

        @Test
        fun `no token returns 401`() =
            testApp { client ->
                val response = client.get("/api/auth/validate")

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("Logout")
    inner class Logout {
        @Test
        fun `logout with valid token returns 200`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (_, loginBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                val loginResponse = parseLoginResponse(loginBody)

                val response =
                    client.post("/api/auth/logout") {
                        bearerAuth(loginResponse.token.accessToken)
                    }

                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `logout without token returns 401`() =
            testApp { client ->
                val response = client.post("/api/auth/logout")

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("Me")
    inner class Me {
        @Test
        fun `authenticated user gets their info`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (_, loginBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                val loginResponse = parseLoginResponse(loginBody)

                val response =
                    client.get("/api/auth/me") {
                        bearerAuth(loginResponse.token.accessToken)
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val employeeInfo = testJson.decodeFromString<EmployeeInfoDto.EmployeeInfo>(response.bodyAsText())
                assertEquals("ADM001", employeeInfo.employeeNumber)
                assertEquals("admin", employeeInfo.username)
                assertEquals("ADMIN", employeeInfo.role)
            }

        @Test
        fun `unauthenticated request returns 401`() =
            testApp { client ->
                val response = client.get("/api/auth/me")

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("Integration Flow")
    inner class IntegrationFlow {
        @Test
        fun `full auth lifecycle`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                // Step 1: Login
                val (loginStatus, loginBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                assertEquals(HttpStatusCode.OK, loginStatus)
                val loginResponse = parseLoginResponse(loginBody)

                // Step 2: Validate original token
                val validateResponse =
                    client.get("/api/auth/validate") {
                        bearerAuth(loginResponse.token.accessToken)
                    }
                assertEquals(HttpStatusCode.OK, validateResponse.status)

                // Step 3: Refresh token
                val refreshResponse =
                    client.post("/api/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = loginResponse.token.refreshToken))
                    }
                assertEquals(HttpStatusCode.OK, refreshResponse.status)

                val newTokens = testJson.decodeFromString<LoginResponse>(refreshResponse.bodyAsText())

                // Step 4: Validate new token
                val validateNewResponse =
                    client.get("/api/auth/validate") {
                        bearerAuth(newTokens.token.accessToken)
                    }
                assertEquals(HttpStatusCode.OK, validateNewResponse.status)

                // Step 5: Logout
                val logoutResponse =
                    client.post("/api/auth/logout") {
                        bearerAuth(newTokens.token.accessToken)
                    }
                assertEquals(HttpStatusCode.OK, logoutResponse.status)
            }

        @Test
        fun `different roles can all login`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createManager()
                    TestData.createCashier()
                },
            ) { client ->
                // Admin login
                val (adminStatus, adminBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                assertEquals(HttpStatusCode.OK, adminStatus)
                val adminResponse = parseLoginResponse(adminBody)
                assertEquals("ADMIN", adminResponse.employee.role)

                // Manager login
                val (managerStatus, managerBody) = login(client, "manager", TestData.DEFAULT_PASSWORD)
                assertEquals(HttpStatusCode.OK, managerStatus)
                val managerResponse = parseLoginResponse(managerBody)
                assertEquals("MANAGER", managerResponse.employee.role)

                // Cashier login
                val (cashierStatus, cashierBody) = login(client, "cashier", TestData.DEFAULT_PASSWORD)
                assertEquals(HttpStatusCode.OK, cashierStatus)
                val cashierResponse = parseLoginResponse(cashierBody)
                assertEquals("CASHIER", cashierResponse.employee.role)
            }
    }

    @Nested
    @DisplayName("Revocation")
    inner class Revocation {
        @Test
        fun `after logout the refresh token no longer works`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (_, loginBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                val tokens = parseLoginResponse(loginBody).token

                val logout = client.post("/api/auth/logout") { bearerAuth(tokens.accessToken) }
                assertEquals(HttpStatusCode.OK, logout.status)

                val refresh =
                    client.post("/api/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = tokens.refreshToken))
                    }
                assertEquals(HttpStatusCode.Unauthorized, refresh.status)
            }

        @Test
        fun `reusing a rotated refresh token from another device is rejected`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val (_, loginBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                val original = parseLoginResponse(loginBody).token.refreshToken

                val first =
                    client.post("/api/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = original))
                    }
                assertEquals(HttpStatusCode.OK, first.status)

                // A same-device, near-immediate replay is deliberately tolerated (two browser tabs
                // refreshing at once) — see RefreshTokenServiceTest's grace-window coverage. A replay
                // presenting a DIFFERENT device is never that: it is the actual theft scenario, and
                // must still revoke the whole token family.
                val replay =
                    client.post("/api/auth/refresh") {
                        header("X-Device-ID", "attacker-device")
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = original))
                    }
                assertEquals(HttpStatusCode.Unauthorized, replay.status)

                // The family was revoked: the token the legitimate first refresh rotated in is dead too.
                val rotatedIn = parseLoginResponse(first.bodyAsText()).token.refreshToken
                val afterReplay =
                    client.post("/api/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = rotatedIn))
                    }
                assertEquals(HttpStatusCode.Unauthorized, afterReplay.status)
            }
    }

    @Nested
    @DisplayName("Session Admin")
    inner class SessionAdmin {
        @Test
        fun `admin can update policy`() =
            // A real server always has a shop; without one this test only passed because the write
            // was silently skipped, and it broke as soon as a class-mate left a shop id in the
            // process-wide ShopConfigurationManager cache.
            testApp(setupData = { seedShopWithFreshConfig() }) { client ->
                try {
                    val (_, body) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                    val token = parseLoginResponse(body).token.accessToken
                    val resp =
                        client.put("/api/auth/sessions/policy") {
                            contentType(ContentType.Application.Json)
                            bearerAuth(token)
                            setBody(
                                org.example.project.auth.data.SessionPolicyDto
                                    .UpdatePolicyRequest(true, "05:30"),
                            )
                        }
                    assertEquals(HttpStatusCode.OK, resp.status)
                } finally {
                    restoreDefaultSessionPolicy()
                }
            }

        /**
         * T9-2: the policy write must reach the database through the REAL manager. Seeding a shop is
         * what makes this meaningful — without one `writePolicy` returned early, which is why the test
         * above stayed green while every real save answered 500 "No transaction in context".
         */
        @Test
        fun `admin policy update is persisted when a shop exists`() =
            testApp(setupData = { seedShopWithFreshConfig() }) { client ->
                try {
                    val (_, body) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                    val token = parseLoginResponse(body).token.accessToken
                    val resp =
                        client.put("/api/auth/sessions/policy") {
                            contentType(ContentType.Application.Json)
                            bearerAuth(token)
                            setBody(
                                org.example.project.auth.data.SessionPolicyDto
                                    .UpdatePolicyRequest(false, "05:30"),
                            )
                        }
                    assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())

                    val stored =
                        dbQuery {
                            ConfigurationRepository().getSettingAsString(firstShopId(), ConfigKey.TOKEN_SESSION_POLICY.key)
                        }
                    assertNotNull(stored)
                    assertTrue(stored.contains("\"dailyResetTime\":\"05:30\""), stored)
                    assertTrue(stored.contains("\"dailyResetEnabled\":false"), stored)

                    val read = client.get("/api/auth/sessions/policy") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.OK, read.status)
                    assertTrue(read.bodyAsText().contains("05:30"), read.bodyAsText())
                } finally {
                    restoreDefaultSessionPolicy()
                }
            }

        /** T9-2: "log everyone out" answers 200 and actually rejects a token issued before it. */
        @Test
        fun `admin revoke all succeeds and invalidates earlier tokens`() =
            testApp(setupData = { seedShopWithFreshConfig() }) { client ->
                try {
                    val (_, body) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                    val token = parseLoginResponse(body).token.accessToken
                    assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth(token) }.status)

                    // The cutoff compares whole epoch seconds (iat < cutoff), so step past the login's second.
                    Thread.sleep(1_100)
                    val resp = client.post("/api/auth/sessions/revoke-all") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())

                    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me") { bearerAuth(token) }.status)

                    Thread.sleep(1_100)
                    val (_, freshBody) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                    val fresh = parseLoginResponse(freshBody).token.accessToken
                    assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { bearerAuth(fresh) }.status)
                } finally {
                    restoreDefaultSessionPolicy()
                }
            }

        @Test
        fun `cashier cannot revoke all`() =
            testApp(setupData = { TestData.createCashier() }) { client ->
                val (_, body) = login(client, "cashier", TestData.DEFAULT_PASSWORD)
                val token = parseLoginResponse(body).token.accessToken
                val resp = client.post("/api/auth/sessions/revoke-all") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Forbidden, resp.status)
            }

        @Test
        fun `admin policy update with bad time is rejected`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val (_, body) = login(client, "admin", TestData.DEFAULT_PASSWORD)
                val token = parseLoginResponse(body).token.accessToken
                val resp =
                    client.put("/api/auth/sessions/policy") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(token)
                        setBody(
                            org.example.project.auth.data.SessionPolicyDto
                                .UpdatePolicyRequest(true, "99:99"),
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
            }
    }

    private fun seedShopWithFreshConfig() {
        TestData.createAdmin()
        TestData.createShopInfo()
        // ShopConfigurationManager caches process-wide; state this test's own precondition.
        runBlocking { restoreDefaultSessionPolicy() }
    }

    private suspend fun firstShopId(): Long =
        dbQuery {
            ShopInfo
                .all()
                .first()
                .id.value
        }

    private suspend fun restoreDefaultSessionPolicy() {
        val shopId = firstShopId()
        dbQuery {
            ConfigurationRepository().setSetting(
                shopId,
                ConfigKey.TOKEN_SESSION_POLICY,
                ConfigKey.TOKEN_SESSION_POLICY.defaultValue,
            )
        }
        ShopConfigurationManager.refreshConfiguration()
    }
}
