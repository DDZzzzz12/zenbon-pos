package org.example.project.features.kassenbuch

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.document.data.service.DocumentManagementServiceImpl
import org.example.project.features.document.data.service.PdfGenerationServiceImpl
import org.example.project.features.document.domain.mapper.toSummaryDto
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.CashBookCloseService
import org.example.project.features.kassenbuch.domain.service.CashBookTransferService
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.features.zBon.mapper.zBonDisplayName
import org.example.project.infrastructure.errors.PosException
import org.example.project.pdf.generator.generateKassenmeldungPdfBytes
import org.example.project.pdf.generator.generateZBonReportPdfBytes
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins how the fiscal Kassenbuch and Z-Bon code reaches the accounting document services, per call
 * site: the Eigenbeleg and Z-Bon report arguments, whether each call runs inside the caller's
 * transaction (and which calls share one), the stored `Document` row and the entry it is linked to,
 * the text of the PDF on disk, the stored/regenerated Z-Bon download, the linked-document summary on
 * the entry detail, the "document not found" refusals and the Kassenmeldung PDF route.
 *
 * Everything runs on the production Koin graph of `testApp`. Only the two accounting services are
 * replaced, by recorders wrapping the real implementations that write into a temporary document
 * root, so the test does not depend on how the fiscal services receive them.
 */
@DisplayName("Kassenbuch and Z-Bon document call sites (characterisation)")
class KassenbuchDocumentsCharacterisationTest {
    private val deviceId = "kdc-device"
    private val fixture = TippedDayFixture(deviceId, "KDC")

    /** anna: 3.00 cash + 2.00 card tips; bert: 4.00 cash. The drawer holds 47.00 cash. */
    private val day = listOf(Sale("anna", "3.00"), Sale("anna", "2.00", byCard = true), Sale("bert", "4.00"))

    /** One call into an accounting document service. */
    private data class Call(
        val text: String,
        val inTransaction: Boolean,
        val transaction: Int?,
        var documentId: Long? = null,
    )

    private class Rig(
        val root: File,
    ) {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

        fun record(text: String): Call {
            val tx = TransactionManager.currentOrNull()
            return Call(text, tx != null, tx?.let { System.identityHashCode(it) }).also { calls += it }
        }

        fun snapshot(): List<Call> = synchronized(calls) { calls.toList() }
    }

    private class RecordingPdf(
        private val real: PdfGenerationService,
        private val rig: Rig,
    ) : PdfGenerationService by real {
        override suspend fun generateEigenbelegPdf(
            belegNr: String,
            date: String,
            entryType: String,
            amount: String,
            description: String,
            employeeId: UUID,
            correctsBelegNr: String?,
            correctsDate: String?,
            reason: String?,
        ): DocumentDto.DocumentResponseDto {
            val call =
                rig.record(
                    "eigenbeleg(belegNr=$belegNr, date=$date, entryType=$entryType, amount=$amount, description=$description, " +
                        "employeeId=$employeeId, correctsBelegNr=$correctsBelegNr, correctsDate=$correctsDate, reason=$reason)",
                )
            return real.generateEigenbelegPdf(belegNr, date, entryType, amount, description, employeeId, correctsBelegNr, correctsDate, reason).also {
                call.documentId = it.id
            }
        }

        override suspend fun generateZBonReportPdf(
            zBon: ZBonDto.ZBonResponseDto,
            employeeId: UUID,
            kassenbuchHeader: KassenbuchHeader?,
        ): DocumentDto.DocumentResponseDto {
            val call =
                rig.record(
                    "zBonReport(zbonNummer=${zBon.zbonNummer}, employeeId=$employeeId, " +
                        "kassenbuchHeader=${kassenbuchHeader?.let { "${it.entryType}/aggregate=${it.isZbonAggregate}" }})",
                )
            return real.generateZBonReportPdf(zBon, employeeId, kassenbuchHeader).also { call.documentId = it.id }
        }
    }

