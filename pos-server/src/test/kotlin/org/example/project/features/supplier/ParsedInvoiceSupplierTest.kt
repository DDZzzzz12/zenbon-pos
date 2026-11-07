package org.example.project.features.supplier

import kotlinx.datetime.LocalDate
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceParty
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.data.service.ParsedInvoiceWriter
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The parse path must leave every invoice linked to a creditor.
 *
 * Goes through [ParsedInvoiceWriter.write] rather than calling the resolver directly — the point of
 * this task is the *wiring*, so a test that bypassed the writer would pass whether or not the
 * wiring exists.
 *
 * Each assertion runs in its own transaction, following [ParsedInvoiceWriter]'s own test: inside one
 * transaction Exposed's identity map can serve back the entity just written rather than what the
 * columns actually stored.
 */
class ParsedInvoiceSupplierTest {
    private val repo = DocumentRepositoryImpl()
    private val writer = ParsedInvoiceWriter(repo)

    private fun parsed(
        invoiceNumber: String,
        vatId: String? = "DE123456789",
        iban: String? = null,
    ) = EInvoice(
        format = EInvoiceFormat.FACTUR_X,
        invoiceNumber = invoiceNumber,
        issueDate = LocalDate(2026, 3, 4),
        currency = "EUR",
        seller =
            EInvoiceParty(
                name = "Metro AG",
                vatId = vatId,
                street = "Metro-Straße 1",
                zip = "40235",
                city = "Düsseldorf",
                country = "DE",
            ),
        vatBreakdown = emptyList(),
        paymentIban = iban,
    )

    @Test
    fun `writing a parsed invoice links a supplier`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId = transaction { writer.write(parsed("RE-1"), sourceDocumentId = null) }

            transaction {
                val stored = Invoice.findById(invoiceId)!!
                assertNotNull(stored.supplier, "the parse path must resolve a creditor")
                assertEquals("Metro AG", stored.supplier?.partner?.name)
                assertEquals("DE123456789", stored.supplier?.partner?.vatId)
                assertEquals(1, Supplier.all().count())
            }
        }
    }

    @Test
    fun `a second invoice from the same VAT id does not create a second supplier`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction { writer.write(parsed("RE-1"), sourceDocumentId = null) }
            transaction { writer.write(parsed("RE-2"), sourceDocumentId = null) }

            transaction { assertEquals(1, Supplier.all().count()) }
        }
    }

    @Test
    fun `the parsed payment IBAN joins the supplier's known set`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val invoiceId =
                transaction {
                    writer.write(parsed("RE-1", iban = "DE89370400440532013000"), sourceDocumentId = null)
                }

            transaction {
                val supplier = Invoice.findById(invoiceId)!!.supplier!!
                assertEquals(listOf("DE89370400440532013000"), supplier.partner.bankAccounts.map { it.iban })
            }
        }
    }
}
