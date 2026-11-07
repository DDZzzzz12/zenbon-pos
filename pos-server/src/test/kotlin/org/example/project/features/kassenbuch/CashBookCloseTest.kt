package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookCloseServiceImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBookClose
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.service.CashBookCloseService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 20 covers the Kassensturz **preview** (read-only). Task 21 covers **close**: counting,
 * booking the difference, and locking the period.
 */
class CashBookCloseTest {
    private lateinit var repo: KassenbuchRepositoryImpl
    private lateinit var books: CashBookRepositoryImpl
    private lateinit var closeService: CashBookCloseServiceImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repo = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        books = CashBookRepositoryImpl()
        closeService =
            CashBookCloseServiceImpl(
                cashBookRepository = books,
                kassenbuchRepository = repo,
                employeeRepository = EmployeeRepositoryImpl(),
                documents = testKassenbuchDocuments(pdfGenerationService = fakePdfGenerationService),
            )
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    // -------------------------------------------------------------------------
    // Fakes — modeled on CashBookTransferTest's fixture
    // -------------------------------------------------------------------------

    private var docCounter = 0

    private fun newDocumentId(filename: String): Long =
        transaction {
            Document
                .new {
                    this.filename = filename
                    this.fileType = "INVOICE"
                    this.filePath = "/tmp/test/${++docCounter}-$filename"
                    this.fileSize = 1024L
                    this.uploadedAt = currentTimeStamp()
                    this.uploadedBy = employee
                    this.isActive = true
                    this.contentHash = "hash-$docCounter-$filename"
                    this.mediaType = "application/pdf"
                }.id.value
        }

