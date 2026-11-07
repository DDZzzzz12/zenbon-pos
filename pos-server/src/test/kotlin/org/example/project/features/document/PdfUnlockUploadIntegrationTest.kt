package org.example.project.features.document

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Uploading a password-protected PDF with `unlockPassword` stores an unlocked copy; without the field
 * it is stored exactly as sent. The password must never decide anything but the unlock.
 */
@DisplayName("Document upload — unlocking password-protected PDFs")
class PdfUnlockUploadIntegrationTest {
    private fun pdf(userPassword: String?): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            if (userPassword != null) {
                val policy = StandardProtectionPolicy("owner-secret", userPassword, AccessPermission())
                policy.encryptionKeyLength = 256
                doc.protect(policy)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun temporaryStorageRoot(): File = File(System.getProperty("java.io.tmpdir"), "pdf-unlock-upload-${System.nanoTime()}").also { it.mkdirs() }

    private suspend fun useTemporaryStorage(
        client: HttpClient,
        root: File,
    ) {
        val token = TestAuthHelper.loginAsAdmin(client)
        val body =
            buildJsonObject {
                put("path", root.absolutePath)
                put("maxUploadSizeMb", 20)
            }.toString()
        val response =
            client.put("/api/v1/config/1/document-storage") {
                bearerAuth(token)
                setBody(TextContent(body, ContentType.Application.Json))
            }
        assertEquals(HttpStatusCode.OK, response.status, "document-storage config must be accepted")
        ShopConfigurationManager.refreshConfiguration()
    }

    /** [unlockPassword] null = the field is not sent at all. */
    private suspend fun upload(
        client: HttpClient,
        token: String,
        bytes: ByteArray,
        unlockPassword: String?,
    ): HttpResponse =
        client.post("/api/documents/upload") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("fileType", "INVOICE")
                        unlockPassword?.let { append("unlockPassword", it) }
                        append(
                            key = "file",
                            value = bytes,
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

    private suspend fun storedBytes(
        client: HttpClient,
        token: String,
        id: Long,
    ): ByteArray = client.get("/api/documents/$id/file") { bearerAuth(token) }.readRawBytes()

    private fun withApp(block: suspend (HttpClient, String) -> Unit) {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                block(client, TestAuthHelper.loginAsManager(client))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the right password stores an unlocked copy that opens without one`() =
        withApp { client, token ->
            val response = upload(client, token, pdf("s3cret"), unlockPassword = "s3cret")

            assertEquals(HttpStatusCode.Created, response.status)
            val stored = storedBytes(client, token, response.body<DocumentDto.DocumentResponseDto>().id)
            assertFalse(String(stored, Charsets.ISO_8859_1).contains("/Encrypt"))
            assertEquals(1, Loader.loadPDF(stored).use { it.numberOfPages })
        }

    @Test
    fun `a wrong password is refused with 422 and stores nothing, so the retry is not a duplicate`() =
        withApp { client, token ->
            val locked = pdf("s3cret")

            val refused = upload(client, token, locked, unlockPassword = "nope")
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            assertTrue(refused.bodyAsText().contains("PDF_PASSWORD_INCORRECT"))
            assertFalse(refused.bodyAsText().contains("nope"), "the password must not be echoed")

            val retry = upload(client, token, locked, unlockPassword = "s3cret")
            assertEquals(HttpStatusCode.Created, retry.status)
        }

    @Test
    fun `an empty password on a PDF that needs one is refused as required`() =
        withApp { client, token ->
            val response = upload(client, token, pdf("s3cret"), unlockPassword = "")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("PDF_PASSWORD_REQUIRED"))
        }

    @Test
    fun `an owner-password-only PDF unlocks with an empty password`() =
        withApp { client, token ->
            val response = upload(client, token, pdf(""), unlockPassword = "")

            assertEquals(HttpStatusCode.Created, response.status)
            val stored = storedBytes(client, token, response.body<DocumentDto.DocumentResponseDto>().id)
            assertFalse(String(stored, Charsets.ISO_8859_1).contains("/Encrypt"))
        }

    @Test
    fun `without the field a locked PDF is stored exactly as sent`() =
        withApp { client, token ->
            val locked = pdf("s3cret")

            val response = upload(client, token, locked, unlockPassword = null)

            assertEquals(HttpStatusCode.Created, response.status)
            val stored = storedBytes(client, token, response.body<DocumentDto.DocumentResponseDto>().id)
            assertTrue(stored.contentEquals(locked), "declining must keep the original bytes")
            val failure = runCatching { Loader.loadPDF(stored) }.exceptionOrNull()
            assertTrue(failure is InvalidPasswordException, "still needs its password; got $failure")
        }

    @Test
    fun `a password sent with a PDF that is not encrypted is ignored`() =
        withApp { client, token ->
            val plain = pdf(null)

            val response = upload(client, token, plain, unlockPassword = "unused")

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(storedBytes(client, token, response.body<DocumentDto.DocumentResponseDto>().id).contentEquals(plain))
        }

    @Test
    fun `an encrypted ZUGFeRD PDF is parsed as an e-invoice once unlocked`() =
        withApp { client, token ->
            val encrypted =
                Loader
                    .loadPDF(
                        checkNotNull(javaClass.getResourceAsStream("/corpus/zugferd-sample.pdf")).use { it.readBytes() },
                    ).use { doc ->
                        val policy = StandardProtectionPolicy("owner-secret", "s3cret", AccessPermission())
                        policy.encryptionKeyLength = 256
                        doc.protect(policy)
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }

            val response = upload(client, token, encrypted, unlockPassword = "s3cret")

            assertEquals(HttpStatusCode.Created, response.status)
            val id = response.body<DocumentDto.DocumentResponseDto>().id
            transaction {
                val invoice = DocumentRepositoryImpl().getInvoiceBySourceDocument(id)
                assertEquals("EINVOICE_XML", invoice?.extractionSource, "the unlocked bytes must reach the parser")
            }
        }

    @Test
    fun `the password never reaches the logs, whether the unlock succeeds or is refused`() =
        withApp { client, token ->
            val secret = "zq-unique-pw-7391"
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                upload(client, token, pdf(secret), unlockPassword = secret)
                upload(client, token, pdf("other"), unlockPassword = secret)
            } finally {
                root.detachAppender(appender)
            }

            val logged = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
            assertFalse(logged.contains(secret), "the password must not appear in any log line")
        }
}
