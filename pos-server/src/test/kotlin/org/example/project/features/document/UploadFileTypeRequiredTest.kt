package org.example.project.features.document

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `fileType` has no default: `CUSTOM` is not a built-in document type, so the old fallback to it
 * always ended in a confusing "Invalid fileType: CUSTOM". An upload without it is refused up front.
 */
@DisplayName("Document upload: fileType is required")
class UploadFileTypeRequiredTest {
    @Test
    fun `an upload without fileType is refused with a clear 400`() =
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
            TestData.createManager()
        }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.post("/api/documents/upload") {
                    bearerAuth(token)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    key = "file",
                                    value = ByteArray(16) { 1 },
                                    headers =
                                        Headers.build {
                                            append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                                            append(HttpHeaders.ContentDisposition, "filename=\"scan.pdf\"")
                                        },
                                )
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("fileType is required"), body)
            assertTrue(!body.contains("CUSTOM"), body)
        }
}
