package org.example.project.features.matching

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.allocation.SuggestionDto
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP contract for the suggestions endpoint.
 *
 * The behaviour worth guarding hardest is the boring one: **nothing to suggest is 200 with an empty
 * list**, for an ineligible transaction as much as for an unmatched one. A 404 or a 409 there would
 * make the client show a failure for the commonest case in the system.
 */
@DisplayName("Suggestion Routes")
class SuggestionRoutesTest {
    private val ownIban = "DE89370400440532013000"

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
        TestData.createCashier()
    }

    private fun newTransaction(
        amount: BigDecimal,
        remittance: String? = null,
        reversal: Boolean = false,
        resolved: Boolean = true,
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
            bookingDate = currentTimeStamp()
            valueDate = currentTimeStamp()
            this.amount = amount
            creditDebit = "DEBIT"
            remittanceStructuredRef = remittance
            reversalIndicator = reversal
        }
    }

    private fun newInvoice(
        gross: BigDecimal,
        number: String,
    ): Invoice =
        Invoice.new {
            invoiceNumber = number
            taxInclusiveTotal = gross
            openAmount = gross
            // Dated today: no test here sets a Buchungsbeginn, and reading one creates the
            // default — the first of the current month — which an older invoice would fall before.
            issueDate = currentTimeStamp()
            dueDate = currentTimeStamp()
            createdAt = currentTimeStamp()
        }

    @Test
    fun `without a token it is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/allocations/suggestions?transactionId=1").status,
            )
        }
    }

    @Test
    fun `as a cashier it is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/allocations/suggestions?transactionId=1") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `a missing transactionId is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/allocations/suggestions") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `a non-numeric transactionId is 400`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/allocations/suggestions?transactionId=abc") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `an unknown transaction is 404`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/allocations/suggestions?transactionId=999999") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `a structured reference suggests that invoice exactly`() {
        testApp(setupData = { setup() }) { client ->
            val id =
                transaction {
                    val invoice = newInvoice(BigDecimal("120.0000"), "RE-2026-0001")
                    newTransaction(BigDecimal("120.0000"), remittance = "RE-2026-0001").id.value to invoice.id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/allocations/suggestions?transactionId=${id.first}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val body: SuggestionDto.ResponseDto = testJson.decodeFromString(response.bodyAsText())
            assertEquals(1, body.total)
            val item = body.items.single()
            assertEquals("INVOICE", item.targetKind)
            assertEquals(listOf(id.second), item.invoiceIds)
            assertEquals("STRUCTURED_REFERENCE", item.signal)
            assertEquals("EXACT", item.strength)
            assertEquals("120.0000", item.amount)
            assertEquals("EUR", item.currency)
            assertEquals("RE-2026-0001", item.label)
        }
    }

    @Test
    fun `a reversal is 200 with an empty list`() {
        testApp(setupData = { setup() }) { client ->
            val id =
                transaction {
                    newInvoice(BigDecimal("120.0000"), "RE-2026-0001")
                    newTransaction(BigDecimal("120.0000"), remittance = "RE-2026-0001", reversal = true).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/allocations/suggestions?transactionId=$id") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body: SuggestionDto.ResponseDto = testJson.decodeFromString(response.bodyAsText())
            assertEquals(0, body.total)
            assertTrue(body.items.isEmpty())
        }
    }

    @Test
    fun `a statement with no bank account is 200 with an empty list`() {
        testApp(setupData = { setup() }) { client ->
            val id =
                transaction {
                    newInvoice(BigDecimal("120.0000"), "RE-2026-0001")
                    newTransaction(BigDecimal("120.0000"), remittance = "RE-2026-0001", resolved = false).id.value
                }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/allocations/suggestions?transactionId=$id") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(testJson.decodeFromString<SuggestionDto.ResponseDto>(response.bodyAsText()).items.isEmpty())
        }
    }

    @Test
    fun `a payment matching nothing is 200 with an empty list`() {
        testApp(setupData = { setup() }) { client ->
            val id = transaction { newTransaction(BigDecimal("77.7700")).id.value }

            val token = TestAuthHelper.loginAsManager(client)
            val response = client.get("/api/allocations/suggestions?transactionId=$id") { bearerAuth(token) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(testJson.decodeFromString<SuggestionDto.ResponseDto>(response.bodyAsText()).items.isEmpty())
        }
    }
}
