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
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Document
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Uploading a document parses any structured e-invoice payload it carries, inline, on the upload.
 *
 * This is the integration point rather than the parser's own suite: `:accounting-einvoice` already proves it
 * can read these very fixtures, so what is pinned here is that the upload path actually *calls* it,
 * that the parsed invoice lands in the normalized model under `EINVOICE_XML` provenance, and — the
 * two cases that matter most operationally — that a plain scan and a broken payload both still
 * produce a stored document.
 *
 * The client-declared part `Content-Type` is deliberately `application/octet-stream` throughout,
 * which is what a browser sends for an unrecognised extension. If the upload path ever trusted that
 * value instead of sniffing the bytes, a real ZUGFeRD invoice would be filed as a plain scan with no
 * error anywhere, and these tests would go red rather than silently losing the invoice.
 */
@DisplayName("Document upload — structured e-invoice payloads are parsed inline")
class EInvoiceUploadIntegrationTest {
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
        fixtureName: String,
        legacyFields: Map<String, String> = emptyMap(),
    ): HttpResponse =
        client.post("/api/documents/upload") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("fileType", "INVOICE")
                        legacyFields.forEach { (name, value) -> append(name, value) }
                        append(
                            key = "file",
                            value = fixture(fixtureName),
                            headers =
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fixtureName\"")
                                },
                        )
                    },
                ),
            )
        }

    /** Uploads [fixtureName] and returns the stored document's id. */
    private suspend fun uploadAndGetDocumentId(
        client: HttpClient,
        fixtureName: String,
        legacyFields: Map<String, String> = emptyMap(),
    ): Long {
        val token = TestAuthHelper.loginAsManager(client)
        val response = upload(client, token, fixtureName, legacyFields)
        assertEquals(
            HttpStatusCode.Created,
            response.status,
            "uploading $fixtureName must succeed",
        )
        return response.body<DocumentDto.DocumentResponseDto>().id
    }

    private fun temporaryStorageRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "einvoice-upload-${System.nanoTime()}")
        root.mkdirs()
        return root
    }

    @Test
    fun `uploading an XRechnung UBL XML writes the invoice with EINVOICE_XML provenance`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId = uploadAndGetDocumentId(client, "xrechnung-ubl.xml")

                transaction {
                    val invoice =
                        assertNotNull(
                            repository.getInvoiceBySourceDocument(documentId),
                            "the uploaded XRechnung must have produced an invoice",
                        )
                    assertEquals("EINVOICE_XML", invoice.extractionSource)
                    assertEquals("ubl", invoice.eInvoiceFormat)
                    // BT-3 survives the whole upload path: the KoSIT fixture states 380.
                    assertEquals("380", invoice.invoiceTypeCode)
                    assertTrue(
                        invoice.vatBreakdown.count() > 0,
                        "the VAT breakdown must not be empty",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `uploading a ZUGFeRD PDF parses the invoice out of its embedded attachment`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId = uploadAndGetDocumentId(client, "zugferd-sample.pdf")

                transaction {
                    val invoice =
                        assertNotNull(
                            repository.getInvoiceBySourceDocument(documentId),
                            "the PDF attachment path must reach the parser",
                        )
                    assertEquals("EINVOICE_XML", invoice.extractionSource)
                    assertTrue(
                        invoice.vatBreakdown.count() > 0,
                        "the VAT breakdown must not be empty",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The mixed upload: a structured payload *and* hand-typed invoice fields in one request.
     *
     * Stage 4 made the typed fields inert. `uploadDocument` no longer creates a legacy
     * `document_invoice_metadata` row, so `LegacyInvoiceSync` never runs on the upload path and
     * `ParsedInvoiceWriter` takes its *create* leg rather than finding a `MANUAL` row to update.
     *
     * The scenario this test guarded before — a parsed invoice left labelled `MANUAL` because the
     * legacy sync created the row first — cannot occur any more, because nothing creates that row.
     * What it guards now is the replacement contract: typed fields from a client that has not been
     * updated are **ignored, not rejected and not stored**, and the supplier's own XML is the only
     * thing that reaches the invoice. See the plan's "invoice fields from an old client are silently
     * ignored" decision for why a 400 was rejected.
     */
    @Test
    fun `typed invoice fields on an upload are ignored and the XML alone reaches the invoice`() {
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
                        "zugferd-sample.pdf",
                        legacyFields =
                            mapOf(
                                "vendorName" to "Falsch Getippt GmbH",
                                "invoiceNumber" to "TYPO-999",
                                "grossAmount" to "1.00",
                            ),
                    )

                transaction {
                    val invoice =
                        assertNotNull(
                            repository.getInvoiceBySourceDocument(documentId),
                            "the parser must still create the invoice when typed fields are present",
                        )

                    assertEquals(
                        "EINVOICE_XML",
                        invoice.extractionSource,
                        "nothing stamps MANUAL any more: the parser is the only writer on this path",
                    )
                    assertEquals("RE-2026-0001", invoice.invoiceNumber, "the XML number, not the typed one")
                    assertEquals("Musterlieferant GmbH", invoice.sellerName, "the XML vendor, not the typed one")
                    assertEquals("DE123456789", invoice.sellerVatId)
                    assertEquals(
                        0,
                        BigDecimal("119.00").compareTo(assertNotNull(invoice.taxInclusiveTotal)),
                        "the XML total, not the typed 1.00",
                    )

                    assertNull(
                        repository.getInvoiceMetadata(documentId),
                        "upload must create no legacy invoice metadata row at all",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `uploading a plain scanned PDF stores the document and creates no invoice`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId = uploadAndGetDocumentId(client, "plain-scan.pdf")

                transaction {
                    assertNotNull(repository.findById(documentId), "the document must still be stored")
                    assertNull(
                        repository.getInvoiceBySourceDocument(documentId),
                        "a scan carries no structured payload, so no invoice may be invented for it",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a payload that cannot be parsed does not fail the upload`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                // Broken XML: the parser returns Failure, which must be logged and swallowed.
                val documentId = uploadAndGetDocumentId(client, "malformed.xml")

                transaction {
                    assertNotNull(
                        repository.findById(documentId),
                        "the document must be stored even though its payload could not be parsed",
                    )
                    assertNull(
                        repository.getInvoiceBySourceDocument(documentId),
                        "a failed parse must not leave a half-written invoice behind",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The upload's own response must report what the parse found.
     *
     * Every other test here reads the database after the fact, which is why this shipped broken: the
     * response DTO was built *before* the parsers ran, so it always said `processingStatus=NONE` with
     * no invoice attached — even for a file the server had just read perfectly. An uploader was told
     * "no e-invoice" every single time, and the truth was only visible to someone who fetched the
     * document again afterwards.
     *
     * Asserted on the response body rather than the row, because the row was already correct.
     */
    @Test
    fun `the upload response reports the parsed invoice, not the pre-parse snapshot`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val token = TestAuthHelper.loginAsManager(client)

                val response = upload(client, token, "xrechnung-ubl.xml")
                assertEquals(HttpStatusCode.Created, response.status)
                val dto: DocumentDto.DocumentResponseDto = response.body()

                assertEquals(ProcessingStatus.COMPLETED, dto.processingStatus, "the response must carry the parse outcome")
                val invoice = assertNotNull(dto.invoiceMetadata, "the extracted invoice must reach the uploader")
                assertEquals("123456XX", invoice.invoiceNumber)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** A scan carries nothing structured, and the response must say so plainly rather than by omission. */
    @Test
    fun `the upload response reports NONE for a plain scan`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val token = TestAuthHelper.loginAsManager(client)

                val dto: DocumentDto.DocumentResponseDto = upload(client, token, "plain-scan.pdf").body()

                assertEquals(ProcessingStatus.NONE, dto.processingStatus)
                assertNull(dto.invoiceMetadata, "a scan carries no invoice to report")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** A payload that broke the parser must reach the uploader as FAILED, not as a silent NONE. */
    @Test
    fun `the upload response reports FAILED for an unparseable payload`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val token = TestAuthHelper.loginAsManager(client)

                val dto: DocumentDto.DocumentResponseDto = upload(client, token, "malformed.xml").body()

                assertEquals(ProcessingStatus.FAILED, dto.processingStatus)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a parsed e-invoice marks the document COMPLETED`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId = uploadAndGetDocumentId(client, "xrechnung-ubl.xml")

                // Separate transaction from the upload: reading back inside the writing one
                // would hit Exposed's identity map rather than the stored row.
                transaction {
                    assertEquals(
                        "COMPLETED",
                        Document.findById(documentId)!!.processingStatus,
                        "a document read from a structured e-invoice must report COMPLETED",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a plain scan keeps the NONE default`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId = uploadAndGetDocumentId(client, "plain-scan.pdf")

                // NoEInvoiceFound deliberately writes nothing — NONE is the column default and
                // the normal scanned-invoice case. This is a regression guard for that branch.
                transaction {
                    assertEquals(
                        "NONE",
                        Document.findById(documentId)!!.processingStatus,
                        "a plain scan must be left at the NONE default",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a payload that cannot be parsed marks the document FAILED`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                // uploadAndGetDocumentId asserts 201, so this also re-pins that a broken
                // payload still stores the document.
                val documentId = uploadAndGetDocumentId(client, "malformed.xml")

                transaction {
                    assertEquals(
                        "FAILED",
                        Document.findById(documentId)!!.processingStatus,
                        "a broken structured payload must report FAILED, not NONE",
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The spec's explicit Stage-4 assertion: *"an upload with invoice fields omitted still stores
     * the document and still parses"*.
     *
     * It is not covered by `uploading a ZUGFeRD PDF parses the invoice out of its embedded
     * attachment` — that one predates the change and does not assert the *absence* of the legacy
     * row, which is the whole point of Stage 4. Both legs matter: dropping the `"INVOICE"` arm must
     * not disturb the document write (which happens before it) or the parse (which happens after).
     */
    @Test
    fun `an upload carrying no invoice fields still stores the document and still parses`() {
        val root = temporaryStorageRoot()
        try {
            testApp(setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
                TestData.createManager()
            }) { client ->
                useTemporaryStorage(client, root)
                val documentId = uploadAndGetDocumentId(client, "zugferd-sample.pdf")

                transaction {
                    assertNotNull(repository.findById(documentId), "the document row must still be written")
                    assertNull(
                        repository.getInvoiceMetadata(documentId),
                        "no legacy metadata is created when the client sends no invoice fields",
                    )

                    val invoice =
                        assertNotNull(
                            repository.getInvoiceBySourceDocument(documentId),
                            "the parser must still reach the embedded payload",
                        )
                    assertEquals("EINVOICE_XML", invoice.extractionSource)
                    assertTrue(invoice.vatBreakdown.count() > 0, "the VAT breakdown must not be empty")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
