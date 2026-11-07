package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.DatevXmlExportServiceImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the cashLedger `<invoiceId>` to the **supplier's** invoice number.
 *
 * `<invoiceId>` is the accountant's handle for matching a cash-book booking against the supplier's
 * invoice, so it must carry the supplier's number. `header.belegNr` is our own internal Kassenbuch
 * document number and is only a last-resort fallback.
 *
 * Since Stage 4 nothing writes `document_invoice_metadata`, so reading the invoice number from there
 * meant every newly linked invoice fell through to `belegNr` — a **wrong** value in fiscal output,
 * not a missing one, emitted with no log line. This is the first end-to-end assertion on that
 * field's provenance; the cashLedger export has no golden capture (only the AP export does, and this
 * change does not touch it).
 */
@DisplayName("Kassenbuch DATEV XML — invoiceId comes from the normalized invoice")
class DatevXmlLedgerInvoiceIdSourceTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private val documentRepository = DocumentRepositoryImpl()

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} not expected here")
        } as T
    }

    private val service by lazy {
        DatevXmlExportServiceImpl(
            kassenbuchRepository = repository,
            shopInfoRepository = noopProxy(),
            zBonService = noopProxy(),
            configManager = noopProxy(),
            exportLogService = noopProxy(),
            documentRepository = documentRepository,
        )
    }

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun invoiceDocument(filename: String): Long =
        documentRepository
            .create(
                filename = filename,
                fileType = "INVOICE",
                filePath = "/nonexistent/$filename",
                fileSize = 1024L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = employee.id.value,
                contentHash = "hash-${System.nanoTime()}",
                mediaType = "application/pdf",
            ).id.value

    private fun expense(belegNr: String) =
        repository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = KassenbuchEntryType.CASH_EXPENSE,
                    belegNr = belegNr,
                    vorzBetrag = "-119.00",
                    belegDatum = "08.07.2026",
                    belegtext = "Wareneinkauf",
                ),
            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-119.00", ustSatz = "19")),
        )

    @Test
    fun `invoiceId is the suppliers number from the normalized invoice, not our belegNr`() {
        transaction {
            val header = expense("2026-07-0300")
            val documentId = invoiceDocument("lieferantenrechnung.pdf")
            documentRepository.createInvoice(
                sourceDocumentId = documentId,
                invoiceNumber = "RE-LIEFERANT-4711",
                sellerName = "Lieferant GmbH",
                taxInclusiveTotal = BigDecimal("119.00"),
            )
            repository.linkDocument(header.id.value, documentId)

            val entries = service.mapHeaderToEntries(header, null)
            assertEquals(
                "RE-LIEFERANT-4711",
                entries.single().invoiceId,
                "invoiceId must be the supplier's number from InvoiceTable, never our internal belegNr",
            )
        }
    }

    @Test
    fun `a linked invoice document with no invoice row still falls back to belegNr`() {
        transaction {
            val header = expense("2026-07-0301")
            val documentId = invoiceDocument("nur-scan.pdf")
            repository.linkDocument(header.id.value, documentId)

            val entries = service.mapHeaderToEntries(header, null)
            assertEquals(
                "2026-07-0301",
                entries.single().invoiceId,
                "an unread scan has no invoice number anywhere; belegNr stays the documented fallback",
            )
        }
    }

    @Test
    fun `a header with no linked document emits no invoiceId`() {
        transaction {
            val header = expense("2026-07-0302")
            assertNull(service.mapHeaderToEntries(header, null).single().invoiceId)
        }
    }
}
