package org.example.project.banking

import kotlinx.datetime.LocalDate
import org.example.project.banking.model.BankStatement
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.BankTransaction
import org.example.project.banking.model.CreditDebit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class BankStatementModelTest {
    private fun tx(
        amount: String,
        cd: CreditDebit,
    ) = BankTransaction(
        bookingDate = LocalDate(2026, 3, 12),
        valueDate = LocalDate(2026, 3, 13),
        amount = BigDecimal(amount),
        currency = "EUR",
        creditDebit = cd,
    )

    // The sign lives in creditDebit, never in the amount. CAMT.053 carries an explicit CdtDbtInd
    // and always a positive amount; storing a negative amount instead would lose the distinction
    // between a debit and a reversed credit.
    @Test
    fun amountIsAlwaysPositiveAndDirectionIsExplicit() {
        val debit = tx("12.50", CreditDebit.DEBIT)
        assertEquals(BigDecimal("12.50"), debit.amount)
        assertEquals(CreditDebit.DEBIT, debit.creditDebit)
    }

    @Test
    fun statementHoldsItsTransactions() {
        val debit = tx("12.50", CreditDebit.DEBIT)
        val credit = tx("3.00", CreditDebit.CREDIT)
        val s =
            BankStatement(
                format = BankStatementFormat.CAMT053,
                currency = "EUR",
                transactions = listOf(debit, credit),
                accountIban = "DE02120300000000202051",
                statementNumber = "2026/045",
                statementDate = LocalDate(2026, 3, 12),
            )
        assertEquals(listOf(debit, credit), s.transactions)
        assertEquals(CreditDebit.DEBIT, s.transactions[0].creditDebit)
        assertEquals(CreditDebit.CREDIT, s.transactions[1].creditDebit)
    }
}
