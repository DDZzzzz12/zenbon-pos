package org.example.project.repository

import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.mapper.toListItemDto
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DocumentRepository.getAllocationCountsByDocuments`: how many bank transactions are allocated to each
 * document's invoice, for a whole page in one go. A reversed allocation is a **deleted row**
 * (`ReversalProcessor` and *Rückgängig* both delete), so the count needs no reversal filter — the test
 * reverses the way production does and expects the count to drop.
 */
@DisplayName("DocumentRepository — allocation counts by document")
class AllocationCountsByDocumentTest {
    private val repo = DocumentRepositoryImpl()
    private val allocations = AllocationRepositoryImpl()
    private lateinit var adminId: UUID

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        adminId = TestData.createAdmin().id.value
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun document(filename: String): Long =
        repo
            .create(
                filename = filename,
                fileType = "INVOICE",
                filePath = "/nonexistent/$filename",
                fileSize = 1024L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = adminId,
                contentHash = "hash-${System.nanoTime()}",
                mediaType = "application/pdf",
            ).id.value

    private fun transactionRow(): BankStatementTransaction {
        val statement =
            BankStatement.new {
                accountIban = "DE89370400440532013000"
                createdAt = currentTimeStamp()
            }
        return BankStatementTransaction.new {
            this.statement = statement
            bookingDate = currentTimeStamp()
            valueDate = currentTimeStamp()
            amount = BigDecimal("50.0000")
            creditDebit = "DEBIT"
        }
    }

    private fun allocate(
        invoiceId: Long,
        transaction: BankStatementTransaction,
    ): Long =
        PaymentAllocation
            .new {
                sourceKind = AllocationSourceKind.BANK_TRANSACTION.name
                bankTransaction = transaction
                targetKind = AllocationTargetKind.INVOICE.name
                invoice = Invoice.findById(invoiceId)
                amount = BigDecimal("50.0000")
                matchedBy = AllocationMatchedBy.MANUAL.name
                createdBy = "admin"
            }.id.value

    @Test
    fun `no invoice, an unallocated invoice, one allocation and two allocations are told apart in one batch`() {
        var none = 0L
        var unallocated = 0L
        var one = 0L
        var two = 0L
        transaction {
            none = document("keine.pdf")
            unallocated = document("offen.pdf")
            one = document("eine.pdf")
            two = document("zwei.pdf")
            repo.createInvoice(sourceDocumentId = unallocated, invoiceNumber = "RE-0")
            val oneInvoice = repo.createInvoice(sourceDocumentId = one, invoiceNumber = "RE-1").id.value
            val twoInvoice = repo.createInvoice(sourceDocumentId = two, invoiceNumber = "RE-2").id.value
            allocate(oneInvoice, transactionRow())
            allocate(twoInvoice, transactionRow())
            allocate(twoInvoice, transactionRow())
        }
        transaction {
            val counts = repo.getAllocationCountsByDocuments(listOf(none, unallocated, one, two))
            assertEquals(0, counts[none] ?: 0, "a document without an invoice has no allocations")
            assertEquals(0, counts[unallocated] ?: 0, "an invoice nobody paid has none")
            assertEquals(1, counts[one])
            assertEquals(2, counts[two])
        }
    }

    @Test
    fun `a transaction split across the same invoice counts once`() {
        var doc = 0L
        transaction {
            doc = document("split.pdf")
            val invoice = repo.createInvoice(sourceDocumentId = doc, invoiceNumber = "RE-S").id.value
            val tx = transactionRow()
            allocate(invoice, tx)
            allocate(invoice, tx)
        }
        transaction { assertEquals(1, repo.getAllocationCountsByDocuments(listOf(doc))[doc]) }
    }

    @Test
    fun `a reversed allocation no longer counts`() {
        var doc = 0L
        var allocationId = 0L
        transaction {
            doc = document("storniert.pdf")
            val invoice = repo.createInvoice(sourceDocumentId = doc, invoiceNumber = "RE-R").id.value
            allocationId = allocate(invoice, transactionRow())
        }
        transaction { assertEquals(1, repo.getAllocationCountsByDocuments(listOf(doc))[doc]) }
        transaction { assertTrue(allocations.delete(allocationId)) }
        transaction { assertEquals(0, repo.getAllocationCountsByDocuments(listOf(doc))[doc] ?: 0) }
    }

    @Test
    fun `an empty id list returns an empty map`() {
        transaction { assertTrue(repo.getAllocationCountsByDocuments(emptyList()).isEmpty()) }
    }

    @Test
    fun `the list item says hasInvoice only for a normalized invoice, not for the legacy metadata fallback`() {
        var normalized = 0L
        var legacyOnly = 0L
        transaction {
            normalized = document("normalisiert.pdf")
            legacyOnly = document("legacy.pdf")
            repo.createInvoice(sourceDocumentId = normalized, invoiceNumber = "RE-N")
            repo.createInvoiceMetadata(documentId = legacyOnly, invoiceNumber = "RE-L")
        }
        transaction {
            val resolver = DocumentKassenbuchLinkResolver { null }
            val ids = listOf(normalized, legacyOnly)
            val invoices = repo.getInvoicesBySourceDocuments(ids)
            val counts = repo.getAllocationCountsByDocuments(ids)

            fun item(id: Long) = repo.findById(id)!!.toListItemDto(resolver, invoices[id], null, counts[id] ?: 0)
            assertTrue(item(normalized).hasInvoice)
            assertEquals(false, item(legacyOnly).hasInvoice, "the legacy fallback fills the columns but is not an invoice row")
        }
    }
}
