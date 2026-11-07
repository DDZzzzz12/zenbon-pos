package org.example.project.features.document

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Document
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * An MT940 statement must reach the parser, and nothing else may ride in behind it.
 *
 * MT940 is plain text with no magic bytes, so before this gate learned the format a `.sta`
 * download was refused with 415 and `:accounting-banking`'s MT940 parser was unreachable in production, no
 * matter how complete it was. The fix is a content sniff on the SWIFT tag structure, and the risk
 * a content sniff carries is that it widens the accepted-upload set — a validation designed to
 * reject arbitrary files would then admit any readable text.
 *
 * So the suite pins three things:
 * - a `.sta` upload is parsed and its statement stored;
 * - the stored `mediaType` names MT940. This is what distinguishes "the sniff ran" from "the file
 *   slipped through as `application/octet-stream`" — both would store a document, only one proves
 *   the format was recognised;
 * - a plain German letter is still rejected. The sniff must recognise MT940, not text.
 *
 * `mt940-statement.sta` is a copy of `:accounting-banking`'s hand-authored `sparkasse-mt940.sta`, chosen
 * because its balances reconcile (1234.56 → 2394.16) and it therefore actually stores; the
 * `real-` prefixed fixtures in that corpus mostly fail on purpose.
 */
@DisplayName("Document upload — MT940 passes the file gate")
class Mt940UploadGateTest {
    private val repository = DocumentRepositoryImpl()

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/corpus/$name")) {
            "missing test fixture /corpus/$name"
        }.use { it.readBytes() }

    /**
     * Points document storage at a throwaway directory. Without this the upload writes into the
     * developer's real application-data folder, because that is `ShopConfigurationManager`'s
     * fallback when no path is configured.
     */
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

    private suspend fun upload(
        client: HttpClient,
        token: String,
        filename: String,
        bytes: ByteArray,
        fileType: String,
    ): HttpResponse =
        client.post("/api/documents/upload") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("fileType", fileType)
                        append(
                            key = "file",
                            value = bytes,
                            headers =
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                },
                        )
                    },
                ),
            )
        }

    /** Uploads [bytes] under [filename] and returns the stored document's id. */
    private suspend fun uploadAndGetDocumentId(
        client: HttpClient,
        filename: String,
        bytes: ByteArray,
        fileType: String = "BANK_STATEMENT",
    ): Long {
        val token = TestAuthHelper.loginAsManager(client)
        val response = upload(client, token, filename, bytes, fileType)
        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "uploading $filename must succeed",
        )
        return response.body<DocumentDto.DocumentResponseDto>().id
    }

    private fun temporaryStorageRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "mt940-upload-${System.nanoTime()}")
        root.mkdirs()
        return root
    }

    @Test
    fun `an sta upload is accepted and its statement is parsed and stored`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(client, "auszug.sta", fixture("mt940-statement.sta"))

                // A separate transaction from the upload's: reading back inside the writing one
                // would hit Exposed's identity map rather than the stored rows.
                transaction {
                    val statement =
                        assertNotNull(
                            repository.getBankStatementBySourceDocument(documentId),
                            "the uploaded MT940 file must have produced a statement",
                        )
                    assertEquals("mt940", statement.format)
                    assertEquals("DE02120300000000202051", statement.accountIban)
                    assertEquals(
                        4,
                        repository.getTransactions(statement.id.value).size,
                        "all four entries of the fixture must be stored",
                    )
                    assertEquals(
                        "COMPLETED",
                        Document.findById(documentId)!!.processingStatus,
                        "a document read as a bank statement must report COMPLETED",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the stored media type identifies MT940 rather than octet-stream`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(client, "auszug.sta", fixture("mt940-statement.sta"))

                transaction {
                    assertEquals(
                        "application/x-swift-mt940",
                        Document.findById(documentId)!!.mediaType,
                        "the sniff must name the format; octet-stream would mean the file merely slipped through",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a plain text file is still rejected`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val token = TestAuthHelper.loginAsManager(client)
                val prose =
                    (
                        "Sehr geehrte Damen und Herren,\r\n\r\n" +
                            "anbei erhalten Sie die Unterlagen zu Ihrer Anfrage vom 3. Februar 2026.\r\n" +
                            "Mit freundlichen Gruessen\r\n"
                    ).toByteArray(Charsets.ISO_8859_1)
                val response = upload(client, token, "brief.txt", prose, "CONTRACTS")

                assertNotEquals(
                    HttpStatusCode.Created,
                    response.status,
                    "the gate must not widen to arbitrary text files",
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
