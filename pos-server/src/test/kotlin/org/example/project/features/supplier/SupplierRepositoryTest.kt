package org.example.project.features.supplier

import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupplierRepositoryTest {
    private val repo = SupplierRepositoryImpl()

    @Test
    fun `create stores the normalized name`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier = repo.create(name = "Getränke Müller GmbH", vatId = "DE111111111")
                assertEquals("getränke müller", supplier.partner.normalizedName)
            }
        }
    }

    @Test
    fun `findByVatId matches exactly`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                repo.create(name = "Metro AG", vatId = "DE123456789")
                assertEquals("Metro AG", repo.findByVatId("DE123456789")?.partner?.name)
                assertNull(repo.findByVatId("DE999999999"))
            }
        }
    }

    @Test
    fun `registerBankAccount is idempotent per supplier and iban`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val supplier = repo.create(name = "Metro AG", vatId = "DE123456789")
                repo.registerBankAccount(supplier, "DE89370400440532013000", bic = null, invoiceId = 1L)
                repo.registerBankAccount(supplier, "DE89370400440532013000", bic = null, invoiceId = 2L)
                assertEquals(1, supplier.partner.bankAccounts.count())
            }
        }
    }

    @Test
    fun `knowsIban distinguishes a supplier's own accounts from another's`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val metro = repo.create(name = "Metro AG", vatId = "DE123456789")
                val other = repo.create(name = "Andere GmbH", vatId = "DE222222222")
                repo.registerBankAccount(metro, "DE89370400440532013000", bic = null, invoiceId = null)
                assertTrue(repo.knowsIban(metro, "DE89370400440532013000"))
                assertTrue(!repo.knowsIban(other, "DE89370400440532013000"))
            }
        }
    }
}
