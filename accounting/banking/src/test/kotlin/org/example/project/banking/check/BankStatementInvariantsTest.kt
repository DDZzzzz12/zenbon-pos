package org.example.project.banking.check

import kotlinx.datetime.LocalDate
import org.example.project.banking.model.BankStatement
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.BankTransaction
import org.example.project.banking.model.CreditDebit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankStatementInvariantsTest {
    private val day = LocalDate(2026, 1, 15)

    private fun entry(
        amount: String,
        direction: CreditDebit,
    ) = BankTransaction(
        bookingDate = day,
        valueDate = day,
        amount = BigDecimal(amount),
        currency = "EUR",
        creditDebit = direction,
    )

    private fun statement(
        opening: String?,
        closing: String?,
        entries: List<BankTransaction>,
    ) = BankStatement(
        format = BankStatementFormat.CAMT053,
        currency = "EUR",
        transactions = entries,
        openingBalance = opening?.let(::BigDecimal),
        closingBalance = closing?.let(::BigDecimal),
    )

    @Test
    fun `a statement whose entries sum to the closing balance passes`() {
        val s = statement("100.00", "130.00", listOf(entry("50.00", CreditDebit.CREDIT), entry("20.00", CreditDebit.DEBIT)))
        assertNull(BankStatementInvariants.check(s))
    }

    @Test
    fun `a one cent discrepancy is reported`() {
        val s = statement("100.00", "130.01", listOf(entry("50.00", CreditDebit.CREDIT), entry("20.00", CreditDebit.DEBIT)))
        val violation = BankStatementInvariants.check(s)
        assertNotNull(violation)
        assertTrue("130.01" in violation, "violation should quote the stated closing balance: $violation")
    }

    @Test
    fun `differing scale is not a discrepancy`() {
        val s = statement("100.0", "150.000", listOf(entry("50.00", CreditDebit.CREDIT)))
        assertNull(BankStatementInvariants.check(s))
    }

    @Test
    fun `a negative closing balance is handled`() {
        val s = statement("10.00", "-40.00", listOf(entry("50.00", CreditDebit.DEBIT)))
        assertNull(BankStatementInvariants.check(s))
    }

    @Test
    fun `a parsed statement with no stated balances is refused`() {
        // Both wire formats mandate balances, so an absent one means a misparse. It also makes the
        // balances a guarantee for the Finanzen Bank tab's continuity check (statement n's closing
        // == statement n+1's opening), which silently degrades to nothing against null balances.
        val violation = BankStatementInvariants.check(statement(null, null, listOf(entry("50.00", CreditDebit.CREDIT))))
        assertNotNull(violation)
        assertTrue("balance" in violation, "violation should name the missing balance: $violation")
    }

    @Test
    fun `a manually-entered statement with no balances is still accepted`() {
        val manual =
            BankStatement(
                format = BankStatementFormat.MANUAL,
                currency = "EUR",
                transactions = listOf(entry("50.00", CreditDebit.CREDIT)),
            )
        assertNull(BankStatementInvariants.check(manual), "MANUAL and PDF never come from this parser")
    }

    @Test
    fun `a transaction in another currency is reported`() {
        val foreign = entry("50.00", CreditDebit.CREDIT).copy(currency = "CHF")
        val s = statement("100.00", "150.00", listOf(foreign))
        val violation = BankStatementInvariants.check(s)
        assertNotNull(violation)
        assertTrue("CHF" in violation, "violation should name the offending currency: $violation")
    }
}
