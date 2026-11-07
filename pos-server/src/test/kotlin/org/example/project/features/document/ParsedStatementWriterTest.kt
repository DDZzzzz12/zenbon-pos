package org.example.project.features.document

import kotlinx.datetime.LocalDate
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.BankTransaction
import org.example.project.banking.model.CreditDebit
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.ParsedStatementWriter
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.example.project.banking.model.BankStatement as ParsedBankStatement

/**
 * Pins the bridge between the `:accounting-banking` parser and the normalized bank-statement model.
 *
 * Deliberately split across separate transactions: within one transaction Exposed's identity map
 * can serve back the entity just written rather than what the columns actually stored, so an
 * assertion would pass without the data having persisted.
 */
class ParsedStatementWriterTest {
    private val repo = DocumentRepositoryImpl()
    private val writer = ParsedStatementWriter(repo)

    private val day = LocalDate(2026, 1, 10)

    /** A fully-populated parse result — every optional term filled so nothing can be silently dropped. */
    private fun statement(entries: List<BankTransaction>) =
        ParsedBankStatement(
            format = BankStatementFormat.CAMT053,
            currency = "EUR",
            transactions = entries,
            accountIban = "DE02120300000000202051",
            accountBic = "BYLADEM1001",
            bankName = "Musterbank",
            accountNumber = "0000202051",
            statementNumber = "STMT-14",
            legalSequenceNumber = "14",
            statementDate = LocalDate(2026, 1, 31),
            periodStart = LocalDate(2026, 1, 1),
            periodEnd = LocalDate(2026, 1, 31),
            openingBalance = BigDecimal("1000.00"),
            closingBalance = BigDecimal("1050.00"),
        )

    private fun credit(amount: String) =
        BankTransaction(
            bookingDate = day,
            valueDate = day,
            amount = BigDecimal(amount),
            currency = "EUR",
            creditDebit = CreditDebit.CREDIT,
            status = "BOOK",
            bankTxDomain = "PMNT",
            bankTxFamily = "RCDT",
            bankTxSubFamily = "ESCT",
            accountServicerReference = "REF-1",
            endToEndId = "E2E-1",
            mandateId = "MND-1",
            creditorId = "DE98ZZZ09999999999",
            counterpartyName = "Kunde Schmidt",
            counterpartyIban = "DE89370400440532013000",
            counterpartyBic = "COBADEFFXXX",
            remittanceUnstructured = "Rechnung 2026-0042",
            remittanceStructuredRef = "RF18539007547034",
            reversalIndicator = false,
            rawEntry = "<Ntry/>",
        )

    /** Creates a document row for the statement to hang off. */
    private fun sourceDocument(uploadedBy: UUID): Long {
        val document =
            repo.create(
                filename = "Kontoauszug.xml",
                fileType = "BANK_STATEMENT",
                filePath = "/tmp/statement-${System.nanoTime()}.xml",
                fileSize = 2048L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = uploadedBy,
                contentHash = "hash-${System.nanoTime()}",
            )
        return document.id.value
    }

    @Test
    fun `a parsed statement is written with its header and entries`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            val documentId = transaction { sourceDocument(admin!!) }

            val id = transaction { writer.write(statement(listOf(credit("50.00"))), sourceDocumentId = documentId) }

            transaction {
                val stored = assertNotNull(repo.getBankStatement(id))
                assertEquals("camt053", stored.format)
                assertEquals("DE02120300000000202051", stored.accountIban)
                assertEquals("BYLADEM1001", stored.accountBic)
                assertEquals("Musterbank", stored.bankName)
                assertEquals("STMT-14", stored.statementNumber)
                assertEquals("14", stored.legalSequenceNumber)
                assertEquals("EUR", stored.currency)
                assertEquals(0, BigDecimal("1000.00").compareTo(assertNotNull(stored.openingBalance)))
                assertEquals(0, BigDecimal("1050.00").compareTo(assertNotNull(stored.closingBalance)))

                val entries = repo.getTransactions(id)
                assertEquals(1, entries.size)
                val entry = entries.first()
                assertEquals("CREDIT", entry.creditDebit)
                assertEquals(0, BigDecimal("50.00").compareTo(entry.amount))
                assertEquals("Kunde Schmidt", entry.counterpartyName)
                assertEquals("DE89370400440532013000", entry.counterpartyIban)
                assertEquals("Rechnung 2026-0042", entry.remittanceUnstructured)
                assertEquals("RF18539007547034", entry.remittanceStructuredRef)
                assertEquals("E2E-1", entry.endToEndId)
                assertEquals("PMNT", entry.bankTxDomain)
                assertEquals("<Ntry/>", entry.rawEntry)
            }
        }
    }

    @Test
    fun `re-parsing the same source document replaces the statement instead of duplicating it`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            val documentId = transaction { sourceDocument(admin!!) }

            val firstId = transaction { writer.write(statement(listOf(credit("50.00"))), sourceDocumentId = documentId) }
            val secondId =
                transaction {
                    writer.write(
                        statement(listOf(credit("50.00"), credit("25.00"))),
                        sourceDocumentId = documentId,
                    )
                }

            assertEquals(firstId, secondId, "the same source document must keep one statement row")
            transaction {
                assertEquals(2, repo.getTransactions(firstId).size)
                assertEquals(
                    firstId,
                    assertNotNull(repo.getBankStatementBySourceDocument(documentId)).id.value,
                )
            }
        }
    }

    @Test
    fun `entry amounts stay positive and direction lives in the credit-debit column`() {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            val documentId = transaction { sourceDocument(admin!!) }
            val debit = credit("50.00").copy(creditDebit = CreditDebit.DEBIT)

            val id = transaction { writer.write(statement(listOf(debit)), sourceDocumentId = documentId) }

            transaction {
                val stored = repo.getTransactions(id).first()
                assertEquals("DEBIT", stored.creditDebit)
                assertEquals(1, stored.amount.signum(), "the stored amount must remain positive")
            }
        }
    }
}
