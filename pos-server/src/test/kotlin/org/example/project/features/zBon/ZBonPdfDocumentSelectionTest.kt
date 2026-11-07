package org.example.project.features.zBon

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.document.domain.models.Document
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.data.service.ZBonServiceImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * `GET /api/z-bon/{id}/pdf` used to hand back a different document than the one it names.
 *
 * `createReport` attributes **every** unswept fiscal Kassenbuch header of the period to the report
 * (`if (header.zBon == null) header.zBon = report`), so `zbon.kasseBuch` is the whole day's cash
 * book — expense receipts, deposits, corrections, both Geldtransit legs — and each of those rows
 * carries its own linked Beleg. Only the aggregate CASH_SALE entry gets the Z-Bon report itself.
 *
 * The old lookup took `kasseBuch.firstOrNull()?.linkedDocument`, i.e. an arbitrary row's document.
 * The failure is quiet: the operator receives a real, well-formed PDF that simply belongs to a
 * different transaction, so nothing looks broken until someone reconciles it.
 */
@DisplayName("ZBonServiceImpl.getZBonPdfBytes document selection")
class ZBonPdfDocumentSelectionTest {
    private lateinit var service: ZBonServiceImpl
    private lateinit var employee: Employee
    private lateinit var shop: ShopInfo
    private lateinit var tempDir: File

    private val zBonPdfBytes = "%PDF-1.4 THE Z-BON REPORT".toByteArray()
    private val expenseBelegBytes = "%PDF-1.4 SOME EXPENSE RECEIPT".toByteArray()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        employee = TestData.createAdmin()
        shop = TestData.createShopInfo()
        tempDir =
            File.createTempFile("zbon-pdf-test", "").let {
                it.delete()
                it.mkdirs()
                it
            }
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
        TestDatabase.teardown()
    }

    @Test
    fun `returns the Z-Bon report, not another header's Beleg`() {
        val (zbonId, files) = seedDayWithTwoDocuments()
        service = serviceReading(files)

        val bytes = runBlocking { service.getZBonPdfBytes(zbonId) }

        assertEquals(
            zBonPdfBytes.toList(),
            bytes.toList(),
            "the download must return the ZBON_REPORT document, not the expense Beleg that happens " +
                "to sit on the day's earliest Kassenbuch header",
        )
    }

    /**
     * One Z-Bon, two attributed headers: an expense booked earlier in the day (lower id, so it is
     * what `firstOrNull()` used to pick) and the Z-Bon aggregate that carries the report.
     */
    private fun seedDayWithTwoDocuments(): Pair<Long, Map<Long, File>> =
        transaction {
            val now = currentTimeStamp()
            val zbon = TestData.createZBon(shop, employee, now, now, nummer = 1)

            val expenseDoc = newDocument("expense-beleg.pdf", DocumentType.INVOICE.name)
            val zBonDoc = newDocument("zbon-report.pdf", DocumentType.ZBON_REPORT.name)

            newHeader(zbon, KassenbuchEntryType.CASH_EXPENSE, "2026-08-0001").apply {
                linkedDocumentId = expenseDoc.id.value
            }
            newHeader(zbon, KassenbuchEntryType.CASH_SALE, "2026-08-0002").apply {
                linkedDocumentId = zBonDoc.id.value
            }

            val files =
                mapOf(
                    expenseDoc.id.value to File(tempDir, "expense.pdf").apply { writeBytes(expenseBelegBytes) },
                    zBonDoc.id.value to File(tempDir, "zbon.pdf").apply { writeBytes(zBonPdfBytes) },
                )
            zbon.id.value to files
        }

    private fun newDocument(
        name: String,
        type: String,
    ): Document =
        Document.new {
            filename = name
            fileType = type
            filePath = "2026/08/$name"
            fileSize = 1L
            uploadedAt = currentTimeStamp()
            uploadedBy = employee
            mediaType = "application/pdf"
            contentHash = "hash-$name"
        }

    private fun newHeader(
        zbon: ZBon,
        type: KassenbuchEntryType,
        beleg: String,
    ): KassenbuchHeader =
        KassenbuchHeader.new {
            this.shop = this@ZBonPdfDocumentSelectionTest.shop
            cashBook = CashBookRepositoryImpl().fiscalBookFor(this@ZBonPdfDocumentSelectionTest.shop)
            employee = this@ZBonPdfDocumentSelectionTest.employee
            entryType = type
            belegNr = beleg
            belegDatum = "13.08.2026"
            belegtext = "seed"
            waehrung = "EUR"
            totalAmount = BigDecimal.TEN
            currentBalance = BigDecimal.TEN
            createdAt = currentTimeStamp()
            this.zBon = zbon
        }

    /** Only `documentManagementService.getDocumentFile` (behind `documents`) is exercised; everything else is inert. */
    private fun serviceReading(files: Map<Long, File>): ZBonServiceImpl =
        ZBonServiceImpl(
            repository = ZBonRepositoryImpl(),
            kassenbuchRepository = noopProxy(),
            cashBookRepository = noopProxy(),
            // Concrete classes, so they cannot be dynamic-proxied; neither is touched by the path
            // under test, and their own collaborators stay inert.
            cashBookTransferService =
                CashBookTransferServiceImpl(
                    configManager = noopProxy(),
                    cashBookRepository = noopProxy(),
                    kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                    employeeRepository = noopProxy(),
                    terminalRepository = noopProxy(),
                    documents = testKassenbuchDocuments(pdfGenerationService = noopProxy()),
                    tseSigner =
                        KassenbuchTseSigner(
                            configManager = noopProxy(),
                            tseServiceFactory = TseServiceFactory(noopProxy(), noopProxy(), TseVorgangSigner(noopProxy(), noopProxy(), noopProxy())),
                            tseSigningQueueService = noopProxy(),
                        ),
                ),
            tseServiceFactory = TseServiceFactory(noopProxy(), noopProxy(), TseVorgangSigner(noopProxy(), noopProxy(), noopProxy())),
            tseSigningQueueService = noopProxy(),
            terminalRepository = noopProxy(),
            documents =
                testKassenbuchDocuments(
                    pdfGenerationService = noopProxy(),
                    documentManagementService =
                        proxy { method, args ->
                            if (method.name == "getDocumentFile") {
                                files.getValue(args!![0] as Long)
                            } else {
                                throw NotImplementedError("unexpected call: ${method.name}")
                            }
                        },
                ),
            favouriteProductService = noopProxy(),
            configManager = noopProxy(),
            cardTerminalService = noopProxy(),
            cardSettlementService = noopProxy(),
            tableSessionRepository = noopProxy(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> proxy(noinline handle: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, args -> handle(method, args) } as T

    private inline fun <reified T : Any> noopProxy(): T = proxy { method, _ -> throw NotImplementedError("unexpected call: ${method.name}") }
}
