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
import kotlin.test.assertNull
import org.example.project.banking.model.BankStatement as ParsedBankStatement

/**
 * Pins the overlap/duplicate marking that runs at the end of [ParsedStatementWriter.write].
 *
 * Two properties matter more than the rest and each has its own test: nothing is ever removed, and
 * a statement is never compared against itself (a re-parse replaces its own rows, so comparing
 * against them would mark every entry a duplicate of itself).
 *
 * Writes and reads sit in separate transactions for the same reason as [ParsedStatementWriterTest]:
 * within one transaction Exposed's identity map can serve back what was just written rather than
 * what the columns stored.
 */
class StatementDuplicateMarkerTest {
    private val repo = DocumentRepositoryImpl()
    private val writer = ParsedStatementWriter(repo)

    private val iban = "DE02120300000000202051"

    /** Creates a document row for a statement to hang off. */
    private fun sourceDocument(uploadedBy: UUID): Long =
        repo
            .create(
                filename = "Kontoauszug.xml",
                fileType = "BANK_STATEMENT",
                filePath = "/tmp/statement-${System.nanoTime()}.xml",
                fileSize = 2048L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = uploadedBy,
                contentHash = "hash-${System.nanoTime()}",
            ).id
            .value

    private fun entry(
        amount: String,
        day: String,
        ref: String? = null,
        counterparty: String? = "DE89370400440532013000",
        remittance: String? = "Rechnung 2026-0042",
    ) = BankTransaction(
        bookingDate = LocalDate.parse(day),
        valueDate = LocalDate.parse(day),
        amount = BigDecimal(amount),
        currency = "EUR",
        creditDebit = CreditDebit.CREDIT,
        accountServicerReference = ref,
        counterpartyName = "Kunde Schmidt",
        counterpartyIban = counterparty,
        remittanceUnstructured = remittance,
    )

    /**
     * [from]/[to] are nullable because a real CAMT.053 file may state no `FrToDt` at all — the
     * corpus fixture does not — and the overlap decision has to work for that shape.
     */
    private fun statementModel(
        account: String,
        from: String?,
        to: String?,
        entries: List<BankTransaction>,
        format: BankStatementFormat = BankStatementFormat.CAMT053,
    ) = ParsedBankStatement(
        format = format,
        currency = "EUR",
        transactions = entries,
        accountIban = account,
        accountNumber = account.takeLast(10),
        statementDate = to?.let(LocalDate::parse),
        periodStart = from?.let(LocalDate::parse),
        periodEnd = to?.let(LocalDate::parse),
        openingBalance = BigDecimal("1000.00"),
        closingBalance = BigDecimal("1050.00"),
    )

    /** Writes one statement against a fresh source document, exactly as an upload would. */
    private fun uploadStatement(
        admin: UUID,
        account: String,
        from: String?,
        to: String?,
        entries: List<BankTransaction>,
        format: BankStatementFormat = BankStatementFormat.CAMT053,
    ): Long {
        val documentId = transaction { sourceDocument(admin) }
        return transaction {
            writer.write(statementModel(account, from, to, entries, format), sourceDocumentId = documentId)
        }
    }

    private fun withApp(block: (UUID) -> Unit) {
        var admin: UUID? = null
        testApp(setupData = {
            TestData.createShopInfo()
            admin = TestData.createAdmin().id.value
        }) { _ ->
            block(admin!!)
        }
    }

    @Test
    fun `a second statement covering the same period points at the first`() {
        withApp { admin ->
            val januaryId = uploadStatement(admin, iban, "2026-01-01", "2026-01-31", listOf(entry("50.00", "2026-01-10")))
            val quarterId = uploadStatement(admin, iban, "2026-01-01", "2026-03-31", listOf(entry("50.00", "2026-01-10")))

            transaction {
                val quarter = assertNotNull(repo.getBankStatement(quarterId))
                assertEquals(januaryId, quarter.overlappingStatement?.id?.value)
            }
        }
    }

    @Test
    fun `a non-overlapping statement is not flagged`() {
        withApp { admin ->
            uploadStatement(admin, iban, "2026-01-01", "2026-01-31", listOf(entry("50.00", "2026-01-10")))
            val februaryId = uploadStatement(admin, iban, "2026-02-01", "2026-02-28", listOf(entry("20.00", "2026-02-10")))

            transaction {
                assertNull(assertNotNull(repo.getBankStatement(februaryId)).overlappingStatement)
            }
        }
    }

