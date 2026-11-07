package org.example.project.features.partner

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierResolver
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * An invoice must never mint a *second* creditor for a company that already has one.
 *
 * Two parties may legitimately carry the same VAT id — a creditor and a debtor are deliberately not
 * merged automatically (see `PartnerPerRoleTest`) — so the party lookup can face a choice. The
 * choice is not free: picking the party *without* a creditor role makes the resolver attach a new
 * one, and there is then a second creditor for one VAT id, created by a path that never consults
 * the `SUPPLIER_VAT_ID_TAKEN` guard. The dropped unique index on `supplier.vat_id` used to catch
 * that; the lookup's preference for an existing creditor role is what catches it now.
 *
 * The debtor's party is created **first** on purpose, so it holds the lower id: an undirected
 * `firstOrNull()` picks it, which is exactly the wrong answer.
 */
@DisplayName("Party lookup prefers the party that already trades as a creditor")
class CreditorPreferenceOnLookupTest {
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()
    private val documents = DocumentRepositoryImpl()
    private val resolver = SupplierResolver(suppliers)

    @Test
    fun `an invoice reaches the existing creditor rather than the debtor's party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val creditorId =
                transaction {
                    // First, so it wins any id-ordered tie: a company we sell to, no creditor role.
                    debtors.create(
                        shop = ShopInfo.all().first(),
                        name = "Metro AG",
                        vatId = "DE123456789",
                    )
                    // Second: the creditor an operator captured for the same VAT id.
                    suppliers.create(name = "Metro AG", vatId = "DE123456789").id.value
                }

            transaction {
                val invoice =
                    documents.createInvoice(sellerName = "Metro AG", sellerVatId = "DE123456789")
                resolver.resolveAndLink(invoice)

                assertEquals(
                    creditorId,
                    invoice.supplier?.id?.value,
                    "the invoice must attach to the creditor that already exists",
                )
                assertEquals(
                    1,
                    Supplier.all().count().toInt(),
                    "resolving an invoice must not create a second creditor for one VAT id",
                )
            }
        }
    }

    @Test
    fun `the same holds when only the tax number identifies the seller`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val creditorId =
                transaction {
                    debtors.create(
                        shop = ShopInfo.all().first(),
                        name = "Getränke Müller GmbH",
                        taxNumber = "12/345/67890",
                    )
                    suppliers.create(name = "Getränke Müller GmbH", taxNumber = "12/345/67890").id.value
                }

            transaction {
                val invoice =
                    documents.createInvoice(
                        sellerName = "Getränke Müller GmbH",
                        sellerTaxNumber = "12/345/67890",
                    )
                resolver.resolveAndLink(invoice)

                assertEquals(creditorId, invoice.supplier?.id?.value)
                assertEquals(1, Supplier.all().count().toInt())
            }
        }
    }

    @Test
    fun `and when only the name matches, which has no uniqueness at all`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val creditorId =
                transaction {
                    debtors.create(
                        shop = ShopInfo.all().first(),
                        name = "Metro AG",
                    )
                    suppliers.create(name = "Metro AG").id.value
                }

            transaction {
                val invoice = documents.createInvoice(sellerName = "metro")
                resolver.resolveAndLink(invoice)

                assertEquals(creditorId, invoice.supplier?.id?.value)
                assertEquals(1, Supplier.all().count().toInt())
            }
        }
    }
}
