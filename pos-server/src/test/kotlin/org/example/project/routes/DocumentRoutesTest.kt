package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Document Routes")
class DocumentRoutesTest {
    @Nested
    @DisplayName("GET /api/documents")
    inner class SearchDocuments {
        @Test
        fun `returns 200 with default filters`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `supports filter params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents?fileType=INVOICE&limit=10&offset=0") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `accepts a processingStatus query parameter`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents?processingStatus=REVIEWED") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `rejects an unknown processingStatus with 400 rather than ignoring it`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                // Silently dropping an unparseable status would return the *unfiltered* archive to
                // a client that asked for one bucket — the worst possible failure mode for a work
                // queue, because the page looks perfectly normal.
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents?processingStatus=ERLEDIGT") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `accepts a comma-separated processingStatus list`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                // The work queue's "Zu erfassen" bucket is one request, not three: offset/limit are
                // per-request, so a client-side merge of three responses cannot page.
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/documents?processingStatus=NONE,UNPROCESSABLE,FAILED") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `rejects a list containing one unknown processingStatus with 400`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                // A per-token check, not a "keep whatever parsed" filter: dropping the bad token
                // would silently widen the bucket, and the response would look entirely normal.
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/documents?processingStatus=NONE,ERLEDIGT") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/{id}")
    inner class GetDocumentById {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/{id}/file")
    inner class GetDocumentFile {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/invalid/file") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/1/file")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/documents/{id}")
    inner class DeleteDocument {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/documents/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/documents/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/statistics")
    inner class GetStatistics {
        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/statistics") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/statistics")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/documents/{id}/metadata")
    inner class UpdateMetadata {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/documents/invalid/metadata") {
                        bearerAuth(token)
                        setBody(TextContent("""{"metadata": "test"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/documents/1/metadata") {
                        setBody(TextContent("""{"metadata": "test"}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/document-types")
    inner class GetDocumentTypes {
        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/document-types") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/document-types")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/{id}/linked")
    inner class GetLinkedDocuments {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/invalid/linked") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/1/linked")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("DELETE /api/documents/link/{linkId}")
    inner class DeleteDocumentLink {
        @Test
        fun `returns 400 for invalid link ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.delete("/api/documents/link/invalid") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.delete("/api/documents/link/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/unlinked")
    inner class GetUnlinkedDocuments {
        @Test
        fun `returns 200 with valid auth`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/unlinked") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/unlinked")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/documents/{id}/bank-statement-metadata")
    inner class UpdateBankStatementMetadata {
        @Test
        fun `returns 400 for invalid ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/documents/invalid/bank-statement-metadata") {
                        bearerAuth(token)
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/documents/1/bank-statement-metadata") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/export/datev-ap")
    inner class ExportDatevAp {
        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/export/datev-ap?endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/export/datev-ap?startDate=2026-01-01") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/export/datev-ap?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/documents/export/datev-ap/statistics")
    inner class GetDatevApStatistics {
        @Test
        fun `returns 400 when startDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/export/datev-ap/statistics?endDate=2026-12-31") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when endDate missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/documents/export/datev-ap/statistics?startDate=2026-01-01") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/documents/export/datev-ap/statistics?startDate=2026-01-01&endDate=2026-12-31")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 200 with excludeKassenbuchLinked=false`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get(
                        "/api/documents/export/datev-ap/statistics?startDate=2026-01-01&endDate=2026-12-31&excludeKassenbuchLinked=false",
                    ) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 200 with excludeKassenbuchLinked=true`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get(
                        "/api/documents/export/datev-ap/statistics?startDate=2026-01-01&endDate=2026-12-31&excludeKassenbuchLinked=true",
                    ) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }
}