    @Test
    fun `a statement for a different account is not flagged`() {
        withApp { admin ->
            uploadStatement(admin, iban, "2026-01-01", "2026-01-31", listOf(entry("50.00", "2026-01-10")))
            val otherId =
                uploadStatement(
                    admin,
                    "DE89370400440532013000",
                    "2026-01-01",
                    "2026-01-31",
                    listOf(entry("50.00", "2026-01-10")),
                )

            transaction {
                assertNull(assertNotNull(repo.getBankStatement(otherId)).overlappingStatement)
            }
        }
    }

    @Test
    fun `a repeated transaction is marked but not dropped`() {
        withApp { admin ->
            val firstId =
                uploadStatement(admin, iban, "2026-01-01", "2026-01-31", listOf(entry("50.00", "2026-01-10", ref = "REF-1")))
            val secondId =
                uploadStatement(
                    admin,
                    iban,
                    "2026-01-01",
                    "2026-03-31",
                    listOf(entry("50.00", "2026-01-10", ref = "REF-1"), entry("75.00", "2026-02-05", ref = "REF-2")),
                )

            transaction {
                val original = repo.getTransactions(firstId).single()
                val reimported = repo.getTransactions(secondId)
                assertEquals(2, reimported.size, "nothing may be dropped")
                assertEquals(
                    original.id.value,
                    reimported
                        .first()
                        .duplicateOfTransaction
                        ?.id
                        ?.value,
                )
                assertNull(reimported[1].duplicateOfTransaction, "the February entry is new")
            }
        }
    }

    @Test
    fun `a second identical payment in the re-export is not marked against the same known row`() {
        // January holds ONE coffee-machine top-up; the January-March re-export holds TWO — one
        // repeat and one genuinely new payment the January file predates. Set-membership matching
        // marks both against the single known row, and because the reconciliation queue filters on
        // `duplicate_of_transaction_id IS NULL`, that second payment would never reach it. Matching
        // pairs off instead, so N known copies and M new copies mark exactly min(N, M).
        withApp { admin ->
            uploadStatement(
                admin,
                iban,
                "2026-01-01",
                "2026-01-31",
                listOf(entry("12.50", "2026-01-10", ref = null)),
                format = BankStatementFormat.MT940,
            )
            val quarterId =
                uploadStatement(
                    admin,
                    iban,
                    "2026-01-01",
                    "2026-03-31",
                    listOf(entry("12.50", "2026-01-10", ref = null), entry("12.50", "2026-01-10", ref = null)),
                    format = BankStatementFormat.MT940,
                )

            transaction {
                val rows = repo.getTransactions(quarterId)
                assertEquals(2, rows.size, "nothing may be dropped")
                assertNotNull(rows[0].duplicateOfTransaction, "the first copy repeats January's entry")
                assertNull(
                    rows[1].duplicateOfTransaction,
                    "the second is a genuinely additional payment and must stay visible to reconciliation",
                )
            }
        }
    }

    @Test
    fun `an MT940 repeat with no bank reference is matched on the composite key`() {
        withApp { admin ->
            uploadStatement(
                admin,
                iban,
                "2026-01-01",
                "2026-01-31",
                listOf(entry("50.00", "2026-01-10", ref = null)),
                format = BankStatementFormat.MT940,
            )
            val secondId =
                uploadStatement(
                    admin,
                    iban,
                    "2026-01-01",
                    "2026-02-28",
                    listOf(entry("50.00", "2026-01-10", ref = null)),
                    format = BankStatementFormat.MT940,
                )

            transaction {
                assertNotNull(
                    repo.getTransactions(secondId).single().duplicateOfTransaction,
                    "with no AcctSvcrRef the composite key must still recognise the repeat",
                )
            }
        }
    }

    @Test
    fun `two statements that state no period are compared on the dates of their entries`() {
        // The real-file case: `pos-server/src/test/resources/corpus/camt053-statement.xml` carries no
        // `<FrToDt>`, so both statements land with a null period. Without a derived period nothing
        // would ever be flagged, and every repeat would reach reconciliation as fresh work.
        withApp { admin ->
            val januaryId =
                uploadStatement(admin, iban, null, null, listOf(entry("50.00", "2026-01-10", ref = "REF-1")))
            val reExportId =
                uploadStatement(
                    admin,
                    iban,
                    null,
                    null,
                    listOf(entry("50.00", "2026-01-10", ref = "REF-1"), entry("75.00", "2026-01-20", ref = "REF-2")),
                )

            transaction {
                val reExport = assertNotNull(repo.getBankStatement(reExportId))
                assertEquals(
                    januaryId,
                    reExport.overlappingStatement?.id?.value,
                    "with no stated period the entry dates must decide the overlap",
                )
                val rows = repo.getTransactions(reExportId)
                assertEquals(2, rows.size, "nothing may be dropped")
                assertNotNull(rows[0].duplicateOfTransaction, "the repeated entry must be marked")
                assertNull(rows[1].duplicateOfTransaction, "the second entry is genuinely new")
            }
        }
    }

