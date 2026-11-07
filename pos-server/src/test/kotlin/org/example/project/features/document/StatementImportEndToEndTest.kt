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
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The media-type sniff, the parser, the writer, the duplicate marker and the DTO repoint each have
 * their own test. This one proves they compose: a real bank file goes in through the actual upload
 * route, and the client sees its transactions.
 *
 * Only two things are reachable end to end and nowhere else, so they are the point of this class:
 * the parsed counterparty surviving parser → writer → mapper → DTO (what the reconciliation work
 * will read), and overlap/duplicate marking happening across two real HTTP uploads rather than two
 * direct writer calls.
 *
 * The fixtures are the corpus copies used by [BankStatementUploadIntegrationTest] and
 * [Mt940UploadGateTest]; their provenance is recorded once in `accounting/banking/src/test/resources/corpus/
 * README.md`.
 */
@DisplayName("Bank statement import — end to end through the upload route")
class StatementImportEndToEndTest {
    private val repository = DocumentRepositoryImpl()

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/corpus/$name")) {
            "missing test fixture /corpus/$name"
        }.use { it.readBytes() }

    private fun documentService(): DocumentManagementService = GlobalContext.get().get()

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
        val root = File(System.getProperty("java.io.tmpdir"), "statement-e2e-${System.nanoTime()}")
        root.mkdirs()
        return root
    }

    @Test
    fun `a real camt file imports end to end and the client sees its transactions`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(client, "kontoauszug.xml", fixture("camt053-statement.xml"))

                transaction {
                    val statement =
                        assertNotNull(
                            repository.getBankStatementBySourceDocument(documentId),
                            "the uploaded CAMT file must have produced a statement",
                        )
                    assertEquals("camt053", statement.format)
                    assertEquals(
                        "COMPLETED",
                        Document.findById(documentId)!!.processingStatus,
                        "a document read as a bank statement must report COMPLETED",
                    )
                }

                // The client's view: the same service the detail route calls.
                val metadata =
                    assertNotNull(
                        documentService().getDocumentById(documentId).bankStatementMetadata,
                        "the parsed statement must reach the document DTO",
                    )
                assertEquals("DE87200500001234567890", metadata.iban)
                assertEquals(
                    0,
                    BigDecimal(assertNotNull(metadata.openingBalance)).compareTo(BigDecimal("112.72")),
                    "the stated opening balance must survive to the DTO",
                )
                assertEquals(
                    0,
                    BigDecimal(assertNotNull(metadata.closingBalance)).compareTo(BigDecimal("158780.32")),
                    "the stated closing balance must survive to the DTO",
                )

                val entries = assertNotNull(metadata.transactions, "the client must see the parsed entries")
                assertTrue(entries.isNotEmpty(), "the client must see the parsed entries")
                assertTrue(
                    entries.any { !it.counterpartyName.isNullOrBlank() },
                    "counterparty data must survive parser, writer, mapper and DTO — reconciliation reads it",
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a real MT940 file imports end to end`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(client, "kontoauszug.sta", fixture("mt940-statement.sta"))

                transaction {
                    val statement =
                        assertNotNull(
                            repository.getBankStatementBySourceDocument(documentId),
                            "the uploaded MT940 file must have produced a statement",
                        )
                    assertEquals("mt940", statement.format)
                    assertEquals(
                        4,
                        repository.getTransactions(statement.id.value).size,
                        "all four :61:/:86: pairs of the fixture must be stored",
                    )
                    assertEquals(
                        "COMPLETED",
                        Document.findById(documentId)!!.processingStatus,
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a byte-identical re-upload is rejected before any parsing happens`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val bytes = fixture("camt053-statement.xml")
                uploadAndGetDocumentId(client, "januar.xml", bytes)

                val token = TestAuthHelper.loginAsManager(client)
                val second = upload(client, token, "januar-kopie.xml", bytes, "BANK_STATEMENT")
                assertNotEquals(
                    HttpStatusCode.Created,
                    second.status,
                    "the content-hash check must reject identical bytes before the parser runs",
                )

                transaction {
                    assertEquals(
                        1,
                        BankStatement
                            .find { BankStatementTable.accountIban eq "DE87200500001234567890" }
                            .count(),
                        "a rejected upload must not have produced a second statement for the account",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The CAMT fixture is here precisely *because* it carries no `FrToDt`: its statement has no
     * stated period, so the overlap decision has to fall back to the dates of its entries. That is
     * the shape a real German export takes, and the one a period-only comparison silently ignored.
     */
    @Test
    fun `an overlapping re-export of a CAMT file with no stated period is still marked`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val bytes = fixture("camt053-statement.xml")
                // A comment in the XML epilog is legal and changes nothing about the parse, but it
                // changes the bytes — so the content-hash check does not catch this as a re-upload.
                val reExport = (String(bytes, Charsets.UTF_8) + "\n<!-- re-export -->\n").toByteArray(Charsets.UTF_8)
                assertFalse(
                    bytes.contentEquals(reExport),
                    "the re-export must differ in its bytes, or this would silently test the duplicate-content path",
                )

                val firstDocumentId = uploadAndGetDocumentId(client, "kontoauszug.xml", bytes)
                val secondDocumentId = uploadAndGetDocumentId(client, "kontoauszug-nachtrag.xml", reExport)

                transaction {
                    val first = assertNotNull(repository.getBankStatementBySourceDocument(firstDocumentId))
                    val second =
                        assertNotNull(
                            repository.getBankStatementBySourceDocument(secondDocumentId),
                            "the re-export must be stored too — nothing is dropped for overlapping",
                        )
                    assertNull(
                        second.periodStart,
                        "the fixture states no period, and no derived period may be written back",
                    )
                    assertEquals(
                        first.id.value,
                        second.overlappingStatement?.id?.value,
                        "the entry dates must decide the overlap when the file states no period",
                    )

                    val firstRows = repository.getTransactions(first.id.value)
                    val secondRows = repository.getTransactions(second.id.value)
                    assertTrue(secondRows.isNotEmpty(), "the fixture must contribute entries to compare")
                    assertEquals(firstRows.size, secondRows.size, "a repeated entry is marked, never removed")
                    assertEquals(
                        secondRows.size,
                        secondRows.count { it.duplicateOfTransaction != null },
                        "a full re-import repeats every entry, so every entry must be marked",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Runs on MT940 as well as the CAMT case above: MT940 derives the period from `:60F:`/`:62F:`,
     * so this one exercises the stated-period path through a different parser.
     */
    @Test
    fun `an overlapping later export has its repeated transactions marked but keeps them all`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val bytes = fixture("mt940-statement.sta")
                // A different file carrying the same statement: SWIFT's end-of-message marker changes
                // the bytes — so the content-hash check does not catch it — without changing the
                // parse, since the tokenizer stops at a lone `-`.
                val reExport = (String(bytes, Charsets.UTF_8) + "\n-\n").toByteArray(Charsets.UTF_8)
                assertFalse(
                    bytes.contentEquals(reExport),
                    "the re-export must differ in its bytes, or this would silently test the duplicate-content path",
                )

                val firstDocumentId = uploadAndGetDocumentId(client, "februar.sta", bytes)
                val secondDocumentId = uploadAndGetDocumentId(client, "februar-nachtrag.sta", reExport)

                transaction {
                    val first =
                        assertNotNull(
                            repository.getBankStatementBySourceDocument(firstDocumentId),
                            "the first upload must keep its own statement",
                        )
                    val second =
                        assertNotNull(
                            repository.getBankStatementBySourceDocument(secondDocumentId),
                            "the re-export must be stored too — nothing is dropped for overlapping",
                        )
                    assertEquals(
                        first.id.value,
                        second.overlappingStatement?.id?.value,
                        "the later statement must point at the one it overlaps",
                    )

                    val firstRows = repository.getTransactions(first.id.value)
                    val secondRows = repository.getTransactions(second.id.value)
                    assertEquals(
                        firstRows.size,
                        secondRows.size,
                        "a repeated entry is marked, never removed",
                    )
                    assertEquals(
                        secondRows.size,
                        secondRows.count { it.duplicateOfTransaction != null },
                        "a full re-import repeats every entry, so every entry must be marked",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
