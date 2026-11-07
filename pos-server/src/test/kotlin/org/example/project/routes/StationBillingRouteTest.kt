package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.websocket.PendingBillingRequestStore
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentInstant
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Finding OS-b, as the owner decided it (2026-09-28): what matters is WHO a bill is registered under,
 * not which device sends it. A bill registered under the shared station account — what a station JWT
 * and a bare station device token act as — is refused in every form; a bill registered under a real
 * person is allowed, even when that person's own login is sent from the station device.
 *
 * Every billing endpoint is called as the station account by every way it can speak — its station
 * JWT, its bare `X-Device-Token`, and its station JWT under a manager step-up lending the lendable
 * bill overrides — and must be refused without writing anything. The step-up lends capabilities; it
 * does not change attribution: the request still carries the station account's employee id, so the
 * bill would still be registered under the station account. The capability mask alone could not
 * refuse it, because a step-up is applied after the mask and lends `BILL_VOID` / `BILL_CHANGE_PAYMENT`.
 *
 * A manager's OWN login on the station device is the other side of the rule: the bill is registered
 * under the manager (`bill.employee`, DSFinV-K BEDIENER) and signed under the station's terminal.
 * The till is unchanged.
 *
 * Every station leg runs under the shipped credentials: pairing over real HTTP, then either the
 * station JWT from `POST /api/v1/terminals/session` or the bare device token.
 */
@DisplayName("Billing is refused under the station account, allowed under a person's own login")
class StationBillingRouteTest {
    private val stationDevice = "station-billing-device"
    private val tillDevice = "till-billing-device"
    private val stepUpSessionId = "SESSION-station-billing"

