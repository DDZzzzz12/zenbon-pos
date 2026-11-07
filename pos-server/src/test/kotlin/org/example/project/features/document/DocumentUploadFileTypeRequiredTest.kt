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
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.example.project.core.error.ProblemDetail
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * `fileType` used to default to "CUSTOM", which is not a [org.example.project.core.domain.enums.document.DocumentType],
 * so an upload that left it out failed later with "Invalid fileType: CUSTOM". It is now required and refused up front.
 */
@DisplayName("Document upload — fileType is required")
class DocumentUploadFileTypeRequiredTest {
    @Test
    fun `an upload without fileType is a 400 problem+json saying so`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.post("/api/documents/upload") {
                    bearerAuth(token)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    key = "file",
                                    value = "hello".encodeToByteArray(),
                                    headers =
                                        Headers.build {
                                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                            append(HttpHeaders.ContentDisposition, "filename=\"a.txt\"")
                                        },
                                )
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ContentType.Application.ProblemJson.contentType, response.contentType()?.contentType)
            val problem = Json { ignoreUnknownKeys = true }.decodeFromString<ProblemDetail>(response.bodyAsText())
            assertContains(problem.detail.orEmpty(), "fileType is required")
        }
}
