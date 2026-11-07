package org.example.project.features.document

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the document detail and list DTOs to the **normalized** bank-statement model.
 *
 * The sibling of `DocumentDetailInvoiceSourceTest` / `DocumentListItemInvoiceSourceTest`, one model
 * later. `ParsedStatementWriter` now writes every uploaded CAMT.053/MT940 into `bank_statement` +
 * `bank_statement_transaction`, but the document DTOs still read only the legacy
 * `document_bank_statement_metadata` blob — so a parsed statement was written and never displayed.
 *
 * Four things have to hold at once:
 *
 * 1. a document whose statement exists only on the new model populates the detail DTO, including the
 *    per-transaction counterparty detail the four-field legacy blob could never express;
 * 2. a document with only a legacy row still renders from it, because adminApp's
 *    `BankStatementMetadataForm` is the only manual-entry path and still writes there;
 * 3. when both exist, the **normalized** statement wins whole-record — the assertions below pick
 *    fields that differ between the two rows, so per-field coalescing fails this test;
 * 4. the list path shows the normalized statement too, not just the detail panel.
 */
@DisplayName("Document DTOs — bank statement metadata reads the normalized model")
class BankStatementMapperTest {
    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

    private fun document(filename: String): Long =
        repo
            .create(
                filename = filename,
                fileType = "BANK_STATEMENT",
                filePath = "/nonexistent/$filename",
                fileSize = 2048L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = adminId,
                contentHash = "hash-${System.nanoTime()}",
                mediaType = "application/xml",
            ).id.value

    private fun documentService(): DocumentManagementService = GlobalContext.get().get()

    /** A normalized statement with one credit entry carrying full counterparty detail. */
    private fun createNormalizedStatement(documentId: Long) {
        val statement =
            repo.createBankStatement(
                sourceDocumentId = documentId,
                format = "camt053",
                accountIban = "DE02120300000000202051",
                accountBic = "BYLADEM1001",
                bankName = "Neu Normalisiert Bank",
                statementNumber = "STMT-2026-14",
                legalSequenceNumber = "14",
                statementDate = LocalDateTime.parse("2026-03-31T00:00:00"),
                periodStart = LocalDateTime.parse("2026-03-01T00:00:00"),
                periodEnd = LocalDateTime.parse("2026-03-31T00:00:00"),
                openingBalance = BigDecimal("1000.00"),
                closingBalance = BigDecimal("1050.00"),
                currency = "EUR",
            )
        repo.replaceTransactions(
            statement.id.value,
            listOf(
                DocumentRepository.BankTransactionRow(
                    bookingDate = LocalDateTime.parse("2026-03-15T00:00:00"),
                    valueDate = LocalDateTime.parse("2026-03-16T00:00:00"),
                    amount = BigDecimal("50.00"),
                    currency = "EUR",
                    creditDebit = "CREDIT",
                    counterpartyName = "Kunde Schmidt",
                    counterpartyIban = "DE89370400440532013000",
                    remittanceUnstructured = "Rechnung 2026-0042",
                    endToEndId = "E2E-1",
                ),
            ),
        )
    }

    /** A legacy blob row, deliberately disagreeing with [createNormalizedStatement] on every field. */
    private fun createLegacyMetadata(documentId: Long) {
        repo.createBankStatementMetadata(
            documentId = documentId,
            bankName = "Alt Legacy Bank",
            bic = "GENODEF1M01",
            iban = "DE44500105175407324931",
            statementNumber = "ALT-99",
            statementDate = LocalDateTime.parse("2026-01-31T00:00:00"),
            openingBalance = BigDecimal("11.11"),
            closingBalance = BigDecimal("22.22"),
            currency = "EUR",
            transactionsJson =
                """[{"date":"2026-01-05T00:00","description":"Alte Buchung","amount":"-9.99","type":"Lastschrift"}]""",
        )
    }

