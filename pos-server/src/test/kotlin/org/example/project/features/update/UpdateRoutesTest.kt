package org.example.project.features.update

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.database.tables.UpdateSettingsTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The authorization surface of `/api/update`, and the one property that outranks all of it: the
 * GitHub token never comes back out.
 *
 * The route registry denies by default, so every case here is also a check that the rule module is
 * registered at all — an unregistered route answers 403 to an ADMIN, indistinguishably from a
 * deliberate refusal.
 */
@DisplayName("UpdateRoutes")
class UpdateRoutesTest {
    /**
     * Seals [plaintext] into the settings row with a key this process does NOT hand the server.
     *
     * The server's `TokenCipher` comes from `UPDATE_TOKEN_KEY`, which is unset under test, so it
     * reads this back as an *undecryptable* token — which is exactly the harder case for the
     * question being asked: the row genuinely holds a sealed credential, and the response must
     * contain neither the plaintext nor the ciphertext.
     */
    private fun seedSealedToken(plaintext: String): String {
        val sealed = TokenCipher(TokenCipher.generateKeyBase64()).encrypt(plaintext)
        transaction {
            val exists =
                UpdateSettingsTable
                    .selectAll()
                    .where { UpdateSettingsTable.id eq UpdateSettingsTable.SINGLETON_ID }
                    .empty()
                    .not()
            if (!exists) UpdateSettingsTable.insert { it[id] = UpdateSettingsTable.SINGLETON_ID }
            UpdateSettingsTable.update({ UpdateSettingsTable.id eq UpdateSettingsTable.SINGLETON_ID }) {
                it[tokenCiphertext] = sealed.ciphertextBase64
                it[tokenNonce] = sealed.nonceBase64
                it[tokenSetAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
        return sealed.ciphertextBase64
    }

    private fun settingsBody(
        apiBaseUrl: String,
        channel: String = "PUBLIC",
        owner: String = "DDZzzzz12",
        name: String = "zenbon-pos",
        autoApply: Boolean = false,
    ) = TextContent(
        // repoOwner/repoName are NOT part of the request shape any more -- the repository follows
        // the channel. They are still emitted here so the smuggling test below sends a body a
        // hostile or out-of-date client would send, and can prove the server ignores them.
        """{"channel":"$channel","repoOwner":"$owner","repoName":"$name","apiBaseUrl":"$apiBaseUrl","autoApply":$autoApply}""",
        ContentType.Application.Json,
    )

    @Test
    @DisplayName("GET /settings never returns the stored token")
    fun `the settings read carries no token`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val ciphertext = seedSealedToken("github_pat_11ABCDEFG_supersecretvalue")
            val token = TestAuthHelper.loginAsAdmin(client)

            val response = client.get("/api/update/settings") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

            val body = response.bodyAsText()
            assertFalse(body.contains("github_pat_11ABCDEFG_supersecretvalue"), "plaintext token leaked: $body")
            assertFalse(body.contains("supersecret"), "part of the plaintext token leaked: $body")
            assertFalse(body.contains(ciphertext), "ciphertext leaked: $body")
            // The row genuinely holds a sealed token; this host simply cannot open it.
            assertTrue(body.contains("UNREADABLE"), "expected the undecryptable-token state: $body")
        }

    @Test
    @DisplayName("PUT /token with only UPDATE_READ is 403")
    fun `a manager cannot store the token`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.put("/api/update/token") {
                    bearerAuth(token)
                    setBody(TextContent("""{"token":"not-a-real-token"}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("DELETE /token with only UPDATE_READ is 403")
    fun `a manager cannot clear the token`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/update/token") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("POST /apply with only UPDATE_READ is 403")
    fun `a manager cannot install an update`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.post("/api/update/apply") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("GET /status with UPDATE_READ is 200")
    fun `a manager may read the update status`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/update/status") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("currentVersion"))
        }

    @Test
    @DisplayName("POST /apply while the preflight is blocking is 409 naming the reason")
    fun `a blocked preflight is a 409 that says why`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            // An open table session is a blocker a route test can produce honestly: the shop is demonstrably
            // trading, and the preflight must say so rather than restart the server under the till.
            TestData.createTableWithSession()
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/update/apply") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
            val body = response.bodyAsText()
            assertTrue(body.contains("open table session"), "the 409 must name the blocker: $body")
        }

    @Test
    @DisplayName("PUT /settings with an http base url is 400")
    fun `a plaintext base url is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/update/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("http://api.github.com"))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("PUT /settings with a raw IPv4 host is 400")
    fun `a raw ipv4 base url is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/update/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("https://192.168.1.10"))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("PUT /settings with a raw IPv6 host is 400")
    fun `a raw ipv6 base url is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/update/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("https://[2001:db8::1]"))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("PUT /settings with a loopback name is 400")
    fun `a localhost base url is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/update/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("https://localhost"))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("PUT /settings with embedded credentials is 400")
    fun `a base url carrying userinfo is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/update/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("https://api.github.com@evil.example"))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("PUT /settings cannot smuggle a repository past the channel")
    fun `a repo sent by the client is ignored`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            // This replaces a test that expected 400 for a path traversal in repoName. There is no
            // repository field any more -- it follows the channel -- so the stronger statement is
            // that sending one changes nothing at all. A traversal, someone else's repository and a
            // stale value from an out-of-date client are all the same case: ignored.
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/update/settings") {
                    bearerAuth(token)
                    setBody(
                        settingsBody(
                            apiBaseUrl = "https://api.github.com",
                            channel = "PRIVATE",
                            owner = "..%2Fevil",
                            name = "..%2Fevil",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val body = response.bodyAsText()
            assertFalse(body.contains("evil"), "the smuggled repository must not be stored: $body")
            assertTrue(
                body.contains("\"repoName\":\"POS-KMP\""),
                "the internal channel must report its own repository: $body",
            )
        }

    @Test
    @DisplayName("PUT /settings with a valid https host is 200 and persisted")
    fun `a valid update source is stored`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/update/settings") {
                    bearerAuth(token)
                    setBody(
                        settingsBody(
                            apiBaseUrl = "https://ghe.example.com/api/v3",
                            channel = "PRIVATE",
                            owner = "DDZzzzz12",
                            name = "POS-KMP",
                            autoApply = true,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

            val reread = client.get("/api/update/settings") { bearerAuth(token) }
            val body = reread.bodyAsText()
            assertTrue(body.contains("https://ghe.example.com/api/v3"), body)
            assertTrue(body.contains("PRIVATE"), body)
            assertTrue(body.contains("POS-KMP"), body)
        }

    @Test
    @DisplayName("an unauthenticated request is 401")
    fun `no bearer token is rejected`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/update/status").status)
        }
}
