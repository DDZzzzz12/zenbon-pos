package org.example.project.features.contract

import kotlinx.datetime.LocalDateTime
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Every assertion reads in its **own** transaction: within one, Exposed's identity map can serve
 * back the instance just written rather than what the column actually stored, so a column that
 * never persisted would still assert green.
 */
@DisplayName("contract and contract_instalment")
class ContractTableTest {
    @Test
    fun `a contract with a supplier and a tolerance band round-trips`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val supplierId = transaction { newSupplier().id.value }
            val contractId =
                transaction {
                    newContract(supplier = Supplier.findById(supplierId)) {
                        description = "Gewerbemiete Ladenlokal"
                        expectedAmount = BigDecimal("1250.0000")
                        tolerancePct = BigDecimal("5.0000")
                        toleranceAbs = BigDecimal("20.0000")
                        noticePeriodDays = 90
                        expectsInvoice = true
                        defaultAccountNo = "4210"
                        defaultBuCode = "9"
                        defaultCostCenter = "100"
                    }.id.value
                }

            transaction {
                val reloaded = Contract.findById(contractId)!!
                assertEquals("Miete Ladenlokal", reloaded.name)
                assertEquals("Gewerbemiete Ladenlokal", reloaded.description)
                assertEquals(supplierId, reloaded.supplier?.id?.value)
                assertEquals(0, BigDecimal("1250.0000").compareTo(reloaded.expectedAmount!!))
                assertEquals(0, BigDecimal("5.0000").compareTo(reloaded.tolerancePct!!))
                assertEquals(0, BigDecimal("20.0000").compareTo(reloaded.toleranceAbs!!))
                assertEquals(ContractInterval.MONTHLY.name, reloaded.interval)
                assertEquals(1, reloaded.dueDay)
                assertEquals(90, reloaded.noticePeriodDays)
                assertEquals(true, reloaded.expectsInvoice)
                assertEquals("4210", reloaded.defaultAccountNo)
                assertEquals("9", reloaded.defaultBuCode)
                assertEquals("100", reloaded.defaultCostCenter)
                assertEquals(true, reloaded.isActive)
                assertNull(reloaded.endDate)
                assertNull(reloaded.document)
                assertNotNull(reloaded.createdAt, "createdAt must be filled by the column's clientDefault")
            }
        }
    }

    /**
     * The invoice-less kind — rent, a standing order. `expects_invoice` defaults to false and
     * `invoice_id` on its instalments stays null forever; waiting for a document would flag every
     * month as a problem.
     */
    @Test
    fun `an instalment round-trips as OPEN with no invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val contractId = transaction { newContract().id.value }
            val instalmentId =
                transaction {
                    ContractInstalment
                        .new {
                            contract = Contract.findById(contractId)!!
                            dueDate = LocalDateTime(2026, 3, 1, 0, 0)
                            expectedAmount = BigDecimal("1250.0000")
                        }.id.value
                }

            transaction {
                val reloaded = ContractInstalment.findById(instalmentId)!!
                assertEquals(contractId, reloaded.contract.id.value)
                assertEquals(LocalDateTime(2026, 3, 1, 0, 0), reloaded.dueDate)
                assertEquals(0, BigDecimal("1250.0000").compareTo(reloaded.expectedAmount!!))
                assertEquals(ContractInstalmentStatus.OPEN.name, reloaded.status)
                assertNull(reloaded.invoice, "an invoice-less contract never fills invoice_id")
                assertNull(reloaded.openAmount, "a residual appears only once something is allocated against the instalment")
                assertNotNull(reloaded.createdAt)
            }

            transaction {
                assertEquals(false, Contract.findById(contractId)!!.expectsInvoice)
            }
        }
    }

    /**
     * The unique index is what makes the generator idempotent: a second run after a restart must be
     * a no-op rather than a doubled expected outgoing.
     */
    @Test
    fun `the contract_id and due_date pair is unique`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val contractId = transaction { newContract().id.value }
            transaction {
                ContractInstalment.new {
                    contract = Contract.findById(contractId)!!
                    dueDate = LocalDateTime(2026, 4, 1, 0, 0)
                }
            }

            assertFailsWith<ExposedSQLException> {
                transaction {
                    ContractInstalment.new {
                        contract = Contract.findById(contractId)!!
                        dueDate = LocalDateTime(2026, 4, 1, 0, 0)
                    }
                }
            }

            transaction {
                assertEquals(1, ContractInstalment.all().count(), "the duplicate must not have landed")
            }
        }
    }

    /** The same due date under a *different* contract is not a collision. */
    @Test
    fun `two contracts may share a due date`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val firstId = transaction { newContract().id.value }
            val secondId = transaction { newContract(name = "Strom").id.value }

            transaction {
                ContractInstalment.new {
                    contract = Contract.findById(firstId)!!
                    dueDate = LocalDateTime(2026, 4, 1, 0, 0)
                }
                ContractInstalment.new {
                    contract = Contract.findById(secondId)!!
                    dueDate = LocalDateTime(2026, 4, 1, 0, 0)
                }
            }

            transaction {
                assertEquals(2, ContractInstalment.all().count())
            }
        }
    }

    /**
     * The invoice-bearing kind: the period's invoice arrives, parses, and is tied to the instalment
     * it settles — and `invoice.contract_id` says which contract it arrived under.
     */
    @Test
    fun `an invoice references its contract and an instalment references the invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val contractId = transaction { newContract(name = "Telekom").id.value }
            val invoiceId =
                transaction {
                    Invoice
                        .new {
                            invoiceNumber = "RE-2026-0042"
                            taxInclusiveTotal = BigDecimal("89.0000")
                            contract = Contract.findById(contractId)
                            createdAt = currentTimeStamp()
                        }.id.value
                }

            val instalmentId =
                transaction {
                    ContractInstalment
                        .new {
                            contract = Contract.findById(contractId)!!
                            dueDate = LocalDateTime(2026, 5, 1, 0, 0)
                            invoice = Invoice.findById(invoiceId)
                        }.id.value
                }

            transaction {
                val invoice = Invoice.findById(invoiceId)!!
                val instalment = ContractInstalment.findById(instalmentId)!!
                assertEquals(contractId, invoice.contract?.id?.value)
                assertEquals(invoiceId, instalment.invoice?.id?.value)
            }
        }
    }

    /** `partner_id` is non-null, so the creditor role row needs a party to point at. */
    private fun newSupplier(): Supplier =
        Supplier.new {
            partner =
                BusinessPartner.new {
                    shop = ShopInfo.all().first()
                    name = "Vermietung Meier GmbH"
                    vatId = "DE123456789"
                }
        }

    private fun newContract(
        name: String = "Miete Ladenlokal",
        supplier: Supplier? = null,
        configure: Contract.() -> Unit = {},
    ): Contract =
        Contract.new {
            shop = ShopInfo.all().first()
            this.supplier = supplier
            this.name = name
            interval = ContractInterval.MONTHLY.name
            dueDay = 1
            startDate = LocalDateTime(2026, 1, 1, 0, 0)
            configure()
        }
}