    @BeforeEach
    fun resetState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
        PendingBillingRequestStore.reset()
    }

    @AfterEach
    fun cleanUp() {
        TemporarySessionStore.removeSession(stepUpSessionId)
        PendingBillingRequestStore.reset()
        // deleteBill leaves a reverse-bill number reservation behind; a fresh DB would re-derive it.
        BillNumberTracker.clearAll()
    }

    /** Ids created by [setup], readable inside the test body. */
    private data class Fixture(
        val tableId: Long,
        val sessionId: Long,
        val ownerUsername: String,
        val itemIds: List<Long>,
        val settledBillId: Long,
        val cashTypeId: Long,
    )

    private var fixture: Fixture? = null

    private fun setup() {
        TestData.createAdmin()
        TestData.createManager()
        TestData.createTerminalDeviceEmployee()
        TestData.createShopInfo()
        val vat = TestData.createDefaultVatRates().first()
        TestData.setTseMode(TseMode.SIMULATION)
        TestData.createFiscalRegisteredTerminal(deviceId = tillDevice, clientId = "TER-TILL-OSB")
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val category = TestData.createCategory(name = "OSB_Cat")
        val product = TestData.createProduct(categoryId = category.id.value, productName = "OSB_Prod", productNumber = "OSB_P")
        // Owner is the cashier `tbl_OSB1`, which TestData creates for the session.
        val tws = TestData.createTableWithSession(tableNumber = "OSB1")

        transaction {
            val session = TableSession.findById(tws.session.id)!!
            val items =
                listOf("10.00", "5.50").map { price ->
                    OrderItem
                        .new {
                            this.session = session
                            this.product = Product.findById(product.id.value)!!
                            this.unitPrice = BigDecimal(price)
                            this.finalPrice = BigDecimal(price)
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = Vat.findById(vat.id.value)!!
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }.id.value
                }
            // An already settled bill on the same session — the target of the Storno legs.
            val now = currentTimeStamp()
            val settled =
                Bill.new {
                    this.employeeId = session.employeeId
                    this.tableSession = session
                    this.billNumber = "OSB-1"
                    this.billAmount = BigDecimal("30.00")
                    this.paidAmount = BigDecimal("30.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            Payment.new {
                this.bill = settled
                this.paymentType = PaymentType.findById(cash.id.value)!!
                this.amount = BigDecimal("30.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            fixture =
                Fixture(
                    tableId = tws.table.id.value,
                    sessionId = session.id.value,
                    ownerUsername = "tbl_OSB1",
                    itemIds = items,
                    settledBillId = settled.id.value,
                    cashTypeId = cash.id.value,
                )
        }
    }

    // --- station credentials ----------------------------------------------------------------

    /** Pairs an ORDER_STATION to [stationDevice]; returns its device token. */
    private suspend fun pairStation(client: HttpClient): String {
        val admin = TestAuthHelper.loginAsAdmin(client)
        val created =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(admin)
                    setBody(TextContent("""{"name":"Station","role":"ORDER_STATION"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            client
                .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(admin) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        return client
            .post("/api/v1/terminals/pair") {
                setBody(TextContent("""{"code":"$code","deviceId":"$stationDevice"}""", ContentType.Application.Json))
            }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
            .deviceToken
    }

    private suspend fun stationJwt(
        client: HttpClient,
        deviceToken: String,
    ): String {
        val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", deviceToken) }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText()).accessToken
    }

    /** A manager step-up at the station, lent to the station account, lending the bill overrides. */
    private fun stepUpAtStation() {
        val managerId =
            transaction {
                Employee
                    .find { EmployeeTable.username eq "manager" }
                    .first()
                    .id.value
                    .toString()
            }
        TemporarySessionStore.addSession(
            TemporarySessionStore.SessionInfo(
                sessionId = stepUpSessionId,
                managerId = managerId,
                managerName = "Test Manager",
                managerRole = EmployeeRole.MANAGER,
                deviceId = stationDevice,
                requestingEmployeeId =
                    transaction {
                        TerminalEmployee
                            .requireRow()
                            .id.value
                            .toString()
                    },
                grantedCapabilities =
                    setOf(Capability.BILL_VOID, Capability.BILL_CHANGE_PAYMENT, Capability.TABLE_SESSION_OVERRIDE),
                createdAt = currentInstant(),
                expiresAt = currentInstant().plus(5.minutes),
            ),
        )
    }

    /**
     * One way of speaking as the station account. [stepUp] runs the call under an active manager
     * step-up lent to that account on the station device.
     */
    private data class StationPrincipal(
        val name: String,
        val stepUp: Boolean = false,
        val auth: HttpRequestBuilder.() -> Unit,
    )

    /** Station JWT, bare device token, and the station JWT under a manager step-up. */
    private suspend fun stationAccountPrincipals(client: HttpClient): List<StationPrincipal> {
        val deviceToken = pairStation(client)
        val jwt = stationJwt(client, deviceToken)
        return listOf(
            StationPrincipal("station JWT") { bearerAuth(jwt) },
            StationPrincipal("station device token") { header("X-Device-Token", deviceToken) },
            StationPrincipal("station JWT under a manager step-up", stepUp = true) { bearerAuth(jwt) },
        )
    }

    /** Runs [block] as [principal], with its step-up active for exactly that call. */
    private suspend fun <T> asStation(
        principal: StationPrincipal,
        block: suspend () -> T,
    ): T {
        if (principal.stepUp) stepUpAtStation()
        try {
            return block()
        } finally {
            TemporarySessionStore.removeSession(stepUpSessionId)
        }
    }

    /** Pairs the station, then returns the manager's OWN login sent from the station device. */
    private suspend fun managerAtStation(client: HttpClient): HttpRequestBuilder.() -> Unit {
        pairStation(client)
        val manager = TestAuthHelper.loginAs(client, "manager", deviceId = stationDevice)
        return {
            bearerAuth(manager)
            header("X-Device-ID", stationDevice)
        }
    }

    // --- the billing endpoints ----------------------------------------------------------------

    /** A PARTIAL bill: only the first of the two open lines. */
    private fun partialBillJson(f: Fixture) =
        """{"tableSessionId":${f.sessionId},"orderItems":[${f.itemIds.first()}],"billAmount":"10.00",""" +
            """"payments":[{"amount":"10.00","paymentTypeId":${f.cashTypeId}}]}"""

    private suspend fun HttpClient.createBill(
        f: Fixture,
        auth: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        post("/api/v1/bills") {
            auth()
            setBody(TextContent(partialBillJson(f), ContentType.Application.Json))
        }

    private suspend fun HttpClient.createBillWithCard(
        f: Fixture,
        auth: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        get("/api/bills/create-with-card") {
            auth()
            parameter("cardTerminalId", 1)
            parameter("amountCents", 1000)
            parameter("request", partialBillJson(f))
        }

    private suspend fun HttpClient.voidBill(
        f: Fixture,
        auth: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = delete("/api/v1/bills/${f.settledBillId}?reason=test") { auth() }

    private suspend fun HttpClient.requestBilling(
        f: Fixture,
        auth: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = post("/api/tables/${f.tableId}/request-billing") { auth() }

    // --- state probes -------------------------------------------------------------------------

    private fun billCount(): Long = transaction { Bill.all().count() }

    private fun itemsStillOpen(f: Fixture): Boolean = transaction { f.itemIds.all { OrderItem.findById(it)!!.itemStatus == ItemStatus.OPEN } }

    private fun settledBillUntouched(f: Fixture): Boolean = transaction { Bill.findById(f.settledBillId)!!.deletingReason == null }

    private fun assertForbidden(
        response: HttpResponse,
        who: String,
    ) {
        assertEquals(HttpStatusCode.Forbidden, response.status, "$who must be refused with 403")
    }

    // --- station legs ------------------------------------------------------------------------

    @Test
    fun `no bill, whole or partial, is created under the station account`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            val before = billCount()
            for (p in stationAccountPrincipals(client)) {
                assertForbidden(asStation(p) { client.createBill(f, p.auth) }, p.name)
            }
            assertEquals(before, billCount(), "a refused bill writes nothing")
            assertTrue(itemsStillOpen(f), "a refused bill pays no line")
        }

    @Test
    fun `no card-payment bill is started under the station account`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            val before = billCount()
            for (p in stationAccountPrincipals(client)) {
                assertForbidden(asStation(p) { client.createBillWithCard(f, p.auth) }, p.name)
            }
            assertEquals(before, billCount())
            assertTrue(itemsStillOpen(f))
        }

    @Test
    fun `no Storno under the station account, not even under a step-up lending BILL_VOID`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            for (p in stationAccountPrincipals(client)) {
                assertForbidden(asStation(p) { client.voidBill(f, p.auth) }, p.name)
            }
            assertTrue(settledBillUntouched(f), "a refused Storno reverses nothing")
        }

    /**
     * The one endpoint outside `BILL_*`: `request-billing` is `TABLE_WRITE`, which the station mask
     * holds, so only the attribution rule refuses it. The bare device token never reaches the handler
     * — the route is JWT-only, so it is refused at authentication (401), not by the billing rule.
     */
    @Test
    fun `the station account may not ask for the bill either`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            val owner =
                transaction {
                    TableSession
                        .findById(f.sessionId)!!
                        .employeeId.id.value
                        .toString()
                }
            for (p in stationAccountPrincipals(client)) {
                val response = asStation(p) { client.requestBilling(f, p.auth) }
                if (p.name == "station device token") {
                    assertTrue(
                        response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden,
                        "${p.name} must be refused, was ${response.status}",
                    )
                } else {
                    assertForbidden(response, p.name)
                }
            }
            assertTrue(PendingBillingRequestStore.getForEmployee(owner).isEmpty(), "a refused request reaches nobody")
        }

    // --- a person's own login on the station device bills, attributed to that person ------------

    private fun terminalDeviceOf(billId: Long): String? = transaction { Bill.findById(billId)!!.terminal?.deviceId }

    private fun employeeUsernameOf(billId: Long): String = transaction { Bill.findById(billId)!!.employeeId.username }

    private fun billIds(): Set<Long> = transaction { Bill.all().map { it.id.value }.toSet() }

    /** The single bill written since [before] was taken. */
    private fun newBill(before: Set<Long>): Long = (billIds() - before).single()

    /**
     * Written under [employee] (`bill.employee`, DSFinV-K BEDIENER) and signed under the station's
     * terminal.
     */
    private fun assertBillAtStation(
        billId: Long,
        employee: String,
        what: String,
    ) {
        assertEquals(employee, employeeUsernameOf(billId), "the $what is registered under $employee")
        assertEquals(stationDevice, terminalDeviceOf(billId), "the $what is signed under the station's terminal")
    }

    /**
     * A Storno carries over the employee of the bill it reverses
     * (`BillServiceImpl`: `employeeId = bill.employeeId`) — here the table's cashier — so for it
     * the rule reads the ACTING employee, the one the request and its audit row are attributed to.
     */
    @Test
    fun `a manager's own login on the station device bills and voids, attributed to the manager`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            val manager = managerAtStation(client)

            val beforeCreate = billIds()
            val created = client.createBill(f, manager)
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertBillAtStation(newBill(beforeCreate), "manager", "bill")

            val beforeVoid = billIds()
            val voided = client.voidBill(f, manager)
            assertEquals(HttpStatusCode.OK, voided.status, voided.bodyAsText())
            assertBillAtStation(newBill(beforeVoid), f.ownerUsername, "Storno")
        }

    @Test
    fun `a manager's own login on the station device may ask for the bill`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            val owner =
                transaction {
                    TableSession
                        .findById(f.sessionId)!!
                        .employeeId.id.value
                        .toString()
                }
            val response = client.requestBilling(f, managerAtStation(client))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(PendingBillingRequestStore.getForEmployee(owner).isNotEmpty(), "the owner is asked")
        }

    @Test
    fun `a manager's own login on the station device is not refused the card-payment flow`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            val response = client.createBillWithCard(f, managerAtStation(client))
            assertNotEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        }

    // --- the till is unchanged -----------------------------------------------------------------

    @Test
    fun `the till still bills, voids and asks for the bill`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            TestData.createCashier(employeeNumber = "W_OSB", username = "waiter_osb")
            val owner = TestAuthHelper.loginAs(client, f.ownerUsername, deviceId = tillDevice)
            val waiter = TestAuthHelper.loginAs(client, "waiter_osb", deviceId = tillDevice)
            val manager = TestAuthHelper.loginAs(client, "manager", deviceId = tillDevice)

            val requested = client.requestBilling(f) { bearerAuth(waiter) }
            assertEquals(HttpStatusCode.OK, requested.status, requested.bodyAsText())

            val created = client.createBill(f) { bearerAuth(owner) }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

            val voided = client.voidBill(f) { bearerAuth(manager) }
            assertEquals(HttpStatusCode.OK, voided.status, voided.bodyAsText())
        }

    @Test
    fun `the till's card-payment flow is not refused by the station rule`() =
        testApp(setupData = { setup() }) { client ->
            val f = fixture!!
            val owner = TestAuthHelper.loginAs(client, f.ownerUsername, deviceId = tillDevice)
            val response = client.createBillWithCard(f) { bearerAuth(owner) }
            assertNotEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        }
}
