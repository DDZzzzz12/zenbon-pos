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
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.document.StatementImportNotice
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.domain.models.Document
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Uploading a document parses any bank statement it carries, inline, on the upload.
 *
 * This is the integration point rather than `:accounting-banking`'s own suite: that module already proves it
 * can read these bytes. What is pinned here is that the upload path actually *calls* the parser,
 * that the statement and its entries land in the normalized model, and that a document with no
 * statement in it is still stored untouched.
 *
 * Two properties are load-bearing and each has a test of its own:
 * - Parsing keys off the **bytes**, never the client-declared `fileType`. A CAMT file filed under
 *   the wrong type is still parsed, the same doctrine that already applies to `Content-Type` — the
 *   part `Content-Type` here is deliberately `application/octet-stream`, which is what a browser
 *   sends for an unrecognised extension.
 * - A statement that does not reconcile is **not stored**. Plausibly-wrong booking data is worse
 *   than none, and the raw file is still on disk for an operator to look at.
 *
 * `camt053-statement.xml` is a copy of `:accounting-banking`'s `real-camt053-germany-dk.xml`, a real German DK
 * `camt.053.001.02` from [moiristo/camt](https://github.com/moiristo/camt) (MIT, © 2014 Reinier de
 * Lange). Provenance and licensing are recorded once, in `accounting/banking/src/test/resources/corpus/
 * README.md` — do not duplicate that record here.
 */
