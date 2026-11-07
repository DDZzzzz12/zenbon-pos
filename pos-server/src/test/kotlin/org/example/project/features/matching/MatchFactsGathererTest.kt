package org.example.project.features.matching

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.features.allocation.AllocationFixtures
import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.matching.data.service.MatchFactsGatherer
import org.example.project.features.matching.data.service.isEligible
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gatherer turns one transaction id into the facts and candidates the pure rankers consume.
 *
 * The two supplier resolutions are asserted **separately**, because they answer different questions:
 * an IBAN says who was paid, a recorded SEPA Creditor Identifier says who is collecting under a
 * mandate. Feeding the IBAN's answer to signal 3 would award `EXACT` to evidence that only earns
 * `STRONG`, which is exactly the confident-wrong-answer the ladder exists to prevent.
 */
@DisplayName("Match facts gatherer")
class MatchFactsGathererTest {
    private val gatherer = MatchFactsGatherer(AllocationQueueRepositoryImpl())

    private val ownIban = "DE89370400440532013000"

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    private fun newSupplier(
        name: String,
        iban: String? = null,
        sepaCreditorId: String? = null,
    ): Supplier {
        val supplier = SupplierRepositoryImpl().create(name = name, vatId = "DE${name.hashCode().toString().take(8)}")
        supplier.sepaCreditorId = sepaCreditorId
        if (iban != null) SupplierRepositoryImpl().registerBankAccount(supplier, iban, null, null)
        return supplier
    }

    private fun newTransaction(
        amount: BigDecimal,
        counterpartyIban: String? = null,
        counterpartyName: String? = null,
        creditorId: String? = null,
        mandateId: String? = null,
        reversal: Boolean = false,
        resolved: Boolean = true,
        valueDate: LocalDateTime = LocalDateTime(2026, 6, 3, 0, 0),
    ): BankStatementTransaction {
        val account =
            BankAccount.find { BankAccountTable.iban eq ownIban }.firstOrNull()
                ?: BankAccount.new {
                    shop = ShopInfo.all().first()
                    this.iban = ownIban
                    bankName = "Sparkasse"
                    createdAt = currentTimeStamp()
                }
        val statement =
            BankStatement.new {
                accountIban = ownIban
                if (resolved) bankAccount = account
                createdAt = currentTimeStamp()
            }
        return BankStatementTransaction.new {
            this.statement = statement
            bookingDate = valueDate
            this.valueDate = valueDate
            this.amount = amount
            creditDebit = "DEBIT"
            this.counterpartyName = counterpartyName
            this.counterpartyIban = counterpartyIban
            this.creditorId = creditorId
            this.mandateId = mandateId
            reversalIndicator = reversal
        }
    }

    private fun newOpenInvoice(
        supplier: Supplier?,
        gross: BigDecimal,
        number: String,
        issued: LocalDateTime = LocalDateTime(2026, 5, 1, 0, 0),
        due: LocalDateTime? = LocalDateTime(2026, 6, 1, 0, 0),
    ): Invoice =
        Invoice.new {
            invoiceNumber = number
            this.supplier = supplier
            taxInclusiveTotal = gross
            openAmount = gross
            issueDate = issued
            dueDate = due
            createdAt = currentTimeStamp()
        }

    private fun newInstalment(
        supplier: Supplier?,
        expected: BigDecimal?,
        mandate: String? = null,
        name: String = "Miete Ladenlokal",
        dueDate: LocalDateTime = LocalDateTime(2026, 6, 1, 0, 0),
    ): ContractInstalment {
        val contract =
            Contract.new {
                shop = ShopInfo.all().first()
                this.name = name
                this.supplier = supplier
                interval = ContractInterval.MONTHLY.name
                dueDay = dueDate.dayOfMonth
                startDate = LocalDateTime(2026, 1, 1, 0, 0)
                expectedAmount = expected
                sepaMandateId = mandate
            }
        return ContractInstalment.new {
            this.contract = contract
            this.dueDate = dueDate
            this.expectedAmount = expected
        }
    }

    @Test
    fun `an unknown transaction id gathers nothing`() {
        testApp(setupData = { setup() }) { _ ->
            transaction { assertNull(gatherer.gather(999_999L, bookedFrom = null)) }
        }
    }