    /** PdfGenerationService fake: generateEigenbelegPdf creates a real Document, returns its summary. */
    private val fakePdfGenerationService: PdfGenerationService by lazy {
        @Suppress("UNCHECKED_CAST")
        Proxy.newProxyInstance(
            PdfGenerationService::class.java.classLoader,
            arrayOf(PdfGenerationService::class.java),
        ) { _, method, _ ->
            if (method.name == "generateEigenbelegPdf") {
                // Mirror the real impl: it persists a Document + maps the entity, so it MUST run
                // within a transaction. This guards against the caller forgetting dbQuery.
                TransactionManager.currentOrNull()
                    ?: error("generateEigenbelegPdf must be called within a transaction")
                val id = newDocumentId("kassensturz-eigenbeleg.pdf")
                DocumentDto.DocumentResponseDto(
                    id = id,
                    filename = "kassensturz-eigenbeleg.pdf",
                    fileType = "INVOICE",
                    fileSize = 1024L,
                    uploadedAt = "2026-08-08T00:00:00",
                    uploadedBy = employee.id.value.toString(),
                    downloadUrl = "/download/$id",
                    formattedFileSize = "1.0 KB",
                    monthFolder = "2026-08",
                )
            } else {
                throw NotImplementedError("PdfGenerationService.${method.name} should not be called")
            }
        } as PdfGenerationService
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun deposit(
        bookId: Long,
        amount: String,
        belegDatum: String = "01.08.2026",
    ): Long =
        transaction {
            repo
                .createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_DEPOSIT,
                            belegNr = repo.nextBelegNrBlocking(bookId),
                            vorzBetrag = amount,
                            belegDatum = belegDatum,
                            belegtext = "Test",
                            cashBookId = bookId,
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = null)),
                    tseTransaction = null,
                ).id.value
        }

    /** Backdates a header's createdAt so a period can be built that genuinely ended in the past. */
    private fun backdate(
        headerId: Long,
        at: LocalDateTime,
    ) {
        transaction {
            KassenbuchHeader.findById(headerId)!!.createdAt = at
        }
    }

    private fun close(
        bookId: Long,
        countedBalance: String,
        notes: String? = null,
    ) = runBlocking {
        closeService.close(
            employeeId = employee.id.value.toString(),
            cashBookId = bookId,
            countedBalance = BigDecimal(countedBalance),
            notes = notes,
        )
    }

    // -------------------------------------------------------------------------
    // Task 20 — preview
    // -------------------------------------------------------------------------

    @Test
    fun `preview derives periodStart from the book's first entry when never closed`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        deposit(tresor, "100.00")
        deposit(tresor, "50.00")
        val today = currentTimeStamp().date.toString()

        val preview = runBlocking { closeService.preview(tresor) }

        assertEquals(today, preview.periodStart, "periodStart must be the date of the book's first entry")
        assertEquals(
            0,
            BigDecimal("150.00").compareTo(BigDecimal(preview.bookBalance)),
            "bookBalance must be the book's current running balance",
        )
    }

    @Test
    fun `preview derives periodStart from the previous close`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "20.00")

        val previousPeriodEnd = LocalDate(2026, 7, 15)
        transaction {
            CashBookClose.new {
                this.cashBook = books.findById(tresor)!!
                this.periodStart = LocalDate(2026, 7, 1)
                this.periodEnd = previousPeriodEnd
                this.bookBalance = BigDecimal("20.00")
                this.countedBalance = BigDecimal("20.00")
                this.difference = BigDecimal.ZERO
                this.closedBy = employee
            }
        }

        val preview = runBlocking { closeService.preview(tresor) }

        assertEquals(
            "2026-07-16",
            preview.periodStart,
            "periodStart must be the day AFTER the previous close's periodEnd",
        )
    }

    @Test
    fun `preview refuses the fiscal book`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }

        assertFailsWith<PosException> {
            runBlocking { closeService.preview(kasse) }
        }
        Unit
    }

    // -------------------------------------------------------------------------
    // Task 21 — close
    // -------------------------------------------------------------------------

    @Test
    fun `a shortage books CASH_SHORTAGE and the book then matches the count`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "5000.00")

        val result = close(tresor, "4950.00")

        assertNotNull(result.differenceHeaderId, "a non-zero difference must book an entry")
        transaction {
            val header = KassenbuchHeader.findById(result.differenceHeaderId!!)!!
            assertEquals(KassenbuchEntryType.CASH_SHORTAGE, header.entryType)
            assertEquals(0, BigDecimal("-50.00").compareTo(header.totalAmount))
            assertNotNull(header.linkedDocumentId, "the difference entry must carry a mandatory Eigenbeleg")
            assertEquals(0, BigDecimal("4950.00").compareTo(repo.getCurrentBalance(shop, tresor)))
        }
    }

    @Test
    fun `a surplus books CASH_SURPLUS`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "5000.00")

        val result = close(tresor, "5050.00")

        assertNotNull(result.differenceHeaderId, "a non-zero difference must book an entry")
        transaction {
            val header = KassenbuchHeader.findById(result.differenceHeaderId!!)!!
            assertEquals(KassenbuchEntryType.CASH_SURPLUS, header.entryType)
            assertEquals(0, BigDecimal("50.00").compareTo(header.totalAmount))
            assertNotNull(header.linkedDocumentId, "the difference entry must carry a mandatory Eigenbeleg")
            assertEquals(0, BigDecimal("5050.00").compareTo(repo.getCurrentBalance(shop, tresor)))
        }
    }

    @Test
    fun `a matching count books no difference entry`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "5000.00")
        val countBefore = transaction { KassenbuchHeader.find { KassenbuchHeaderTable.cashBookId eq tresor }.count() }

        val result = close(tresor, "5000.00")

        assertNull(result.differenceHeaderId, "a matching count must not book a difference entry")
        transaction {
            val countAfter = KassenbuchHeader.find { KassenbuchHeaderTable.cashBookId eq tresor }.count()
            assertEquals(countBefore, countAfter, "a matching count must create no new header")
        }
    }

    @Test
    fun `the difference entry is locked even when the period ended in the past`() {
        // Trap test: close() runs TODAY, but the period it resolves to ended weeks ago. The
        // difference entry it books is stamped createdAt = NOW, not periodEnd, so a lock
        // predicate scoped to `createdAt <= periodEnd` would silently skip the very row it just
        // created, stranding it isLocked=false forever (no repair path).
        val tresor =
            transaction {
                books.create(shop, "Tresor", false, "1010", CashBookCloseInterval.MONTHLY).id.value
            }
        val headerId = deposit(tresor, "5000.00", "15.07.2026")
        backdate(headerId, LocalDateTime(2026, 7, 15, 10, 0))

        // Sanity: the resolved period must genuinely be in the past, or this test proves nothing.
        val preview = runBlocking { closeService.preview(tresor) }
        assertEquals("2026-07-31", preview.periodEnd, "the period must end in July, well before today")

        val result = close(tresor, "4950.00")

        assertNotNull(result.differenceHeaderId, "a non-zero difference must book an entry")
        transaction {
            val diffHeader = KassenbuchHeader.findById(result.differenceHeaderId!!)!!
            assertTrue(
                diffHeader.isLocked,
                "the difference entry must be locked even though its createdAt is after periodEnd",
            )
        }
    }

    @Test
    fun `entries after periodEnd stay unlocked`() {
        val tresor =
            transaction {
                books.create(shop, "Tresor", false, "1010", CashBookCloseInterval.MONTHLY).id.value
            }
        val withinPeriod = deposit(tresor, "5000.00", "15.07.2026")
        backdate(withinPeriod, LocalDateTime(2026, 7, 15, 10, 0))
        val afterPeriod = deposit(tresor, "10.00", "01.08.2026")
        backdate(afterPeriod, LocalDateTime(2026, 8, 1, 9, 0))

        close(tresor, "5000.00")

        transaction {
            assertTrue(
                KassenbuchHeader.findById(withinPeriod)!!.isLocked,
                "the entry inside the closed period must be locked",
            )
            assertTrue(
                !KassenbuchHeader.findById(afterPeriod)!!.isLocked,
                "an entry dated after periodEnd must stay unlocked",
            )
        }
    }

    @Test
    fun `the difference entry is never TSE-signed`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "5000.00")

        val result = close(tresor, "4990.00")

        assertNotNull(result.differenceHeaderId, "a non-zero difference must book an entry")
        transaction {
            val header = KassenbuchHeader.findById(result.differenceHeaderId!!)!!
            assertEquals(TseSigningStatus.NOT_APPLICABLE, header.tseSigningStatus)
            assertNull(header.tseTransaction)
        }
    }

    @Test
    fun `closing one book does not lock another book's entries`() {
        // Second-writer invariant (KassenbuchHeaderTable.isLocked KDoc): the lock predicate must
        // be scoped to the ONE book being closed, structurally incapable of touching another
        // book's rows even when both are due for the exact same period.
        val tresor1 =
            transaction {
                books.create(shop, "Tresor 1", false, "1010", CashBookCloseInterval.MONTHLY).id.value
            }
        val tresor2 =
            transaction {
                books.create(shop, "Tresor 2", false, "1020", CashBookCloseInterval.MONTHLY).id.value
            }
        val header1 = deposit(tresor1, "5000.00", "15.07.2026")
        backdate(header1, LocalDateTime(2026, 7, 15, 10, 0))
        val header2 = deposit(tresor2, "3000.00", "15.07.2026")
        backdate(header2, LocalDateTime(2026, 7, 15, 10, 0))

        close(tresor1, "5000.00")

        transaction {
            assertTrue(KassenbuchHeader.findById(header1)!!.isLocked, "tresor1's entry must be locked")
            assertTrue(
                !KassenbuchHeader.findById(header2)!!.isLocked,
                "tresor2's entry must stay untouched by tresor1's close",
            )
        }
    }

    @Test
    fun `closing again with no day elapsed is refused, not booked as a second Kassensturz`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "5000.00")

        // First close settles everything through today and books the -50 shortage.
        close(tresor, "4950.00")
        val headersAfterFirst =
            transaction { KassenbuchHeader.find { KassenbuchHeaderTable.cashBookId eq tresor }.count() }
        val closesAfterFirst = transaction { CashBookClose.all().count() }

        // Second close on the same day derives periodStart = tomorrow against periodEnd = today.
        // Unguarded this wrote an inverted-period row AND booked another difference — letting an
        // operator re-count at will, moving the balance each time.
        val error = assertFailsWith<PosException> { close(tresor, "4000.00") }
        assertTrue(
            error.message!!.contains("nothing new to close"),
            "the refusal must name the cause, got: ${error.message}",
        )

        transaction {
            assertEquals(
                headersAfterFirst,
                KassenbuchHeader.find { KassenbuchHeaderTable.cashBookId eq tresor }.count(),
                "a refused close must book no second difference entry",
            )
            assertEquals(closesAfterFirst, CashBookClose.all().count(), "and must write no close row")
            assertEquals(
                0,
                java.math.BigDecimal("4950.00").compareTo(repo.getCurrentBalance(shop, tresor)),
                "the balance must still be the first close's counted figure",
            )
        }
    }

    @Test
    fun `a same-day Kassensturz is allowed once new entries were booked after the first (T9-11)`() {
        // The refusal above must not become a lockout: entries booked after today's close are
        // unlocked, block every Stammdaten edit, and only a Kassensturz of this book can lock them.
        // Before T9-11 that close was refused until tomorrow ("nothing new to close").
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        deposit(tresor, "5000.00")
        close(tresor, "5000.00")
        val later = deposit(tresor, "-20.00")
        val today = currentTimeStamp().date.toString()

        val preview = runBlocking { closeService.preview(tresor) }
        assertEquals(today, preview.periodStart, "the follow-up close covers today again")
        assertEquals(today, preview.periodEnd)
        assertEquals(0, BigDecimal("4980.00").compareTo(BigDecimal(preview.bookBalance)))

        val second = close(tresor, "4970.00")

        assertNotNull(second.differenceHeaderId, "the second count's shortage is booked")
        transaction {
            assertTrue(KassenbuchHeader.findById(later)!!.isLocked, "the entry booked after the first close is locked")
            assertTrue(
                KassenbuchHeader.find { KassenbuchHeaderTable.cashBookId eq tresor }.all { it.isLocked },
                "nothing of the Tresor stays open",
            )
            assertEquals(2, CashBookClose.all().count().toInt())
        }
        // And with nothing new since, a third close is refused again.
        val error = assertFailsWith<PosException> { close(tresor, "4970.00") }
        assertTrue(error.message!!.contains("nothing new to close"), error.message)
    }

    @Test
    fun `the service exposes no way to reopen a close`() {
        // GoBD: a closed period is corrected by Storno entries in the NEXT period, never by
        // reopening. The guarantee is structural — there is no such method to call.
        val methods =
            CashBookCloseService::class.java.methods
                .map { it.name }
                .toSet()
        assertEquals(
            setOf("preview", "close"),
            methods.filter { it in setOf("preview", "close", "reopen", "delete", "update", "unlock") }.toSet(),
            "CashBookCloseService must expose only preview and close, got: $methods",
        )
    }
}