    private class RecordingDocuments(
        private val real: DocumentManagementService,
        private val rig: Rig,
    ) : DocumentManagementService by real {
        override suspend fun copyDocument(
            sourceDocumentId: Long,
            copiedBy: UUID,
        ): Long {
            val call = rig.record("copyDocument(source=$sourceDocumentId, copiedBy=$copiedBy)")
            return real.copyDocument(sourceDocumentId, copiedBy).also { call.documentId = it }
        }

        override suspend fun getDocumentFile(id: Long): File {
            rig.record("getDocumentFile(id=$id)")
            return real.getDocumentFile(id)
        }
    }

    @AfterEach
    fun reset() = BillNumberTracker.clearAll()

    // ── Z-Bon closing: in-closing tip payouts, drawer count, report ──────────────────────────────

    @Test
    fun `the closing books its Eigenbelege and the Z-Bon report inside the closing transaction`() =
        scenario { rig, client ->
            val response =
                fixture.closeWith(
                    client,
                    ZBonDto.CreateZBonRequestDto(
                        countedAmount = "37.00",
                        tipPayouts = listOf(ZBonDto.CreateTipPayoutRequestDto(anna(), "5.00"), ZBonDto.CreateTipPayoutRequestDto(bert(), "4.00")),
                    ),
                )
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val zBon = testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())

            val calls = rig.snapshot()
            assertEquals(
                listOf(
                    "eigenbeleg(belegNr=<YM>-0002, date=<TODAY>, entryType=Trinkgeld-Auszahlung, amount=-5,00 EUR, description=Auszahlung Trinkgeld (Arbeitnehmer) an <ANNA> im Kassenabschluss Z-Bon Nr. 1, employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                    "eigenbeleg(belegNr=<YM>-0003, date=<TODAY>, entryType=Trinkgeld-Auszahlung, amount=-4,00 EUR, description=Auszahlung Trinkgeld (Arbeitnehmer) an <BERT> im Kassenabschluss Z-Bon Nr. 1, employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                    "eigenbeleg(belegNr=<YM>-0004, date=<TODAY>, entryType=Kassendifferenz Minus, amount=-1,00 EUR, description=Kassenzählung zum <TODAY>: gezählt 37,00 EUR, Soll 38,00 EUR (Buch 0,00 + Bareinnahmen 47,00 - Trinkgeld-Auszahlung 9,00), employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                    "zBonReport(zbonNummer=1, employeeId=<ADMIN>, kassenbuchHeader=CASH_SALE/aggregate=true)",
                ),
                calls.map { normalise(it.text) },
            )
            assertTrue(calls.all { it.inTransaction }, "every document call runs inside the closing's transaction")
            assertEquals(1, calls.map { it.transaction }.distinct().size, "and all of them inside ONE transaction")
            assertStoredAndLinked(rig, calls)

            // The download returns the stored report, read through the document service outside any transaction.
            rig.calls.clear()
            val zBons = GlobalContext.get().get<ZBonService>()
            val reportId = assertNotNull(calls.last().documentId)
            val stored = runBlocking { zBons.getZBonPdfBytes(zBon.id) }
            assertContentEquals(storedFile(rig, reportId).readBytes(), stored)
            assertEquals(listOf("getDocumentFile(id=$reportId)" to false), rig.snapshot().map { it.text to it.inTransaction })

            // Without the file it regenerates from the closing itself.
            storedFile(rig, reportId).delete()
            val regenerated = runBlocking { zBons.getZBonPdfBytes(zBon.id) }
            val expected = generateZBonReportPdfBytes(runBlocking { zBons.getReportById(zBon.id, true) })
            assertEquals(textOf(expected), textOf(regenerated))
        }

    // ── After the closing: tip payout and its Storno ─────────────────────────────────────────────