    @Test
    fun `a stated period on one side and a derived one on the other still match`() {
        withApp { admin ->
            val januaryId =
                uploadStatement(admin, iban, "2026-01-01", "2026-01-31", listOf(entry("50.00", "2026-01-10", ref = "REF-1")))
            val undatedId =
                uploadStatement(admin, iban, null, null, listOf(entry("50.00", "2026-01-10", ref = "REF-1")))

            transaction {
                assertEquals(
                    januaryId,
                    assertNotNull(repo.getBankStatement(undatedId)).overlappingStatement?.id?.value,
                    "the fallback must apply on either side of the comparison",
                )
                assertNotNull(
                    repo.getTransactions(undatedId).single().duplicateOfTransaction,
                    "the repeat must still be marked when only one side states a period",
                )
            }
        }
    }

    @Test
    fun `two statements with no period whose entries do not overlap are not flagged`() {
        withApp { admin ->
            uploadStatement(admin, iban, null, null, listOf(entry("50.00", "2026-01-10", ref = "REF-1")))
            val marchId = uploadStatement(admin, iban, null, null, listOf(entry("20.00", "2026-03-10", ref = "REF-9")))

            transaction {
                assertNull(
                    assertNotNull(repo.getBankStatement(marchId)).overlappingStatement,
                    "deriving a period must not make every undated statement overlap",
                )
                assertNull(repo.getTransactions(marchId).single().duplicateOfTransaction)
            }
        }
    }

    @Test
    fun `a stated period is used even when it is much wider than the entries it holds`() {
        // Not asked for, but it pins the precedence: the derivation is a fallback, never a
        // narrowing. A January statement holding one entry on the 5th still covers the whole month,
        // so an undated statement whose entries fall on the 25th overlaps it. Preferring the derived
        // span would shrink real coverage and miss the overlap.
        withApp { admin ->
            val januaryId =
                uploadStatement(admin, iban, "2026-01-01", "2026-01-31", listOf(entry("50.00", "2026-01-05", ref = "REF-1")))
            val lateId = uploadStatement(admin, iban, null, null, listOf(entry("31.00", "2026-01-25", ref = "REF-7")))

            transaction {
                assertEquals(
                    januaryId,
                    assertNotNull(repo.getBankStatement(lateId)).overlappingStatement?.id?.value,
                    "the stated period must win over the narrower span of its own entries",
                )
                assertNull(
                    repo.getTransactions(lateId).single().duplicateOfTransaction,
                    "an overlapping period alone must not mark an entry the other statement never held",
                )
            }
        }
    }

    @Test
    fun `a statement with neither a period nor entries is not flagged`() {
        withApp { admin ->
            uploadStatement(admin, iban, null, null, listOf(entry("50.00", "2026-01-10", ref = "REF-1")))
            val emptyId = uploadStatement(admin, iban, null, null, entries = emptyList())

            transaction {
                assertNull(
                    assertNotNull(repo.getBankStatement(emptyId)).overlappingStatement,
                    "with nothing to derive a period from there is nothing to compare",
                )
                assertEquals(0, repo.getTransactions(emptyId).size)
            }
        }
    }

    @Test
    fun `re-parsing one document does not mark its own entries as duplicates of themselves`() {
        withApp { admin ->
            val documentId = transaction { sourceDocument(admin) }
            val statement = statementModel(iban, "2026-01-01", "2026-01-31", listOf(entry("50.00", "2026-01-10", ref = "REF-1")))

            val id = transaction { writer.write(statement, sourceDocumentId = documentId) }
            transaction { writer.write(statement, sourceDocumentId = documentId) }

            transaction {
                assertNull(
                    repo.getTransactions(id).single().duplicateOfTransaction,
                    "a replaced statement must not be compared against the rows it replaced",
                )
            }
        }
    }
}
