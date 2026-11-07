package org.example.project.ui.components.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A4 — the manual cash transfer form's guards, mirroring `CashBookTransferServiceImpl`.
 *
 * The form exists to close a trap: without it, moving cash to the safe gets booked as a one-sided
 * CASH_TRANSFER_OUT, which means **Bank**, not the Tresor — the safe never receives the money in
 * its own book and the two books silently disagree.
 */
class CashTransferValidationTest {
    private fun book(
        id: Long,
        name: String,
        balance: String,
        isFiscal: Boolean = false,
        isActive: Boolean = true,
    ) = CashBookDto.ResponseDto(
        id = id,
        name = name,
        isFiscal = isFiscal,
        datevAccount = "1000",
        closeInterval = CashBookCloseInterval.MANUAL,
        isActive = isActive,
        sortOrder = 0,
        currentBalance = balance,
        currency = "EUR",
    )

    private val till = book(1, "Ladenkasse", "500.00", isFiscal = true)
    private val safe = book(2, "Tresor", "0.00")

    @Test
    fun `a valid transfer passes`() {
        assertNull(validateCashTransfer(till, safe, "100.00"))
    }

    @Test
    fun `the same book on both sides is refused`() {
        assertEquals(CashTransferError.SAME_BOOK, validateCashTransfer(till, till, "100.00"))
    }

    @Test
    fun `an amount above the source balance is refused`() {
        assertEquals(
            CashTransferError.AMOUNT_EXCEEDS_SOURCE_BALANCE,
            validateCashTransfer(till, safe, "500.01"),
        )
    }

    @Test
    fun `the balance checked is the source's own, not the till's`() {
        // The Tresor holds 0.00. Moving 100 OUT of it must fail even though the till holds 500 —
        // comparing against a fixed book would let this through to an unexplainable server error.
        assertEquals(
            CashTransferError.AMOUNT_EXCEEDS_SOURCE_BALANCE,
            validateCashTransfer(safe, till, "100.00"),
        )
    }

    @Test
    fun `exactly the full balance is allowed`() {
        assertNull(validateCashTransfer(till, safe, "500.00"))
    }

    @Test
    fun `zero and negative amounts are refused`() {
        assertEquals(CashTransferError.AMOUNT_NOT_POSITIVE, validateCashTransfer(till, safe, "0.00"))
        assertEquals(CashTransferError.AMOUNT_NOT_POSITIVE, validateCashTransfer(till, safe, "-5.00"))
    }

    @Test
    fun `an unparseable amount is refused`() {
        assertEquals(CashTransferError.AMOUNT_NOT_POSITIVE, validateCashTransfer(till, safe, "abc"))
    }

    @Test
    fun `a blank amount reports it as missing, not invalid`() {
        // The distinction drives the UI: an untouched form must not shout at the operator.
        assertEquals(CashTransferError.AMOUNT_MISSING, validateCashTransfer(till, safe, "   "))
    }

    @Test
    fun `a german decimal comma is accepted`() {
        assertNull(validateCashTransfer(till, safe, "100,50"))
    }

    @Test
    fun `a missing source or target is reported before anything else`() {
        assertEquals(CashTransferError.NO_SOURCE, validateCashTransfer(null, safe, "10.00"))
        assertEquals(CashTransferError.NO_TARGET, validateCashTransfer(till, null, "10.00"))
    }

    @Test
    fun `inactive books are not selectable`() {
        val closed = book(3, "Alt", "0.00", isActive = false)
        assertEquals(listOf(till, safe), selectableBooks(listOf(till, safe, closed)))
    }
}
