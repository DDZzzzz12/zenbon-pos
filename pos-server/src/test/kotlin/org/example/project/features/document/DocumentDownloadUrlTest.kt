package org.example.project.features.document

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.features.document.domain.mapper.toSummaryDto
import org.example.project.features.document.domain.models.Document
import org.example.project.infrastructure.security.RoutePermissions
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The download link a document response advertises must be one the server actually serves.
 *
 * `DocumentMapper` wrote `downloadUrl = "/api/v1/pdfs/{id}/file"` in both the detail and the
 * summary DTO, while the file route is `GET /api/documents/{id}/file`. Following the advertised
 * link for document 383 logged *"No permission rule for GET /api/v1/pdfs/383/file — denied"* and
 * answered 403 — a wrong path disguised as a permissions problem (E2E-2026-09-20 round 2, R2-2).
 * No client in the repo read the field, which is why it went unnoticed.
 *
 * The file itself does not exist in this test, deliberately: writing a real one would touch the
 * document storage directory. A missing file is the route's own 404; what must not happen is the
 * authorization plugin's 403 for a path it has no rule for.
 */
@DisplayName("Document download URL — the advertised link is a routed path")
class DocumentDownloadUrlTest {
    @Test
    fun `the detail response advertises a URL the server routes`() {
        var documentId = 0L
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                documentId =
                    transaction {
                        Document
                            .new {
                                this.filename = "Rechnung_2026-09-21_74fb.xml"
                                this.fileType = "INVOICE"
                                this.filePath = "/nonexistent/Rechnung_2026-09-21_74fb.xml"
                                this.fileSize = 1024L
                                this.uploadedAt = currentTimeStamp()
                                this.uploadedBy = admin
                                this.isActive = true
                                this.contentHash = "hash-download-url"
                                this.mediaType = "application/xml"
                            }.id.value
                    }
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val detail = client.get("/api/documents/$documentId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, detail.status)
            val url =
                Json
                    .parseToJsonElement(detail.bodyAsText())
                    .jsonObject["downloadUrl"]!!
                    .jsonPrimitive.content

            assertNotNull(
                RoutePermissions.findRule(url, HttpMethod.Get),
                "the advertised download URL '$url' has no route rule, so every call to it is denied",
            )
            val download = client.get(url) { bearerAuth(token) }
            assertNotEquals(
                HttpStatusCode.Forbidden,
                download.status,
                "following the advertised link must reach the file route, not be refused",
            )

            val summaryUrl = transaction { Document.findById(documentId)!!.toSummaryDto().downloadUrl }
            assertEquals(url, summaryUrl, "the summary DTO must advertise the same link as the detail DTO")
        }
    }
}