    @Test
    fun `a tip payout and its reversal each book an Eigenbeleg in a transaction`() =
        scenario { rig, client ->
            val zBon = fixture.close(client)
            rig.calls.clear()

            val paid = fixture.payOut(client, zBon.id, anna(), "5.00")
            assertEquals(HttpStatusCode.Created, paid.status, paid.bodyAsText())
            val payout = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), paid.bodyAsText())
            val reversed = fixture.reverse(client, zBon.id, payout.kassenbuchHeaderId!!)
            assertEquals(HttpStatusCode.Created, reversed.status, reversed.bodyAsText())

            val calls = rig.snapshot()
            assertEquals(
                listOf(
                    "eigenbeleg(belegNr=<YM>-0002, date=<TODAY>, entryType=Trinkgeld-Auszahlung, amount=-5,00 EUR, description=Auszahlung Trinkgeld (Arbeitnehmer) an <ANNA> aus Z-Bon-ID ${zBon.id}, employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                    "eigenbeleg(belegNr=STORNO-<YM>-0002, date=<TODAY>, entryType=Korrektur, amount=+5,00 EUR, description=Storno Trinkgeld-Auszahlung an <ANNA>, employeeId=<ADMIN>, correctsBelegNr=<YM>-0002, correctsDate=<TODAY>, reason=falscher Mitarbeiter)",
                ),
                calls.map { normalise(it.text) },
            )
            assertTrue(calls.all { it.inTransaction })
            assertStoredAndLinked(rig, calls)
        }

    // ── Geldtransit between two non-fiscal books, and its Storno pair ────────────────────────────

    @Test
    fun `a transfer and its reversal book one Eigenbeleg per leg in the transfer's transaction`() =
        scenario { rig, _ ->
            val tresorA = fixture.createBook("Tresor A")
            val tresorB = fixture.createBook("Tresor B")
            fixture.fund(tresorA, "100.00")
            val transfers = GlobalContext.get().get<CashBookTransferService>()

            val transfer =
                runBlocking {
                    transfers.createTransfer(admin(), deviceId, CashBookDto.CreateTransferRequestDto(tresorA, tresorB, "40.00", today(), note = "Wechselgeld"))
                }
            val created = rig.snapshot()
            rig.calls.clear()
            runBlocking { transfers.reverseTransfer(admin(), deviceId, transfer.transferId, "falscher Betrag") }
            val reversal = rig.snapshot()

            assertEquals(
                listOf(
                    "eigenbeleg(belegNr=<YM>-0002, date=<TODAY>, entryType=Geldtransit (Abgang), amount=-40,00 EUR, description=Geldtransit an Tresor B (Beleg <YM>-0001). Wechselgeld, employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                    "eigenbeleg(belegNr=<YM>-0001, date=<TODAY>, entryType=Geldtransit (Zugang), amount=+40,00 EUR, description=Geldtransit von Tresor A (Beleg <YM>-0002). Wechselgeld, employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                ),
                created.map { normalise(it.text) },
            )
            assertEquals(
                listOf(
                    "eigenbeleg(belegNr=<YM>-0003, date=<TODAY>, entryType=Geldtransit (Zugang), amount=+40,00 EUR, description=Storno zu Beleg-Nr. <YM>-0002, employeeId=<ADMIN>, correctsBelegNr=<YM>-0002, correctsDate=<TODAY>, reason=falscher Betrag)",
                    "eigenbeleg(belegNr=<YM>-0002, date=<TODAY>, entryType=Geldtransit (Abgang), amount=-40,00 EUR, description=Storno zu Beleg-Nr. <YM>-0001, employeeId=<ADMIN>, correctsBelegNr=<YM>-0001, correctsDate=<TODAY>, reason=falscher Betrag)",
                ),
                reversal.map { normalise(it.text) },
            )
            listOf(created, reversal).forEach { legs ->
                assertTrue(legs.all { it.inTransaction })
                assertEquals(1, legs.map { it.transaction }.distinct().size, "both legs in one transaction")
            }
            assertStoredAndLinked(rig, created + reversal)
        }

    // ── Kassensturz of a non-fiscal book ─────────────────────────────────────────────────────────

    @Test
    fun `closing a non-fiscal book books the difference Eigenbeleg in the close transaction`() =
        scenario { rig, _ ->
            val tresor = fixture.createBook("Tresor")
            fixture.fund(tresor, "100.00")

            runBlocking { GlobalContext.get().get<CashBookCloseService>().close(admin(), tresor, BigDecimal("90.00")) }

            val calls = rig.snapshot()
            assertEquals(
                listOf(
                    "eigenbeleg(belegNr=<YM>-0002, date=<TODAY>, entryType=Kassendifferenz Minus, amount=-10,00 EUR, description=Kassensturz Tresor zum <TODAY>: gezählt 90,00 EUR, Buch 100,00 EUR, employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                ),
                calls.map { normalise(it.text) },
            )
            assertTrue(calls.single().inTransaction)
            assertStoredAndLinked(rig, calls)
        }

    // ── Kassenbuch correction: Storno-Eigenbeleg + copy of the original's document ──────────────

    @Test
    fun `a correction books a Storno-Eigenbeleg in a transaction and copies the original document outside one`() =
        scenario { rig, _ ->
            fixture.fund(null, "100.00")
            val originalDoc = uploadedDocument(rig, "original.pdf")
            val kassenbuch = GlobalContext.get().get<KassenbuchService>()
            val original = runBlocking { kassenbuch.createEntry(admin(), deviceId, expense("20.00", originalDoc)) }
            val originalId = headerIdOf(original.belegNr)

            runBlocking {
                kassenbuch.createCorrection(
                    admin(),
                    deviceId,
                    originalId,
                    KassenbuchDto.CorrectEntryRequestDto(reason = "Tippfehler", correctedEntry = expense("25.00", null)),
                )
            }

            val calls = rig.snapshot()
            assertEquals(
                listOf(
                    (
                        "eigenbeleg(belegNr=STORNO-<YM>-0002, date=<TODAY>, entryType=Korrektur, amount=+20,00 EUR, " +
                            "description=Storno zu Beleg-Nr. <YM>-0002, employeeId=<ADMIN>, correctsBelegNr=<YM>-0002, " +
                            "correctsDate=<TODAY>, reason=Tippfehler)"
                    ) to true,
                    "copyDocument(source=$originalDoc, copiedBy=<ADMIN>)" to false,
                ),
                calls.map { normalise(it.text) to it.inTransaction },
            )
            transaction {
                val corrected = KassenbuchHeader.find { KassenbuchHeaderTable.correctsHeaderId eq originalId }.toList()
                assertEquals(2, corrected.size, "reversal and re-entry")
                assertEquals(setOf(calls[0].documentId, calls[1].documentId), corrected.map { it.linkedDocumentId }.toSet())
            }
            assertStoredAndLinked(rig, calls.take(1))
        }

    // ── Drawer count outside a closing ───────────────────────────────────────────────────────────

    @Test
    fun `a drawer count books its difference Eigenbeleg in a transaction`() =
        scenario { rig, _ ->
            fixture.fund(null, "100.00")

            runBlocking { GlobalContext.get().get<KassenbuchService>().recordCashCount(admin(), deviceId, KassenbuchDto.CashCountRequestDto("150.00")) }

            val calls = rig.snapshot()
            assertEquals(
                listOf(
                    "eigenbeleg(belegNr=<YM>-0002, date=<TODAY>, entryType=Kassendifferenz Plus, amount=+3,00 EUR, description=Kassenzählung zum <TODAY>: gezählt 150,00 EUR, Soll 147,00 EUR (Buch 100,00 + unverbuchte Bareinnahmen 47,00), employeeId=<ADMIN>, correctsBelegNr=null, correctsDate=null, reason=null)",
                ),
                calls.map { normalise(it.text) },
            )
            assertTrue(calls.single().inTransaction)
            assertStoredAndLinked(rig, calls)
        }

    // ── Linked document on the entry detail; "document not found" refusals ───────────────────────

    @Test
    fun `entry detail carries the linked document summary and missing documents are refused`() =
        scenario { rig, _ ->
            fixture.fund(null, "100.00")
            val doc = uploadedDocument(rig, "beleg.pdf")
            val kassenbuch = GlobalContext.get().get<KassenbuchService>()
            val entry = runBlocking { kassenbuch.createEntry(admin(), deviceId, expense("12.00", doc)) }
            val headerId = headerIdOf(entry.belegNr)

            val detail = runBlocking { kassenbuch.getGroupedEntryDetail(headerId) }
            val expectedSummary = transaction { Document.findById(doc)!!.toSummaryDto() }
            assertEquals(expectedSummary, detail.linkedDocument)
            assertEquals(doc, detail.linkedDocument?.id)
            assertEquals(true, detail.hasLinkedDocuments)

            val missing = 987_654L
            val onCreate = assertFailsWith<PosException> { runBlocking { kassenbuch.createEntry(admin(), deviceId, expense("1.00", missing)) } }
            assertEquals(HttpStatusCode.NotFound to "Document not found: $missing", onCreate.httpStatus to onCreate.message)

            val alreadyLinked = assertFailsWith<PosException> { runBlocking { kassenbuch.linkDocument(admin(), deviceId, headerId, missing) } }
            assertEquals(
                HttpStatusCode.NotFound to "Kassenbuch entry $headerId already has a linked document (id=$doc)",
                alreadyLinked.httpStatus to alreadyLinked.message,
            )

            val unlinked = unlinkedHeader()
            val onLink = assertFailsWith<PosException> { runBlocking { kassenbuch.linkDocument(admin(), deviceId, unlinked, missing) } }
            assertEquals(HttpStatusCode.NotFound to "PDF document not found: $missing", onLink.httpStatus to onLink.message)

            val onReplace = assertFailsWith<PosException> { runBlocking { kassenbuch.replaceDocument(admin(), deviceId, headerId, missing) } }
            assertEquals(HttpStatusCode.NotFound to "PDF document not found: $missing", onReplace.httpStatus to onReplace.message)
            transaction { assertEquals(doc, KassenbuchHeader.findById(headerId)!!.linkedDocumentId, "a refused replace keeps the link") }
        }

    // ── Kassenmeldung PDF route ──────────────────────────────────────────────────────────────────

    @Test
    fun `the Kassenmeldung PDF route answers a PDF attachment of the Kassenmeldung record`() =
        scenario { _, client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val shopId =
                transaction {
                    ShopInfo
                        .all()
                        .first()
                        .id.value
                }

            val pdf = client.get("/api/v1/config/$shopId/kassen-meldung/pdf") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, pdf.status, pdf.bodyAsText())
            assertEquals(ContentType.Application.Pdf, pdf.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).withoutParameters() })
            assertEquals("attachment; filename=\"kassenmeldung.pdf\"", pdf.headers[HttpHeaders.ContentDisposition])

            val data = client.get("/api/v1/config/$shopId/kassen-meldung") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, data.status, data.bodyAsText())
            val record = testJson.decodeFromString(ShopConfigurationDto.KassenMeldungResponseDto.serializer(), data.bodyAsText())
            val text = textOf(pdf.bodyAsBytes())
            assertEquals(textOf(generateKassenmeldungPdfBytes(record)), text)
            assertTrue("Test Shop" in text, text)
        }

    // ── rig ──────────────────────────────────────────────────────────────────────────────────────

    private fun scenario(check: suspend io.ktor.server.testing.ApplicationTestBuilder.(Rig, io.ktor.client.HttpClient) -> Unit) {
        val root = Files.createTempDirectory("kassenbuch-documents").toFile()
        val rig = Rig(root)
        try {
            testApp(setupData = { fixture.seed(day) }, overrides = listOf(recorders(rig))) { client -> check(rig, client) }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Replaces the two accounting services by recorders around the real ones, writing under [Rig.root].
     * Loaded with the production modules, so the eagerly built Z-Bon scheduler chain gets them too.
     */
    private fun recorders(rig: Rig): Module {
        val storage =
            object : IShopConfigurationManager by ShopConfigurationManager {
                override suspend fun getDocumentStoragePath(): String = rig.root.absolutePath
            }
        return module {
            single<PdfGenerationService> { RecordingPdf(PdfGenerationServiceImpl(get(), get(), storage, get()), rig) }
            single<DocumentManagementService> { RecordingDocuments(DocumentManagementServiceImpl(get(), storage, get(), get(), get()), rig) }
        }
    }

    /** Each recorded document exists as stored PDF, is linked to exactly one entry, and its text names that entry. */
    private fun assertStoredAndLinked(
        rig: Rig,
        calls: List<Call>,
    ) {
        calls.forEach { call ->
            val id = assertNotNull(call.documentId, "no document for ${call.text}")
            val (fileType, mediaType, active) = transaction { Document.findById(id)!!.let { Triple(it.fileType, it.mediaType, it.isActive) } }
            val zBonReport = call.text.startsWith("zBonReport")
            assertEquals(if (zBonReport) "ZBON_REPORT" else "EIGENBELEG", fileType, call.text)
            assertEquals("application/pdf", mediaType)
            assertTrue(active)
            val linked = transaction { KassenbuchHeader.find { KassenbuchHeaderTable.linkedDocumentId eq id }.map { it.belegNr } }
            assertEquals(1, linked.size, "document $id is linked to exactly one entry")
            val text = textOf(storedFile(rig, id).readBytes())
            if (!zBonReport) {
                val belegNr = call.text.substringAfter("belegNr=").substringBefore(",")
                assertTrue(belegNr in text, "Eigenbeleg text names $belegNr: $text")
                assertTrue(call.text.substringAfter("amount=").substringBefore(",") in text || "amount=" !in call.text, text)
                assertTrue(
                    call.text
                        .substringAfter("description=")
                        .substringBefore(", employeeId=")
                        .take(30) in text.replace("\n", " "),
                    text,
                )
                if (call.text.contains("correctsBelegNr=null")) {
                    assertEquals(belegNr, linked.single(), "an ordinary Eigenbeleg carries its own entry's number")
                }
            }
        }
    }

    private fun storedFile(
        rig: Rig,
        documentId: Long,
    ): File = File(rig.root, transaction { Document.findById(documentId)!!.filePath })

    /** A document as an upload would leave it: a row plus its file under the document root. */
    private fun uploadedDocument(
        rig: Rig,
        name: String,
    ): Long {
        val relative = "2026/10/$name"
        File(rig.root, relative).apply {
            parentFile.mkdirs()
            writeBytes("%PDF-1.4 $name".toByteArray())
        }
        return transaction {
            Document
                .new {
                    filename = name
                    fileType = "INVOICE"
                    filePath = relative
                    fileSize = 16L
                    uploadedAt = currentTimeStamp()
                    uploadedBy = Employee.all().first { it.username == "admin" }
                    isActive = true
                    contentHash = "hash-$name"
                    mediaType = "application/pdf"
                }.id.value
        }
    }

    private fun unlinkedHeader(): Long =
        transaction {
            val shop = ShopInfo.all().first()
            val repository = GlobalContext.get().get<KassenbuchRepository>()
            val fiscal = CashBook.all().first { it.isFiscal }
            repository
                .createEntry(
                    shop = shop,
                    employee = Employee.all().first { it.username == "admin" },
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_DEPOSIT,
                            belegNr = "KDC-UNLINKED-1",
                            vorzBetrag = "1.00",
                            belegDatum = today(),
                            belegtext = "ohne Beleg",
                            cashBookId = fiscal.id.value,
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "1.00", ustSatz = null)),
                ).id.value
        }

    private fun expense(
        gross: String,
        documentId: Long?,
    ) = KassenbuchDto.CreateKassenbuchEntryRequestDto(
        entryType = KassenbuchEntryType.CASH_EXPENSE,
        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = gross)),
        belegtext = "Büromaterial",
        belegDatum = today(),
        documentId = documentId,
    )

    private fun headerIdOf(belegNr: String): Long =
        transaction {
            KassenbuchHeader
                .find { KassenbuchHeaderTable.belegNr eq belegNr }
                .single()
                .id.value
        }

    private fun admin(): String =
        transaction {
            Employee
                .all()
                .first { it.username == "admin" }
                .id.value
                .toString()
        }

    private fun anna() = fixture.waiters.getValue("anna").toString()

    private fun bert() = fixture.waiters.getValue("bert").toString()

    private fun displayName(id: UUID): String =
        transaction {
            Employee.findById(id)!!.zBonDisplayName()
        }

    @Suppress("DefaultLocale")
    private fun today(): String = currentTimeStamp().date.let { String.format("%02d.%02d.%04d", it.dayOfMonth, it.monthNumber, it.year) }

    @Suppress("DefaultLocale")
    private fun yearMonth(): String = currentTimeStamp().date.let { String.format("%04d-%02d", it.year, it.monthNumber) }

    private fun normalise(text: String): String =
        text
            .replace(today(), "<TODAY>")
            .replace(yearMonth(), "<YM>")
            .replace(admin(), "<ADMIN>")
            .replace(nameOf(anna()), "<ANNA>")
            .replace(nameOf(bert()), "<BERT>")

    private fun nameOf(id: String): String = displayName(UUID.fromString(id))

    private fun textOf(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
}
