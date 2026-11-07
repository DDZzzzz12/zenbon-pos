package org.example.project.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repository test for the bank statement side of [DocumentRepositoryImpl].
 *
 * The old model kept transactions in a JSON blob whose DTO carried only four fields, so the
 * counterparty data present in the source statement was silently discarded at the DTO boundary.
 * These tests pin that nothing is dropped any more.
 *
 * Both round trips deliberately span two transactions: within one transaction Exposed's identity
 * map can serve the entity straight back from memory, asserting what we wrote rather than what the
 * database actually stored.
 */
class BankStatementRepositoryTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `counterparty and direction survive a round trip`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val statementId =
                transaction {
                    val stmt =
                        repo.createBankStatement(
                            sourceDocumentId = null,
                            format = "camt053",
                            accountIban = "DE02120300000000202051",
                        )
                    repo.replaceTransactions(
                        stmt.id.value,
                        listOf(
                            DocumentRepository.BankTransactionRow(
                                bookingDate = LocalDateTime(2026, 3, 12, 0, 0),
                                valueDate = LocalDateTime(2026, 3, 13, 0, 0),
                                amount = BigDecimal("12.5000"),
                                currency = "EUR",
                                creditDebit = "DEBIT",
                                counterpartyName = "Deutsche Post AG",
                                counterpartyIban = "DE99500105175417123456",
                                endToEndId = "E2E-1",
                            ),
                        ),
                    )
                    stmt.id.value
                }

            transaction {
                val rows = repo.getBankStatement(statementId)!!.transactions.toList()
                assertEquals(1, rows.size)
                assertEquals("Deutsche Post AG", rows[0].counterpartyName)
                assertEquals("DEBIT", rows[0].creditDebit)
                assertEquals("E2E-1", rows[0].endToEndId)
            }
        }
    }

    /**
     * The defect this task exists to prevent is a mapper that drops fields. One populated field
     * would not catch nineteen dropped ones, so this writes every column and reads each back.
     */
    @Test
    fun `every transaction field survives a round trip`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val statementId =
                transaction {
                    val stmt = repo.createBankStatement(sourceDocumentId = null, format = "camt053")
                    repo.replaceTransactions(
                        stmt.id.value,
                        listOf(
                            DocumentRepository.BankTransactionRow(
                                bookingDate = LocalDateTime(2026, 3, 12, 8, 30),
                                valueDate = LocalDateTime(2026, 3, 13, 9, 45),
                                amount = BigDecimal("1234.5678"),
                                currency = "CHF",
                                creditDebit = "CREDIT",
                                status = "BOOK",
                                bankTxDomain = "PMNT",
                                bankTxFamily = "RCDT",
                                bankTxSubFamily = "ESCT",
                                accountServicerReference = "ASR-77",
                                endToEndId = "E2E-77",
                                mandateId = "MND-77",
                                creditorId = "DE98ZZZ09999999999",
                                counterpartyName = "Deutsche Post AG",
                                counterpartyIban = "DE99500105175417123456",
                                counterpartyBic = "DEUTDEFFXXX",
                                remittanceUnstructured = "Rechnung RE-2026-0042",
                                remittanceStructuredRef = "RF18539007547034",
                                reversalIndicator = true,
                                rawEntry = "<Ntry>raw</Ntry>",
                            ),
                        ),
                    )
                    stmt.id.value
                }

            transaction {
                val rows = repo.getBankStatement(statementId)!!.transactions.toList()
                val row = rows.single()
                assertEquals(LocalDateTime(2026, 3, 12, 8, 30), row.bookingDate)
                assertEquals(LocalDateTime(2026, 3, 13, 9, 45), row.valueDate)
                assertEquals(BigDecimal("1234.5678"), row.amount)
                assertEquals("CHF", row.currency)
                assertEquals("CREDIT", row.creditDebit)
                assertEquals("BOOK", row.status)
                assertEquals("PMNT", row.bankTxDomain)
                assertEquals("RCDT", row.bankTxFamily)
                assertEquals("ESCT", row.bankTxSubFamily)
                assertEquals("ASR-77", row.accountServicerReference)
                assertEquals("E2E-77", row.endToEndId)
                assertEquals("MND-77", row.mandateId)
                assertEquals("DE98ZZZ09999999999", row.creditorId)
                assertEquals("Deutsche Post AG", row.counterpartyName)
                assertEquals("DE99500105175417123456", row.counterpartyIban)
                assertEquals("DEUTDEFFXXX", row.counterpartyBic)
                assertEquals("Rechnung RE-2026-0042", row.remittanceUnstructured)
                assertEquals("RF18539007547034", row.remittanceStructuredRef)
                assertTrue(row.reversalIndicator)
                assertEquals("<Ntry>raw</Ntry>", row.rawEntry)
            }
        }
    }

    /**
     * Entries are owned by the statement, so a second write replaces the whole set. A mapper that
     * appended instead would still satisfy the two round-trip tests above, which each call
     * [DocumentRepository.replaceTransactions] only once.
     */
    @Test
    fun `replaceTransactions replaces rather than appends`() {
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { _ ->
            val statementId =
                transaction {
                    val stmt = repo.createBankStatement(sourceDocumentId = null, format = "camt053")
                    repo.replaceTransactions(
                        stmt.id.value,
                        listOf(
                            transactionRow(endToEndId = "E2E-OLD-1", counterpartyName = "Alte Post AG"),
                            transactionRow(endToEndId = "E2E-OLD-2", counterpartyName = "Alte Bahn AG"),
                        ),
                    )
                    repo.replaceTransactions(
                        stmt.id.value,
                        listOf(transactionRow(endToEndId = "E2E-NEW", counterpartyName = "Neue Post AG")),
                    )
                    stmt.id.value
                }

            transaction {
                val rows = repo.getBankStatement(statementId)!!.transactions.toList()
                assertEquals(1, rows.size)
                assertEquals("E2E-NEW", rows[0].endToEndId)
                assertEquals("Neue Post AG", rows[0].counterpartyName)
            }
        }
    }

    private fun transactionRow(
        endToEndId: String,
        counterpartyName: String,
    ) = DocumentRepository.BankTransactionRow(
        bookingDate = LocalDateTime(2026, 3, 12, 0, 0),
        valueDate = LocalDateTime(2026, 3, 13, 0, 0),
        amount = BigDecimal("12.5000"),
        creditDebit = "DEBIT",
        endToEndId = endToEndId,
        counterpartyName = counterpartyName,
    )
}