    @Test
    fun `the counterparty IBAN resolves to its supplier`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val supplier = newSupplier("Metro AG", iban = "DE02120300000000202051")
                val invoice = newOpenInvoice(supplier, BigDecimal("120.0000"), "RE-2026-0001")
                val id = newTransaction(BigDecimal("120.0000"), counterpartyIban = "DE02120300000000202051").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertEquals(supplier.id.value, facts.counterpartySupplierId)
                assertEquals(listOf(invoice.id.value), facts.invoiceCandidates.map { it.id })
                assertEquals(supplier.id.value, facts.invoiceCandidates.single().supplierId)
                assertTrue(isEligible(facts.transaction))
            }
        }
    }

    @Test
    fun `an IBAN nobody has recorded resolves to no supplier`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                newSupplier("Metro AG", iban = "DE02120300000000202051")
                val id = newTransaction(BigDecimal("120.0000"), counterpartyIban = "DE99999999999999999999").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertNull(facts.counterpartySupplierId)
            }
        }
    }

    /**
     * The independence proof. The IBAN resolves, the bank quoted a creditor id, and **no** supplier
     * has that identifier recorded — so signal 3's supplier must stay null rather than inheriting
     * the IBAN's answer.
     */
    @Test
    fun `an unrecorded creditor id resolves to no supplier even when the IBAN does`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val supplier = newSupplier("Metro AG", iban = "DE02120300000000202051", sepaCreditorId = null)
                val id =
                    newTransaction(
                        BigDecimal("120.0000"),
                        counterpartyIban = "DE02120300000000202051",
                        creditorId = "DE98ZZZ09999999999",
                    ).id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertEquals(supplier.id.value, facts.counterpartySupplierId)
                assertNull(facts.sepaCreditorSupplierId)
            }
        }
    }

    @Test
    fun `a recorded creditor id resolves to its own supplier`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val collector = newSupplier("Stadtwerke", sepaCreditorId = "DE98ZZZ09999999999")
                val id = newTransaction(BigDecimal("120.0000"), creditorId = "DE98ZZZ09999999999").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertEquals(collector.id.value, facts.sepaCreditorSupplierId)
                assertNull(facts.counterpartySupplierId)
            }
        }
    }

    @Test
    fun `a reversal is gathered and then rejected by eligibility`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val id = newTransaction(BigDecimal("120.0000"), reversal = true).id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertTrue(facts.transaction.reversalIndicator)
                assertFalse(isEligible(facts.transaction))
            }
        }
    }

    @Test
    fun `a statement with no bank account is gathered without one`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val id = newTransaction(BigDecimal("120.0000"), resolved = false).id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertFalse(facts.transaction.hasBankAccount)
                assertFalse(isEligible(facts.transaction))
            }
        }
    }

    @Test
    fun `an invoice issued before the Buchungsbeginn is not a candidate`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val supplier = newSupplier("Metro AG", iban = "DE02120300000000202051")
                newOpenInvoice(
                    supplier,
                    BigDecimal("120.0000"),
                    "RE-2025-0001",
                    issued = LocalDateTime(2025, 12, 1, 0, 0),
                )
                val kept = newOpenInvoice(supplier, BigDecimal("120.0000"), "RE-2026-0001")
                val id = newTransaction(BigDecimal("120.0000"), counterpartyIban = "DE02120300000000202051").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = LocalDate(2026, 1, 1)))

                assertEquals(listOf(kept.id.value), facts.invoiceCandidates.map { it.id })
            }
        }
    }

    @Test
    fun `a settled invoice is not a candidate`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val supplier = newSupplier("Metro AG", iban = "DE02120300000000202051")
                newOpenInvoice(supplier, BigDecimal("120.0000"), "RE-2026-0002").apply { openAmount = BigDecimal.ZERO }
                val id = newTransaction(BigDecimal("120.0000"), counterpartyIban = "DE02120300000000202051").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertTrue(facts.invoiceCandidates.isEmpty())
            }
        }
    }

    @Test
    fun `the supplier's normalized name is gathered for the candidates`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val supplier = newSupplier("Metro Cash & Carry GmbH", iban = "DE02120300000000202051")
                newOpenInvoice(supplier, BigDecimal("120.0000"), "RE-2026-0001")
                val id = newTransaction(BigDecimal("120.0000"), counterpartyIban = "DE02120300000000202051").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertEquals(supplier.partner.normalizedName, facts.supplierNormalizedNames[supplier.id.value])
            }
        }
    }

    @Test
    fun `an instalment is gathered with its contract's supplier, mandate and tolerance`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val supplier = newSupplier("Vermieter", iban = "DE02120300000000202051")
                val instalment = newInstalment(supplier, BigDecimal("1450.0000"), mandate = "MND-4711")
                instalment.contract.tolerancePct = BigDecimal("5.0000")
                instalment.contract.toleranceAbs = BigDecimal("2.0000")
                val id = newTransaction(BigDecimal("1450.0000"), counterpartyIban = "DE02120300000000202051").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                val candidate = facts.contractCandidates.single()
                assertEquals(instalment.id.value, candidate.instalmentId)
                assertEquals(supplier.id.value, candidate.supplierId)
                assertEquals("MND-4711", candidate.sepaMandateId)
                assertEquals(0, BigDecimal("1450.0000").compareTo(candidate.expectedAmount))
                assertEquals(0, BigDecimal("5.0000").compareTo(candidate.tolerancePct))
                assertEquals(0, BigDecimal("2.0000").compareTo(candidate.toleranceAbs))
                assertEquals(LocalDate(2026, 6, 1), candidate.dueDate)
                assertEquals("Miete Ladenlokal", facts.contractNames[candidate.contractId])
            }
        }
    }

    @Test
    fun `a settled instalment and an inactive contract are not candidates`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val supplier = newSupplier("Vermieter", iban = "DE02120300000000202051")
                newInstalment(supplier, BigDecimal("1450.0000"), name = "Miete").apply {
                    openAmount = BigDecimal.ZERO
                }
                newInstalment(supplier, BigDecimal("99.0000"), name = "Alter Vertrag").also {
                    it.contract.isActive = false
                }
                val id = newTransaction(BigDecimal("1450.0000"), counterpartyIban = "DE02120300000000202051").id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertTrue(facts.contractCandidates.isEmpty())
            }
        }
    }

    @Test
    fun `the transaction's own fields survive the round trip`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                val tx =
                    newTransaction(
                        BigDecimal("120.0000"),
                        counterpartyIban = "DE02120300000000202051",
                        counterpartyName = "METRO Cash Carry",
                        creditorId = "DE98ZZZ09999999999",
                        mandateId = "MND-4711",
                    )
                tx.remittanceUnstructured = "Rechnung RE-2026-0001"
                tx.remittanceStructuredRef = "RE-2026-0001"
                tx.endToEndId = "E2E-1"

                val facts = requireNotNull(gatherer.gather(tx.id.value, bookedFrom = null))

                assertEquals(tx.id.value, facts.transaction.id)
                assertEquals(LocalDate(2026, 6, 3), facts.transaction.valueDate)
                assertEquals(0, BigDecimal("120.0000").compareTo(facts.transaction.amount))
                assertEquals("DEBIT", facts.transaction.creditDebit)
                assertEquals("METRO Cash Carry", facts.transaction.counterpartyName)
                assertEquals("DE02120300000000202051", facts.transaction.counterpartyIban)
                assertEquals("Rechnung RE-2026-0001", facts.transaction.remittanceUnstructured)
                assertEquals("RE-2026-0001", facts.transaction.remittanceStructuredRef)
                assertEquals("E2E-1", facts.transaction.endToEndId)
                assertEquals("MND-4711", facts.transaction.mandateId)
                assertEquals("DE98ZZZ09999999999", facts.transaction.creditorId)
                assertEquals("EUR", facts.currency)
            }
        }
    }

    @Test
    fun `an invoice with no supplier is still a candidate`() {
        testApp(setupData = { setup() }) { _ ->
            transaction {
                // The fixture's invoice carries no supplier and no due date — still a candidate,
                // because signals 1 and 2 read the reference, not the supplier.
                val loose = AllocationFixtures.newInvoice(BigDecimal("55.0000"), "RE-2026-0009")
                val id = newTransaction(BigDecimal("55.0000")).id.value

                val facts = requireNotNull(gatherer.gather(id, bookedFrom = null))

                assertEquals(listOf(loose.id.value), facts.invoiceCandidates.map { it.id })
                assertNull(facts.invoiceCandidates.single().supplierId)
            }
        }
    }
}
