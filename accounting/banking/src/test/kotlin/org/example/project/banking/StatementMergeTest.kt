package org.example.project.banking

import kotlinx.datetime.LocalDate
import org.example.project.banking.model.BankStatement
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.BankTransaction
import org.example.project.banking.model.CreditDebit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatementMergeTest {
    private fun entry(
        day: Int,
        amount: String,
    ) = BankTransaction(
        bookingDate = LocalDate(2026, 1, day),
        valueDate = LocalDate(2026, 1, day),
        amount = BigDecimal(amount),
        currency = "EUR",
        creditDebit = CreditDebit.CREDIT,
    )

    private fun day(
        number: String,
        from: Int,
        to: Int,
        opening: String,
        closing: String,
        entries: List<BankTransaction>,
    ) = BankStatement(
        format = BankStatementFormat.CAMT053,
        currency = "EUR",
        transactions = entries,
        accountIban = "DE02120300000000202051",
        statementNumber = number,
        statementDate = LocalDate(2026, 1, to),
        periodStart = LocalDate(2026, 1, from),
        periodEnd = LocalDate(2026, 1, to),
        openingBalance = BigDecimal(opening),
        closingBalance = BigDecimal(closing),
    )

    private val first = day("14", 1, 1, "100.00", "150.00", listOf(entry(1, "50.00")))
    private val second = day("15", 2, 2, "150.00", "175.00", listOf(entry(2, "25.00")))
    private val third = day("16", 3, 3, "175.00", "275.00", listOf(entry(3, "100.00")))

    @Test
    fun `a single statement is returned unchanged`() {
        assertEquals(first, StatementMerge.merge(listOf(first)))
    }

    @Test
    fun `merging spans the period and takes the outer balances`() {
        val merged = StatementMerge.merge(listOf(first, second, third))
        assertEquals(LocalDate(2026, 1, 1), merged.periodStart)
        assertEquals(LocalDate(2026, 1, 3), merged.periodEnd)
        assertEquals(0, BigDecimal("100.00").compareTo(merged.openingBalance))
        assertEquals(0, BigDecimal("275.00").compareTo(merged.closingBalance))
    }

    @Test
    fun `merging preserves every transaction in document order`() {
        val merged = StatementMerge.merge(listOf(first, second, third))
        assertEquals(3, merged.transactions.size)
        assertEquals(listOf(1, 2, 3), merged.transactions.map { it.bookingDate.day })
    }

    @Test
    fun `the merged statement number comes from the first`() {
        assertEquals("14", StatementMerge.merge(listOf(first, second, third)).statementNumber)
    }

    @Test
    fun `account identity and the latest statement date survive the merge`() {
        val merged = StatementMerge.merge(listOf(first, second, third))
        assertEquals("DE02120300000000202051", merged.accountIban)
        assertEquals(LocalDate(2026, 1, 3), merged.statementDate)
    }

    @Test
    fun `merging an empty list is a programming error`() {
        assertFailsWith<IllegalArgumentException> { StatementMerge.merge(emptyList()) }
    }
}
