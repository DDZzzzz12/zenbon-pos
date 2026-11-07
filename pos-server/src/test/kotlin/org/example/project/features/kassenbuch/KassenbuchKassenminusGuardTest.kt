package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.document.domain.models.Document
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.service.CashBookTransferService
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F59 — the Kassenminus guard on every operator withdrawal: no booking may take a cash book below zero.
 *
 * The day: one 20.00 bill with a 3.00 employee tip, paid in cash — 23.00 sits in the drawer while the
 * fiscal book still reads 0.00 (cash sales reach it only with the Z-Bon's aggregate). "Available" for
 * the fiscal Ladenkasse is therefore 23.00; for a Tresor it is its book balance alone.
 *
 * Every refusal asserts nothing was written, and each refusal has a negative control: the same booking
 * one cent smaller goes through.
 */
@DisplayName("Kassenminus guard on cash-book withdrawals (F59)")
class KassenbuchKassenminusGuardTest {
    private val deviceId = "kassenminus-device"
    private val fixture = TippedDayFixture(deviceId, "KMG")
    private val cashDay = listOf(Sale("anna", "3.00"))

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    // ============================================
    // Available cash
    // ============================================

    @Test
    fun `the fiscal Ladenkasse may pay out its not-yet-closed cash takings, and not a cent more`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val available = kassenbuch().getAvailableCash(null)
            assertDecimal("0", available.bookBalance)
            assertDecimal("23.00", available.unbookedCashTakings, "the drawer holds the cash bill")
            assertDecimal("23.00", available.availableCash)

            val refused = assertRefused { book(KassenbuchEntryType.CASH_EXPENSE, "23.01", cashBookId = null) }
            assertTrue(refused.message!!.contains("holds 23.00"), refused.message)
            assertEquals(0, headerCount(), "nothing booked")

            book(KassenbuchEntryType.CASH_EXPENSE, "23.00", cashBookId = null) // negative control
            assertDecimal("0", kassenbuch().getAvailableCash(null).availableCash)
            assertRefused { book(KassenbuchEntryType.CASH_EXPENSE, "0.01", cashBookId = null) }
        }

    @Test
    fun `a Tresor holds only its own entries - takings never count there`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val tresor = fixture.createBook("Tresor")
            assertDecimal("0", kassenbuch().getAvailableCash(tresor).availableCash, "the drawer's takings are not in the Tresor")
            assertRefused { book(KassenbuchEntryType.CASH_EXPENSE, "0.01", cashBookId = tresor) }
        }

    @Test
    fun `every withdrawal type is guarded`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val tresor = fixture.createBook("Tresor")
            fixture.fund(tresor, "10.00")
            val withdrawals =
                listOf(
                    KassenbuchEntryType.CASH_EXPENSE,
                    KassenbuchEntryType.CASH_WITHDRAWAL,
                    KassenbuchEntryType.PRIVATE_WITHDRAWAL,
                    KassenbuchEntryType.REFUND,
                )
            withdrawals.forEach { type ->
                val before = headerCount()
                val refused = assertRefused { book(type, "10.01", cashBookId = tresor) }
                assertEquals(PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH, refused.code, type.name)
                assertEquals(before, headerCount(), "$type: nothing booked")
            }
            // Negative control: the last cent is payable.
            book(KassenbuchEntryType.PRIVATE_WITHDRAWAL, "10.00", cashBookId = tresor)
            assertDecimal("0", balance(tresor))
        }

    @Test
    fun `an inflow is never refused, however empty the book`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val tresor = fixture.createBook("Tresor")
            book(KassenbuchEntryType.CASH_DEPOSIT, "5.00", cashBookId = tresor)
            assertDecimal("5.00", balance(tresor))
        }

    // ============================================
    // Exemptions
    // ============================================

    /**
     * A Storno says a booking did not happen; refusing it would keep a record known to be false. The
     * Storno of an Einlage whose cash was spent therefore goes through and shows the gap.
     */
    @Test
    fun `the Storno of an Einlage whose cash was spent stays possible and shows the gap`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val tresor = fixture.createBook("Tresor")
            val deposit = book(KassenbuchEntryType.CASH_DEPOSIT, "10.00", cashBookId = tresor)
            book(KassenbuchEntryType.CASH_EXPENSE, "10.00", cashBookId = tresor)

            kassenbuch().createCorrection(
                employeeId = admin(),
                deviceId = deviceId,
                headerId = deposit,
                request =
                    KassenbuchDto.CorrectEntryRequestDto(
                        reason = "Einlage war 4,00, nicht 10,00",
                        correctedEntry = entry(KassenbuchEntryType.CASH_DEPOSIT, "4.00", tresor),
                        replacementDocumentId = newDocument(),
                    ),
            )

            assertDecimal("-6.00", balance(tresor), "the reversal (−10) is exempt; the books now show the 6.00 that was never there")
            transaction {
                val reversal = KassenbuchHeader.all().single { it.correctionRole == CorrectionRole.REVERSAL }
                assertDecimal("-10.00", reversal.totalAmount.toPlainString())
            }
            // Negative control: the same 10.00 out of that book as an ordinary booking is refused.
            assertRefused { book(KassenbuchEntryType.CASH_EXPENSE, "10.00", cashBookId = tresor) }
        }

    /** The re-entry of a correction is a new booking: refused up front, BEFORE the Storno is booked. */
    @Test
    fun `a correction whose re-entry the book cannot cover is refused before anything is booked`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val tresor = fixture.createBook("Tresor")
            fixture.fund(tresor, "30.00")
            val expense = book(KassenbuchEntryType.CASH_EXPENSE, "15.00", cashBookId = tresor)
            val before = headerCount()

            assertRefused { correctExpense(expense, "45.01", tresor) } // 15.00 back + 15.00 left = 30.00 available
            assertEquals(before, headerCount(), "no half-done correction: the Storno was not booked either")

            correctExpense(expense, "30.00", tresor) // negative control
            assertDecimal("0", balance(tresor))
        }

    /** A Kassendifferenz records what was counted — reality. */
    @Test
    fun `a counted shortage is booked even when it takes the book below zero`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val result =
                kassenbuch().recordCashCount(admin(), deviceId, KassenbuchDto.CashCountRequestDto(countedAmount = "3.00"))
            assertDecimal("-20.00", result.difference, "23.00 expected, 3.00 counted")
            assertDecimal("-20.00", balance(null), "the book is below zero until the closing books the takings")
        }

    // ============================================
    // Transfers share the one guard
    // ============================================

    @Test
    fun `a transfer out of the Ladenkasse counts the drawer's takings and refuses more`() =
        testApp(setupData = { fixture.seed(cashDay) }) { _ ->
            val tresor = fixture.createBook("Tresor")
            val fiscal =
                transaction {
                    org.example.project.features.kassenbuch.data.repository
                        .CashBookRepositoryImpl()
                        .fiscalBookFor(ShopInfo.all().first())
                        .id.value
                }

            val refused = assertRefused { transfer(fiscal, tresor, "23.01") }
            assertTrue(refused.message!!.contains("holds 23.00"), refused.message)
            transaction { assertTrue(KassenbuchHeader.all().none { it.transferId != null }, "neither leg booked") }

            transfer(fiscal, tresor, "23.00") // negative control: the takings are in the drawer
            assertDecimal("23.00", balance(tresor))
        }

    // ============================================
    // Helpers
    // ============================================

    private fun kassenbuch(): KassenbuchService = GlobalContext.get().get()

    private fun admin(): String =
        transaction {
            Employee
                .all()
                .first { it.username == "admin" }
                .id.value
                .toString()
        }

    private fun headerCount(): Long = transaction { KassenbuchHeader.all().count() }

    private fun balance(cashBookId: Long?): String =
        transaction { KassenbuchRepositoryImpl(testKassenbuchDocuments()).getCurrentBalance(ShopInfo.all().first(), cashBookId).toPlainString() }

    private fun entry(
        type: KassenbuchEntryType,
        amount: String,
        cashBookId: Long?,
        documentId: Long? = null,
    ) = KassenbuchDto.CreateKassenbuchEntryRequestDto(
        entryType = type,
        lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "19", amount = amount)),
        belegtext = "F59 ${type.name}",
        documentId = documentId,
        cashBookId = cashBookId,
    )

    /** Books [type] of [amount] through the operator path; returns the header id. */
    private suspend fun book(
        type: KassenbuchEntryType,
        amount: String,
        cashBookId: Long?,
    ): Long =
        kassenbuch()
            .createEntry(admin(), deviceId, entry(type, amount, cashBookId, newDocument()))
            .entries
            .single()
            .id

    private suspend fun correctExpense(
        headerId: Long,
        amount: String,
        cashBookId: Long,
    ) {
        kassenbuch().createCorrection(
            employeeId = admin(),
            deviceId = deviceId,
            headerId = headerId,
            request =
                KassenbuchDto.CorrectEntryRequestDto(
                    reason = "Betrag korrigiert",
                    correctedEntry = entry(KassenbuchEntryType.CASH_EXPENSE, amount, cashBookId),
                    replacementDocumentId = newDocument(),
                ),
        )
    }

    private suspend fun transfer(
        from: Long,
        to: Long,
        amount: String,
    ) {
        val belegDatum =
            currentTimeStamp().date.let {
                "${it.dayOfMonth.toString().padStart(2, '0')}.${it.monthNumber.toString().padStart(2, '0')}.${it.year}"
            }
        GlobalContext.get().get<CashBookTransferService>().createTransfer(
            employeeId = admin(),
            deviceId = deviceId,
            request = CashBookDto.CreateTransferRequestDto(fromBookId = from, toBookId = to, amount = amount, belegDatum = belegDatum),
        )
    }

    private var docCounter = 0

    private fun newDocument(): Long =
        transaction {
            Document
                .new {
                    this.filename = "beleg-${++docCounter}.pdf"
                    this.fileType = "INVOICE"
                    this.filePath = "/tmp/test/kmg-$docCounter.pdf"
                    this.fileSize = 1024L
                    this.uploadedAt = currentTimeStamp()
                    this.uploadedBy = Employee.all().first { it.username == "admin" }
                    this.isActive = true
                    this.contentHash = "kmg-hash-$docCounter"
                    this.mediaType = "application/pdf"
                }.id.value
        }

    private suspend fun assertRefused(block: suspend () -> Unit): PosException {
        val refused = assertFailsWith<PosException> { block() }
        assertEquals(PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH, refused.code, refused.message)
        assertEquals(409, refused.httpStatus.value)
        return refused
    }

    private fun assertDecimal(
        expected: String,
        actual: String,
        message: String? = null,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "${message ?: ""} expected $expected, was $actual")
}
