package org.example.project.features.supplier

import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierResolver
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupplierResolverTest {
    private val documents = DocumentRepositoryImpl()
    private val suppliers = SupplierRepositoryImpl()
    private val resolver = SupplierResolver(suppliers)

    @Test
    fun `an unmatched VAT id auto-creates a confirmed supplier`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice =
                    documents.createInvoice(sellerName = "Metro AG", sellerVatId = "DE123456789")
                resolver.resolveAndLink(invoice)

                val supplier = invoice.supplier!!
                assertEquals("Metro AG", supplier.partner.name)
                assertEquals("DE123456789", supplier.partner.vatId)
                assertFalse(supplier.isProvisional)
            }
        }
    }

    @Test
    fun `a matching VAT id reuses the existing supplier despite a different spelling`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val existing = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                val invoice =
                    documents.createInvoice(sellerName = "METRO Deutschland", sellerVatId = "DE123456789")
                resolver.resolveAndLink(invoice)

                assertEquals(existing.id.value, invoice.supplier?.id?.value)
                // The existing master-data name is NOT overwritten by the invoice's spelling.
                assertEquals("Metro AG", invoice.supplier?.partner?.name)
            }
        }
    }

    @Test
    fun `no VAT id but an exact normalized name links instead of creating`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val existing = suppliers.create(name = "Getränke Müller GmbH", vatId = "DE111111111")
                val invoice = documents.createInvoice(sellerName = "getränke müller")
                resolver.resolveAndLink(invoice)

                assertEquals(existing.id.value, invoice.supplier?.id?.value)
            }
        }
    }

    @Test
    fun `no VAT id and no match creates a provisional supplier`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = documents.createInvoice(sellerName = "Kleiner Handwerksbetrieb")
                resolver.resolveAndLink(invoice)

                assertTrue(invoice.supplier!!.isProvisional)
            }
        }
    }

    @Test
    fun `a known IBAN resolves the supplier when name and VAT id are absent`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val existing = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                suppliers.registerBankAccount(existing, "DE89370400440532013000", null, null)

                val invoice = documents.createInvoice(paymentIban = "DE89370400440532013000")
                resolver.resolveAndLink(invoice)

                assertEquals(existing.id.value, invoice.supplier?.id?.value)
            }
        }
    }

    @Test
    fun `an invoice with no identifying data at all is left unlinked`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice = documents.createInvoice()
                resolver.resolveAndLink(invoice)
                assertNull(invoice.supplier)
            }
        }
    }

    @Test
    fun `the invoice IBAN joins the supplier's known set`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice =
                    documents.createInvoice(
                        sellerName = "Metro AG",
                        sellerVatId = "DE123456789",
                        paymentIban = "DE89370400440532013000",
                    )
                resolver.resolveAndLink(invoice)

                assertTrue(suppliers.knowsIban(invoice.supplier!!, "DE89370400440532013000"))
            }
        }
    }

    @Test
    fun `an IBAN the supplier has never used is reported as unknown`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val existing = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                suppliers.registerBankAccount(existing, "DE89370400440532013000", null, null)

                val invoice =
                    documents.createInvoice(
                        sellerName = "Metro AG",
                        sellerVatId = "DE123456789",
                        paymentIban = "DE00111122223333444455",
                    )
                val outcome = resolver.resolveAndLink(invoice)

                assertTrue(outcome.ibanWasUnknown)
            }
        }
    }

    @Test
    fun `an IBAN the supplier already uses is not reported as unknown`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val existing = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                suppliers.registerBankAccount(existing, "DE89370400440532013000", null, null)

                val invoice =
                    documents.createInvoice(
                        sellerName = "Metro AG",
                        sellerVatId = "DE123456789",
                        paymentIban = "DE89370400440532013000",
                    )
                assertFalse(resolver.resolveAndLink(invoice).ibanWasUnknown)
            }
        }
    }

    @Test
    fun `a newly created supplier's first IBAN is not reported as unknown`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val invoice =
                    documents.createInvoice(
                        sellerName = "Neue Firma",
                        sellerVatId = "DE555555555",
                        paymentIban = "DE89370400440532013000",
                    )
                assertFalse(resolver.resolveAndLink(invoice).ibanWasUnknown)
            }
        }
    }

    @Test
    fun `the VAT id rung outranks the name rung`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val byVatId = suppliers.create(name = "Alpha Handel", vatId = "DE123456789")
                val byName = suppliers.create(name = "Beta Vertrieb", vatId = null)

                // Points at Alpha by VAT id and at Beta by name. VAT id must win.
                val invoice =
                    documents.createInvoice(sellerName = "Beta Vertrieb", sellerVatId = "DE123456789")
                resolver.resolveAndLink(invoice)

                assertEquals(byVatId.id.value, invoice.supplier?.id?.value)
                assertNotEquals(byName.id.value, invoice.supplier?.id?.value)
            }
        }
    }

    @Test
    fun `the IBAN rung outranks the name rung`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val byIban = suppliers.create(name = "Gamma Logistik", vatId = "DE222222222")
                suppliers.registerBankAccount(byIban, "DE89370400440532013000", null, null)
                val byName = suppliers.create(name = "Delta Service", vatId = null)

                val invoice =
                    documents.createInvoice(
                        sellerName = "Delta Service",
                        paymentIban = "DE89370400440532013000",
                    )
                resolver.resolveAndLink(invoice)

                assertEquals(byIban.id.value, invoice.supplier?.id?.value)
                assertNotEquals(byName.id.value, invoice.supplier?.id?.value)
            }
        }
    }

    @Test
    fun `an invoice that already carries a supplier is not re-linked`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val hand = suppliers.create(name = "Von Hand zugeordnet", vatId = null)
                // Every rung would point somewhere else: the VAT id matches another creditor.
                val other = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                val invoice =
                    documents.createInvoice(sellerName = "Metro AG", sellerVatId = "DE123456789")
                invoice.supplier = hand

                val outcome = resolver.resolveAndLink(invoice)

                assertEquals(hand.id.value, invoice.supplier?.id?.value)
                assertNotEquals(other.id.value, invoice.supplier?.id?.value)
                assertEquals(hand.id.value, outcome.supplier?.id?.value)
                assertFalse(outcome.created)
            }
        }
    }

    @Test
    fun `created distinguishes a new supplier from a reused one`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val fresh = documents.createInvoice(sellerName = "Erste GmbH", sellerVatId = "DE777777777")
                assertTrue(resolver.resolveAndLink(fresh).created)

                val second = documents.createInvoice(sellerName = "Erste GmbH", sellerVatId = "DE777777777")
                assertFalse(resolver.resolveAndLink(second).created)
            }
        }
    }
}
