package org.example.project.features.allocation

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import java.math.BigDecimal

/**
 * The three rows an allocation test needs on either side of it, shared by `AllocationWriterTest` and
 * `ResidualInvariantTest` so the two agree on what a fixture looks like.
 *
 * All of these require an ambient transaction.
 */
internal object AllocationFixtures {
    /** The IBAN of the one own-account these fixtures resolve statements to. */
    private const val OWN_ACCOUNT_IBAN = "DE89370400440532013000"

    fun newInvoice(
        gross: BigDecimal,
        number: String = "RE-2026-0001",
    ): Invoice =
        Invoice.new {
            invoiceNumber = number
            taxInclusiveTotal = gross
            createdAt = currentTimeStamp()
        }

    /** An invoice whose [Invoice.amountDue] differs from its gross — the residual reads `amountDue` first. */
    fun newInvoiceWithAmountDue(
        gross: BigDecimal,
        due: BigDecimal,
        number: String = "RE-2026-0002",
    ): Invoice = newInvoice(gross, number).apply { amountDue = due }

    /** An issued STANDALONE invoice — the AR target the SALES_INVOICE kind settles. */
    fun newSalesInvoice(
        gross: BigDecimal,
        number: String = "RG-2027-00001",
        origin: org.example.project.core.domain.enums.salesinvoice.RevenueOrigin =
            org.example.project.core.domain.enums.salesinvoice.RevenueOrigin.STANDALONE,
    ): SalesInvoice =
        SalesInvoice.new {
            this.shop = ShopInfo.all().first()
            revenueOrigin = origin
            status = org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus.ISSUED
            invoiceNumber = number
            buyerName = "Musterfirma GmbH"
            issueDate = currentTimeStamp()
            serviceDate = currentTimeStamp()
            taxInclusiveTotal = gross
            amountDue = gross
            openAmount = gross
        }

    fun newBankTransaction(value: BigDecimal): BankStatementTransaction {
        val statement =
            BankStatement.new {
                accountIban = "DE89370400440532013000"
                createdAt = currentTimeStamp()
            }
        return BankStatementTransaction.new {
            this.statement = statement
            bookingDate = currentTimeStamp()
            valueDate = currentTimeStamp()
            amount = value
            creditDebit = "DEBIT"
        }
    }

    /**
     * A transaction on a statement that **has** been resolved to one of our accounts. The queue
     * admits only these: an unresolved statement has no account, therefore no shop, so no guard
     * that depends on one could be evaluated.
     *
     * The account is looked up before it is created: `bank_account.iban` is unique, so a second
     * call would otherwise fail on the unique index rather than produce a second transaction.
     */
    fun newResolvedBankTransaction(
        value: BigDecimal,
        counterparty: String? = "Metro AG",
        iban: String? = "DE02120300000000202051",
        remittance: String? = "RE-2026-0001",
    ): BankStatementTransaction {
        val account =
            BankAccount.find { BankAccountTable.iban eq OWN_ACCOUNT_IBAN }.firstOrNull()
                ?: BankAccount.new {
                    shop = ShopInfo.all().first()
                    this.iban = OWN_ACCOUNT_IBAN
                    bankName = "Sparkasse"
                    createdAt = currentTimeStamp()
                }
        val statement =
            BankStatement.new {
                accountIban = OWN_ACCOUNT_IBAN
                bankAccount = account
                createdAt = currentTimeStamp()
            }
        return BankStatementTransaction.new {
            this.statement = statement
            bookingDate = currentTimeStamp()
            valueDate = currentTimeStamp()
            amount = value
            creditDebit = "DEBIT"
            counterpartyName = counterparty
            counterpartyIban = iban
            remittanceUnstructured = remittance
        }
    }

    fun newKassenbuchHeader(
        total: BigDecimal,
        belegNr: String = "2026-08-0001",
    ): KassenbuchHeader {
        val shop = ShopInfo.all().first()
        return KassenbuchHeader.new {
            this.shop = shop
            cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
            employee = Employee.all().first()
            entryType = KassenbuchEntryType.CASH_EXPENSE
            this.belegNr = belegNr
            belegDatum = "13.08.2026"
            belegtext = "Lieferantenrechnung bar bezahlt"
            waehrung = "EUR"
            totalAmount = total
            currentBalance = total
            createdAt = currentTimeStamp()
        }
    }
}
