package org.example.project.features.supplier

import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvoiceSupplierLinkTest {
    private val repo = DocumentRepositoryImpl()

    @Test
    fun `an invoice starts unlinked and can be pointed at a supplier`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            // Written and read in separate transactions on purpose: inside the writing one Exposed's
            // identity map returns the entity it already holds, so the assertion would hold even if
            // the FK column never reached the database.
            val (invoiceId, supplierId) =
                transaction {
                    val invoice = repo.createInvoice(sellerName = "Metro AG")
                    assertNull(invoice.supplier)

                    // `partner_id` is non-null, so the role row needs a party even where the
                    // party is not what the test is about.
                    val party =
                        BusinessPartner.new {
                            this.shop = ShopInfo.all().first()
                            this.name = "Metro AG"
                        }
                    val supplier =
                        Supplier.new {
                            this.partner = party
                        }
                    invoice.supplier = supplier
                    invoice.id.value to supplier.id.value
                }

            transaction {
                val reloaded = Invoice.findById(invoiceId)!!
                assertEquals(supplierId, reloaded.supplier?.id?.value)
                // The asserted snapshot is untouched by the link.
                assertEquals("Metro AG", reloaded.sellerName)
            }
        }
    }
}
