package org.example.project.features.issuereport

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.example.project.features.issuereport.database.tables.IssueReportSettingsTable
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
 * The authorization surface of `/api/issue-reports`. The route registry denies by default, so a
 * 200 here also proves the rule module is registered, and the token never comes back out.
 */
@DisplayName("IssueReportRoutes")
class IssueReportRoutesTest {
    private fun seedRow(configure: (org.jetbrains.exposed.v1.core.statements.UpdateStatement) -> Unit) {
        transaction {
            val exists =
                IssueReportSettingsTable
                    .selectAll()
                    .where { IssueReportSettingsTable.id eq IssueReportSettingsTable.SINGLETON_ID }
                    .empty()
                    .not()
            if (!exists) IssueReportSettingsTable.insert { it[id] = IssueReportSettingsTable.SINGLETON_ID }
            IssueReportSettingsTable.update({ IssueReportSettingsTable.id eq IssueReportSettingsTable.SINGLETON_ID }) {
                configure(it)
            }
        }
    }

    /** Seals under a key this process does not hold, so the server reads it back as UNREADABLE. */
    private fun seedSealedToken(plaintext: String): String {
        val sealed = TokenCipher(TokenCipher.generateKeyBase64()).encrypt(plaintext)
        seedRow {
            it[IssueReportSettingsTable.tokenCiphertext] = sealed.ciphertextBase64
            it[IssueReportSettingsTable.tokenNonce] = sealed.nonceBase64
            it[IssueReportSettingsTable.tokenSetAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
        return sealed.ciphertextBase64
    }

    /** Seals an UPDATE token under a key this process does not hold, so it reads back as UNREADABLE. */
    private fun seedUnreadableUpdateToken(plaintext: String): String {
        val sealed = TokenCipher(TokenCipher.generateKeyBase64()).encrypt(plaintext)
        transaction {
            val missing =
                UpdateSettingsTable
                    .selectAll()
                    .where { UpdateSettingsTable.id eq UpdateSettingsTable.SINGLETON_ID }
                    .empty()
            if (missing) UpdateSettingsTable.insert { it[id] = UpdateSettingsTable.SINGLETON_ID }
            UpdateSettingsTable.update({ UpdateSettingsTable.id eq UpdateSettingsTable.SINGLETON_ID }) {
                it[tokenCiphertext] = sealed.ciphertextBase64
                it[tokenNonce] = sealed.nonceBase64
                it[tokenSetAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
        return sealed.ciphertextBase64
    }

    private fun json(text: String) = TextContent(text, ContentType.Application.Json)

    private fun report(includeLogs: Boolean = false) =
        json("""{"title":"Printer jams","description":"It jammed twice today.","source":"ADMIN","includeLogs":$includeLogs}""")

    private fun settingsBody(apiBaseUrl: String) = json("""{"owner":"DDZzzzz12","repo":"POS-KMP","apiBaseUrl":"$apiBaseUrl","isPublic":false}""")

    @Test
    @DisplayName("manager POST /preview reaches the service (200)")
    fun `a manager may preview a report`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/issue-reports/preview") {
                    bearerAuth(token)
                    setBody(report())
                }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("Printer jams"))
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val previewId = obj["previewId"]?.toString()?.trim('"')
            assertTrue(previewId != null && previewId.length == 32, "expected a previewId: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("POST with includeLogs but no previewId is 400 even for a private repository")
    fun `logs are never sent without a preview`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            seedRow { it[IssueReportSettingsTable.isPublic] = false }
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.post("/api/issue-reports") {
                    bearerAuth(token)
                    setBody(report(includeLogs = true))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("preview"), response.bodyAsText())
        }

    @Test
    @DisplayName("POST with a previewId this caller never received is 409")
    fun `an unknown preview is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.post("/api/issue-reports") {
                    bearerAuth(token)
                    setBody(
                        json(
                            """{"title":"Printer jams","description":"It jammed.","source":"ADMIN",""" +
                                """"previewId":"0123456789abcdef0123456789abcdef"}""",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("preview again"), response.bodyAsText())
        }

    @Test
    @DisplayName("manager POST with no token configured is 409")
    fun `filing without a token is a conflict`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/issue-reports") {
                    bearerAuth(token)
                    setBody(report())
                }
            assertEquals(HttpStatusCode.Conflict, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("manager GET/PUT settings and PUT/DELETE token are 403")
    fun `a manager cannot configure reporting`() =
        testApp(setupData = { TestData.createManager() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val read = client.get("/api/issue-reports/settings") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, read.status, "body: ${read.bodyAsText()}")
            val save =
                client.put("/api/issue-reports/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("https://api.github.com"))
                }
            assertEquals(HttpStatusCode.Forbidden, save.status, "body: ${save.bodyAsText()}")
            val set =
                client.put("/api/issue-reports/token") {
                    bearerAuth(token)
                    setBody(json("""{"token":"not-a-real-token"}"""))
                }
            assertEquals(HttpStatusCode.Forbidden, set.status, "body: ${set.bodyAsText()}")
            val clear = client.delete("/api/issue-reports/token") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, clear.status, "body: ${clear.bodyAsText()}")
        }

    @Test
    @DisplayName("admin GET /settings shows NONE and carries no token key")
    fun `the settings read of a fresh install has no token`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/issue-reports/settings") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("NONE", obj["tokenState"]?.toString()?.trim('"'))
            assertEquals("NONE", obj["updateTokenState"]?.toString()?.trim('"'))
            assertEquals("false", obj["useUpdateToken"]?.toString())
            val allowed = setOf("tokenState", "tokenPreview", "tokenSetAt", "useUpdateToken", "updateTokenState")
            val tokenKeys = obj.keys.filter { it.contains("token", ignoreCase = true) }.toSet()
            assertEquals(allowed, tokenKeys.intersect(allowed))
            assertEquals(emptySet(), tokenKeys - allowed, "unexpected token key in $obj")
        }

    @Test
    @DisplayName("GET /settings shows the update token's state and nothing of it")
    fun `the settings read shows the update token state only`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val ciphertext = seedUnreadableUpdateToken("github_pat_11UPDATE_supersecretupdate")
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/issue-reports/settings") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val body = response.bodyAsText()
            val obj = Json.parseToJsonElement(body).jsonObject
            assertEquals("UNREADABLE", obj["updateTokenState"]?.toString()?.trim('"'))
            assertEquals("NONE", obj["tokenState"]?.toString()?.trim('"'))
            assertFalse(body.contains("supersecretupdate"), "update token leaked: $body")
            assertFalse(body.contains(ciphertext), "update ciphertext leaked: $body")
        }

    @Test
    @DisplayName("PUT /settings choosing the update token for another host is 400")
    fun `the update token is never retargeted to a foreign host`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val evil =
                client.put("/api/issue-reports/settings") {
                    bearerAuth(token)
                    setBody(
                        json(
                            """{"owner":"DDZzzzz12","repo":"POS-KMP","apiBaseUrl":"https://github.evil.example/api/v3",""" +
                                """"isPublic":false,"useUpdateToken":true}""",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, evil.status, "body: ${evil.bodyAsText()}")
            assertTrue(evil.bodyAsText().contains("update source"), evil.bodyAsText())
            // A stable code, so the client can localize the refusal instead of showing this English detail.
            assertTrue(evil.bodyAsText().contains("ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH"), evil.bodyAsText())

            val after = client.get("/api/issue-reports/settings") { bearerAuth(token) }
            val obj = Json.parseToJsonElement(after.bodyAsText()).jsonObject
            assertEquals("false", obj["useUpdateToken"]?.toString())
            assertEquals("https://api.github.com", obj["apiBaseUrl"]?.toString()?.trim('"'))
        }

    @Test
    @DisplayName("an unreadable sealed token leaks neither plaintext nor ciphertext")
    fun `the settings read never returns the stored token`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val ciphertext = seedSealedToken("github_pat_11ABCDEFG_supersecretvalue")
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/issue-reports/settings") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")
            val body = response.bodyAsText()
            assertFalse(body.contains("github_pat_11ABCDEFG_supersecretvalue"), "plaintext leaked: $body")
            assertFalse(body.contains("supersecret"), "part of the plaintext leaked: $body")
            assertFalse(body.contains(ciphertext), "ciphertext leaked: $body")
            assertTrue(body.contains("UNREADABLE"), "expected the undecryptable state: $body")
        }

    @Test
    @DisplayName("PUT /settings with an http base url is 400")
    fun `a plaintext base url is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/issue-reports/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("http://evil.example"))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("PUT /settings with a raw IP base url is 400")
    fun `a raw ip base url is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.put("/api/issue-reports/settings") {
                    bearerAuth(token)
                    setBody(settingsBody("https://192.168.1.10"))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("POST with includeLogs against a public repository is 400")
    fun `logs are refused for a public repository`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            seedRow { it[IssueReportSettingsTable.isPublic] = true }
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.post("/api/issue-reports") {
                    bearerAuth(token)
                    setBody(report(includeLogs = true))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: ${response.bodyAsText()}")
        }

    @Test
    @DisplayName("unauthenticated POST is 401")
    fun `an unauthenticated report is refused`() =
        testApp { client ->
            val response = client.post("/api/issue-reports") { setBody(report()) }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "body: ${response.bodyAsText()}")
        }
}
