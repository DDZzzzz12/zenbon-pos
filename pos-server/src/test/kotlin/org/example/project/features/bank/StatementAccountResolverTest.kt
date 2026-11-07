package org.example.project.features.bank

import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.bank.data.service.StatementAccountResolver
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatementAccountResolverTest {
    private val accounts = BankAccountRepositoryImpl()

    private fun resolver() = StatementAccountResolver(accounts)

    /** Stands in for what `:accounting-banking` writes on upload: a statement with an IBAN and no account. */
    private fun statement(iban: String?): BankStatement =
        BankStatement.new {
            this.accountIban = iban
            this.createdAt = currentTimeStamp()
        }

    @Test
    fun `a statement whose IBAN is known gets the account`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val account = accounts.create(shop, "DE89370400440532013000", null, "Commerzbank")
                val stmt = statement("DE89 3704 0044 0532 0130 00")

                val report = resolver().resolveUnassigned()

                assertEquals(1, report.resolved)
                assertEquals(account.id.value, stmt.bankAccount?.id?.value)
            }
        }
    }

    @Test
    fun `an unknown IBAN is reported, not invented as an account`() {
        testApp {
            transaction {
                val stmt = statement("DE00111122223333444455")

                val report = resolver().resolveUnassigned()

                assertEquals(0, report.resolved)
                assertEquals(listOf("DE00111122223333444455"), report.unknownIbans)
                assertNull(stmt.bankAccount)
                assertEquals(0, accounts.countAll())
            }
        }
    }

    @Test
    fun `a statement with no IBAN at all is counted separately`() {
        testApp {
            transaction {
                statement(null)

                val report = resolver().resolveUnassigned()

                assertEquals(0, report.resolved)
                assertEquals(1, report.withoutIban)
                assertEquals(emptyList(), report.unknownIbans)
            }
        }
    }

    @Test
    fun `an already-assigned statement is left alone`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                val shop = ShopInfo.all().first()
                val first = accounts.create(shop, "DE89370400440532013000", null, null)
                val second = accounts.create(shop, "DE02120300000000202051", null, null)
                val stmt = statement("DE02120300000000202051")
                stmt.bankAccount = first

                resolver().resolveUnassigned()

                // Still the hand-assigned account, not the one its IBAN points at.
                assertEquals(first.id.value, stmt.bankAccount?.id?.value)
                assertEquals(2, accounts.countAll())
            }
        }
    }
}
