package org.example.project.features.contract

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.features.allocation.domain.models.AllocationMatchedBy
import org.example.project.features.allocation.domain.models.AllocationSourceKind
import org.example.project.features.allocation.domain.models.AllocationTargetKind
import org.example.project.features.allocation.domain.models.PaymentAllocation
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractAlertKind
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP contract for the alerts endpoint.
 *
 * The first test is the one that matters most structurally: `GET /api/contracts/alerts` has to be
 * declared **before** `GET /api/contracts/{id}`, or Ktor matches `alerts` as an id and every call
 * 400s. Asserting the exact status is what catches that — an unregistered route is denied by
 * default, so a missing `RoutePermissions` entry shows up here as a 403 rather than a 404.
 *
 * The two amount tests pin the invoice-bearing / invoice-less split, which decides *which* number is
 * compared against the band. Both seed an allocation and an invoice that disagree, so getting the
 * split backwards fails rather than passing by coincidence.
 */
@DisplayName("Contract alert routes")
class ContractAlertRoutesTest {
    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
        TestData.createCashier()
    }

    /** A negative [days] is a due date in the future — nothing is late yet. */
    private fun dueDaysAgo(days: Int = 30): LocalDateTime = LocalDateTime(currentTimeStamp().date.minus(days, DateTimeUnit.DAY), LocalTime(0, 0))

    private fun newContract(
        name: String,
        expectsInvoice: Boolean,
        expected: BigDecimal? = BigDecimal("89.0000"),
        tolerancePct: BigDecimal? = null,
        toleranceAbs: BigDecimal? = null,
    ): Contract =
        Contract.new {
            shop = ShopInfo.all().first()
            this.name = name
            interval = ContractInterval.MONTHLY.name
            dueDay = 1
            startDate = LocalDateTime(2026, 1, 1, 0, 0)
            expectedAmount = expected
            this.tolerancePct = tolerancePct
            this.toleranceAbs = toleranceAbs
            this.expectsInvoice = expectsInvoice
        }

    private fun newInstalment(
        contract: Contract,
        dueDate: LocalDateTime = dueDaysAgo(),
        expected: BigDecimal? = BigDecimal("89.0000"),
        open: BigDecimal? = null,
        linkedInvoice: Invoice? = null,
    ): ContractInstalment =
        ContractInstalment.new {
            this.contract = contract
            this.dueDate = dueDate
            this.expectedAmount = expected
            this.openAmount = open
            this.invoice = linkedInvoice
        }

    private fun allocate(
        instalment: ContractInstalment,
        amount: BigDecimal,
    ) = PaymentAllocation.new {
        sourceKind = AllocationSourceKind.BANK_TRANSACTION.name
        targetKind = AllocationTargetKind.CONTRACT_INSTALMENT.name
        contractInstalment = instalment
        this.amount = amount
        matchedBy = AllocationMatchedBy.MANUAL.name
    }

    private fun newInvoice(
        gross: BigDecimal,
        number: String,
    ): Invoice =
        Invoice.new {
            invoiceNumber = number
            taxInclusiveTotal = gross
            createdAt = currentTimeStamp()
        }

    @Test
    fun `the alerts path is served rather than parsed as a contract id`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response = client.get("/api/contracts/alerts") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "a 400 here means /alerts is matching the /{id} route declared above it",
            )
            val body = testJson.decodeFromString<ContractAlertDto.ListResponseDto>(response.bodyAsText())
            assertEquals(0, body.total, "nothing is seeded, so nothing complains")
            assertTrue(body.items.isEmpty())
        }
    }

    @Test
    fun `the alerts path without a token is 401`() {
        testApp(setupData = { setup() }) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/contracts/alerts").status)
        }
    }

    @Test
    fun `the alerts path as a cashier is 403`() {
        testApp(setupData = { setup() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/contracts/alerts") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun `a past-due unpaid instalment reports a missing payment naming its contract`() {
        testApp(setupData = { setup() }) { client ->
            val due = dueDaysAgo()
            transaction { newInstalment(newContract("Miete Ladenlokal", expectsInvoice = false), dueDate = due) }
            val token = TestAuthHelper.loginAsManager(client)

            val alerts = fetchAlerts(client, token)

            val payment = alerts.items.single { it.kind == ContractAlertKind.PAYMENT_MISSING.name }
            assertEquals("Miete Ladenlokal", payment.contractName)
            assertEquals(due.date.toString(), payment.dueDate)
            assertNull(payment.expectedAmount, "only AMOUNT_OFF_BAND carries the two figures")
            assertNull(payment.actualAmount)
        }
    }

    @Test
    fun `an invoice-bearing contract is judged on the invoice total, not on what was paid`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                val contract = newContract("Telefon", expectsInvoice = true)
                val invoice = newInvoice(BigDecimal("129.0000"), "RE-2026-0001")
                val instalment = newInstalment(contract, linkedInvoice = invoice, open = BigDecimal("0.0000"))
                // Deliberately in band: if the *paid* amount governed, this contract would stay quiet.
                allocate(instalment, BigDecimal("89.0000"))
            }
            val token = TestAuthHelper.loginAsManager(client)

            val alerts = fetchAlerts(client, token)

            val offBand = alerts.items.single { it.kind == ContractAlertKind.AMOUNT_OFF_BAND.name }
            assertEquals("89.0000", offBand.expectedAmount, "money crosses as a scale-preserving string")
            assertEquals("129.0000", offBand.actualAmount, "the supplier's figure, not the settled one")
        }
    }

    @Test
    fun `an invoice-less contract is judged on what was allocated and never wants an invoice`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                val contract = newContract("Miete Ladenlokal", expectsInvoice = false)
                val instalment = newInstalment(contract, open = BigDecimal("0.0000"))
                allocate(instalment, BigDecimal("129.0000"))
            }
            val token = TestAuthHelper.loginAsManager(client)

            val alerts = fetchAlerts(client, token)

            val offBand = alerts.items.single { it.kind == ContractAlertKind.AMOUNT_OFF_BAND.name }
            assertEquals("89.0000", offBand.expectedAmount)
            assertEquals("129.0000", offBand.actualAmount, "no document, so the payment is the only evidence")
            assertTrue(
                alerts.items.none { it.kind == ContractAlertKind.INVOICE_MISSING.name },
                "an invoice-less contract must never report a missing invoice, however late",
            )
        }
    }

    @Test
    fun `an invoice-bearing contract past due with no invoice reports both missing things`() {
        testApp(setupData = { setup() }) { client ->
            transaction { newInstalment(newContract("Strom", expectsInvoice = true)) }
            val token = TestAuthHelper.loginAsManager(client)

            val alerts = fetchAlerts(client, token)

            assertEquals(
                setOf(ContractAlertKind.PAYMENT_MISSING.name, ContractAlertKind.INVOICE_MISSING.name),
                alerts.items.map { it.kind }.toSet(),
                "the two answer different questions; suppressing the second hides half the problem",
            )
            assertEquals(2, alerts.total)
        }
    }

    @Test
    fun `the contract list carries a per-contract alert count`() {
        testApp(setupData = { setup() }) { client ->
            transaction {
                val nagging = newContract("Strom", expectsInvoice = true)
                newInstalment(nagging)
                // Settled and not yet due: this one has nothing to say.
                val quiet = newContract("Versicherung", expectsInvoice = false)
                newInstalment(quiet, dueDate = dueDaysAgo(-30), open = BigDecimal("0.0000"))
            }
            val token = TestAuthHelper.loginAsManager(client)

            val response = client.get("/api/contracts") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            val listed = testJson.decodeFromString<ContractDto.ListResponseDto>(response.bodyAsText())

            assertEquals(2, listed.items.single { it.name == "Strom" }.alertCount)
            assertEquals(0, listed.items.single { it.name == "Versicherung" }.alertCount)
        }
    }

    private suspend fun fetchAlerts(
        client: io.ktor.client.HttpClient,
        token: String,
    ): ContractAlertDto.ListResponseDto {
        val response = client.get("/api/contracts/alerts") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return testJson.decodeFromString(response.bodyAsText())
    }
}