    @Test
    fun `a statement that exists only on the new model populates the detail metadata`() {
        var documentId = 0L
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("nur-neues-modell-auszug.xml")
                    createNormalizedStatement(documentId)
                }
            },
        ) { _ ->
            val metadata = documentService().getDocumentById(documentId).bankStatementMetadata
            assertNotNull(metadata, "a statement on the new model must produce bank statement metadata")

            assertEquals("Neu Normalisiert Bank", metadata.bankName)
            assertEquals("DE02120300000000202051", metadata.iban, "iban comes from BankStatement.accountIban")
            assertEquals("BYLADEM1001", metadata.bic, "bic comes from BankStatement.accountBic")
            assertEquals("STMT-2026-14", metadata.statementNumber)
            assertEquals("2026-03-31T00:00", metadata.statementDate)
            assertEquals("2026-03-01T00:00", metadata.periodStart)
            assertEquals("2026-03-31T00:00", metadata.periodEnd)
            assertEquals("1000.00", metadata.openingBalance)
            assertEquals("1050.00", metadata.closingBalance)
            assertEquals("EUR", metadata.currency)

            val entry = assertNotNull(metadata.transactions).single()
            assertEquals("2026-03-15T00:00", entry.bookingDate)
            assertEquals("2026-03-16T00:00", entry.valueDate)
            assertEquals("CREDIT", entry.creditDebit)
            assertEquals(
                "Kunde Schmidt",
                entry.counterpartyName,
                "the rich transaction fields must survive to the DTO — the legacy blob had no room for them",
            )
            assertEquals("DE89370400440532013000", entry.counterpartyIban)
            assertEquals("Rechnung 2026-0042", entry.remittanceUnstructured)
            assertEquals("E2E-1", entry.endToEndId)
        }
    }

    @Test
    fun `a document with no normalized statement still falls back to its legacy row`() {
        var documentId = 0L
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("nur-legacy-auszug.pdf")
                    createLegacyMetadata(documentId)
                }
            },
        ) { _ ->
            val metadata = documentService().getDocumentMetadata(documentId).bankStatementMetadata
            assertNotNull(metadata)
            assertEquals(
                "Alt Legacy Bank",
                metadata.bankName,
                "the manual capture form still writes the legacy table; a hand-entered statement must render",
            )
            assertEquals("22.22", metadata.closingBalance)

            val entry = assertNotNull(metadata.transactions).single()
            assertEquals("2026-01-05T00:00", entry.bookingDate, "the legacy blob's single date fills both dates")
            assertEquals("2026-01-05T00:00", entry.valueDate)
            assertEquals("9.99", entry.amount, "a signed legacy amount is split into magnitude and direction")
            assertEquals("DBIT", entry.creditDebit, "the direction is derived from the legacy amount's sign")
            assertEquals("EUR", entry.currency, "the legacy blob has no per-line currency; the statement's applies")
            assertEquals("Alte Buchung", entry.remittanceUnstructured)
        }
    }

    @Test
    fun `the normalized statement wins over a legacy metadata row for the same document`() {
        var documentId = 0L
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("beides-vorhanden-auszug.xml")
                    createLegacyMetadata(documentId)
                    createNormalizedStatement(documentId)
                }
            },
        ) { _ ->
            val metadata = documentService().getDocumentById(documentId).bankStatementMetadata
            assertNotNull(metadata)

            // Every assertion below names a field the legacy row also carries, with a different value:
            // per-field coalescing would let at least one legacy value through and fail here.
            assertEquals("Neu Normalisiert Bank", metadata.bankName, "the parsed statement is the one to render")
            assertEquals("DE02120300000000202051", metadata.iban)
            assertEquals("BYLADEM1001", metadata.bic)
            assertEquals("STMT-2026-14", metadata.statementNumber)
            assertEquals("2026-03-31T00:00", metadata.statementDate)
            assertEquals("1000.00", metadata.openingBalance)
            assertEquals("1050.00", metadata.closingBalance)
            assertEquals(
                "Kunde Schmidt",
                assertNotNull(metadata.transactions).single().counterpartyName,
                "the whole record comes from one source — including its transactions",
            )
        }
    }

    @Test
    fun `the document list shows the normalized statement`() {
        var documentId = 0L
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value
                transaction {
                    documentId = document("liste-auszug.xml")
                    createLegacyMetadata(documentId)
                    createNormalizedStatement(documentId)
                }
            },
        ) { _ ->
            val row =
                documentService()
                    .searchDocuments(DocumentDto.DocumentSearchFilterDto(fileType = "BANK_STATEMENT"))
                    .items
                    .single { it.id == documentId }

            assertEquals("Neu Normalisiert Bank", row.bankName, "the list reads the normalized statement too")
            assertEquals("2026-03-31T00:00", row.statementDate)
            assertEquals("1050.00", row.closingBalance)
            assertEquals(
                "2026-03-31T00:00",
                row.date,
                "a bank statement row's date column falls back to its statement date",
            )
        }
    }
}