@DisplayName("Document upload — bank statements are parsed inline")
class BankStatementUploadIntegrationTest {
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
    ): Long = uploadAndGetResponse(client, filename, bytes, fileType).id

    /** Uploads [bytes] under [filename] and returns the upload response. */
    private suspend fun uploadAndGetResponse(
        client: HttpClient,
        filename: String,
        bytes: ByteArray,
        fileType: String = "BANK_STATEMENT",
    ): DocumentDto.DocumentResponseDto {
        val token = TestAuthHelper.loginAsManager(client)
        val response = upload(client, token, filename, bytes, fileType)
        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "uploading $filename must succeed",
        )
        return response.body<DocumentDto.DocumentResponseDto>()
    }

    private fun temporaryStorageRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "statement-upload-${System.nanoTime()}")
        root.mkdirs()
        return root
    }

    @Test
    fun `uploading a camt file stores the statement and its transactions and marks the document COMPLETED`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(client, "camt053-statement.xml", fixture("camt053-statement.xml"))

                // A separate transaction from the upload's: reading back inside the writing one
                // would hit Exposed's identity map rather than the stored rows.
                transaction {
                    val statement =
                        assertNotNull(
                            repository.getBankStatementBySourceDocument(documentId),
                            "the uploaded CAMT file must have produced a statement",
                        )
                    assertEquals("camt053", statement.format)
                    assertEquals("DE87200500001234567890", statement.accountIban)
                    assertEquals(
                        10,
                        repository.getTransactions(statement.id.value).size,
                        "all ten entries of the fixture must be stored",
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

    /**
     * T9-17: a statement for one of our own registered IBANs used to land under "Auszüge ohne Konto"
     * until a restart or "Zuordnung erneut prüfen" ran the resolver. It is assigned on upload now;
     * a statement for an unregistered IBAN still waits unassigned.
     */
    @Test
    fun `a statement for a registered own IBAN is assigned to that account on upload`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
                transaction {
                    BankAccount.new {
                        shop = ShopInfo.all().first()
                        iban = "DE87200500001234567890"
                        bankName = "Hausbank"
                        createdAt = currentTimeStamp()
                    }
                }
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(client, "camt053-statement.xml", fixture("camt053-statement.xml"))

                transaction {
                    val statement = assertNotNull(repository.getBankStatementBySourceDocument(documentId))
                    val account = BankAccount.find { BankAccountTable.iban eq "DE87200500001234567890" }.single()
                    assertEquals(
                        account.id.value,
                        statement.bankAccount?.id?.value,
                        "the statement must be under the registered account right after the upload",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a statement for an unregistered IBAN stays unassigned after upload`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(client, "camt053-statement.xml", fixture("camt053-statement.xml"))

                transaction {
                    val statement = assertNotNull(repository.getBankStatementBySourceDocument(documentId))
                    assertNull(statement.bankAccount, "an unknown IBAN is never auto-assigned or auto-created")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a statement whose balances do not reconcile is not stored and the document is marked FAILED`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                // Broken in the test rather than committed as a second fixture, so the two files
                // cannot drift: one entry amount is altered, leaving the stated closing balance
                // one euro away from the sum of the entries.
                val broken =
                    String(fixture("camt053-statement.xml"), Charsets.UTF_8)
                        .replace("<Amt Ccy=\"EUR\">100876.00</Amt>", "<Amt Ccy=\"EUR\">100875.00</Amt>")
                        .toByteArray(Charsets.UTF_8)
                val response = uploadAndGetResponse(client, "kaputt.xml", broken)
                val documentId = response.id

                // T5-9: the reason used to reach only the server log, while the import card said
                // "Beleg angehängt". The upload response now names it.
                assertEquals(
                    listOf(StatementImportNotice.BALANCES_DO_NOT_RECONCILE),
                    response.statementImportNotices,
                    "the operator must be told the statement was refused for its balances",
                )
                assertEquals(ProcessingStatus.FAILED, response.processingStatus)

                transaction {
                    assertNotNull(
                        Document.findById(documentId),
                        "the document must be stored even though its statement could not be read",
                    )
                    assertNull(
                        repository.getBankStatementBySourceDocument(documentId),
                        "a statement that does not reconcile must not be stored",
                    )
                    assertEquals(
                        "FAILED",
                        Document.findById(documentId)!!.processingStatus,
                        "a statement that does not reconcile must report FAILED, not NONE",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * T5-9: an overlapping import used to flag only its repeated bookings; the statement itself said
     * nothing. The upload response and the stored statement now both carry the overlap — and a
     * first import carries no notice at all.
     */
    @Test
    fun `an overlapping statement is flagged on the import result and on the statement itself`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val original = String(fixture("camt053-statement.xml"), Charsets.UTF_8)
                val first = uploadAndGetResponse(client, "camt-a.xml", original.toByteArray(Charsets.UTF_8))
                // Same period and entries, different bytes — a re-export, not a byte-identical file.
                val reExport = original.replace("<MsgId>27632364572</MsgId>", "<MsgId>27632364573</MsgId>")
                check(reExport != original) { "the fixture rewrite matched nothing" }
                val second = uploadAndGetResponse(client, "camt-b.xml", reExport.toByteArray(Charsets.UTF_8))

                assertEquals(emptyList(), first.statementImportNotices, "a first import overlaps nothing")
                assertEquals(
                    listOf(StatementImportNotice.OVERLAPS_EARLIER_STATEMENT),
                    second.statementImportNotices,
                )
                assertEquals(ProcessingStatus.COMPLETED, second.processingStatus, "an overlap is a hint, not a refusal")

                transaction {
                    val earlier = assertNotNull(repository.getBankStatementBySourceDocument(first.id))
                    val later = assertNotNull(repository.getBankStatementBySourceDocument(second.id))
                    assertEquals(earlier.id.value, later.readValues[BankStatementTable.overlappingStatementId]?.value)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a plain scanned PDF leaves the processing status untouched and stores no statement`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(
                        client,
                        "plain-scan.pdf",
                        fixture("plain-scan.pdf"),
                        fileType = "INVOICE",
                    )

                transaction {
                    assertNull(
                        repository.getBankStatementBySourceDocument(documentId),
                        "a scan carries no statement, so none may be invented for it",
                    )
                    assertEquals(
                        "NONE",
                        Document.findById(documentId)!!.processingStatus,
                        "NoStatementFound writes nothing: NONE is the column default",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * E2E 18.8: an e-invoice XML picked in "Auszug importieren" stored no statement — correct — but
     * the e-invoice parser marked it FAILED, and the Bankkonten card read that as "the statement
     * could not be read". The response now says what actually happened: the file declared as a bank
     * statement carries none. It is told apart from a statement that is present but unreadable.
     */
    @Test
    fun `a file uploaded as a bank statement that carries none says so on the response`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                // A broken CII invoice: the e-invoice parser FAILS it, the bank parser finds nothing.
                val brokenInvoice = uploadAndGetResponse(client, "malformed.xml", fixture("malformed.xml"))
                assertEquals(ProcessingStatus.FAILED, brokenInvoice.processingStatus, "the e-invoice half still reports its own failure")
                assertEquals(listOf("NOT_A_BANK_STATEMENT"), brokenInvoice.statementImportNotices.map { it.name })

                val scan = uploadAndGetResponse(client, "plain-scan.pdf", fixture("plain-scan.pdf"))
                assertEquals(listOf("NOT_A_BANK_STATEMENT"), scan.statementImportNotices.map { it.name })

                // Declared as something else, a file without a statement is simply not one — nothing to say.
                val invoiceUpload = uploadAndGetResponse(client, "plain-scan-2.pdf", fixture("plain-scan.pdf") + byteArrayOf(0x0A), fileType = "INVOICE")
                assertEquals(emptyList(), invoiceUpload.statementImportNotices)

                // A real statement that does not reconcile is still the other notice, not this one.
                val statement = uploadAndGetResponse(client, "camt053-statement.xml", fixture("camt053-statement.xml"))
                assertFalse(statement.statementImportNotices.any { it.name == "NOT_A_BANK_STATEMENT" })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a camt file is parsed even when the client declares the wrong document type`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId =
                    uploadAndGetDocumentId(
                        client,
                        "camt053-statement.xml",
                        fixture("camt053-statement.xml"),
                        // Deliberately wrong: a statement filed as a contract.
                        fileType = "CONTRACTS",
                    )

                transaction {
                    assertNotNull(
                        repository.getBankStatementBySourceDocument(documentId),
                        "parsing must key off the bytes, never the client-declared fileType",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
