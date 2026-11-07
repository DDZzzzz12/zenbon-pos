package org.example.project.features.supplier

import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierBackfill
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupplierBackfillTest {
    private val documents = DocumentRepositoryImpl()
    private val suppliers = SupplierRepositoryImpl()

    private fun backfill() = SupplierBackfill(suppliers)

    @Test
    fun `existing invoices gain a creditor`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            // The assertion runs in a second transaction on purpose: read back inside the writing
            // one, Exposed's identity map serves the entity it already holds, so the test would pass
            // even if the FK column never reached the database.
            val invoiceId =
                transaction {
                    val invoice = documents.createInvoice(sellerName = "Metro AG", sellerVatId = "DE123456789")
                    assertEquals(1, backfill().run().linked)
                    invoice.id.value
                }

            transaction {
                val linkedParty =
                    Invoice
                        .findById(invoiceId)!!
                        .supplier
                        ?.partner
                assertEquals("Metro AG", linkedParty?.name)
            }
        }
    }

    @Test
    fun `two invoices from one VAT id collapse to one creditor`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                documents.createInvoice(sellerName = "Metro AG", sellerVatId = "DE123456789")
                documents.createInvoice(sellerName = "METRO Deutschland", sellerVatId = "DE123456789")
                backfill().run()

                assertEquals(1, Supplier.all().count())
            }
        }
    }

    @Test
    fun `running twice links nothing new`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                documents.createInvoice(sellerName = "Metro AG", sellerVatId = "DE123456789")
                backfill().run()
                val second = backfill().run()

                assertEquals(0, second.linked)
                assertEquals(1, second.skippedAlreadyLinked)
                assertEquals(1, Supplier.all().count())
            }
        }
    }

    @Test
    fun `an invoice with no seller name is skipped, not linked to an empty creditor`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = documents.createInvoice()
                val report = backfill().run()

                assertNull(invoice.supplier)
                assertEquals(1, report.skippedNoSeller)
                assertEquals(0, Supplier.all().count())
            }
        }
    }
}
