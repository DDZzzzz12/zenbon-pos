package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.product.domain.models.Product
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentInstant
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Finding E-2: a line may not be discounted or custom-priced under the shared station account.
 *
 * The order-station design (2026-09-06, §1 and §4) says pricing is not offered at a station. The
 * station mask already withholds `DISCOUNT_GRANT` and `PRICE_OVERRIDE`, so a steep discount and a
 * custom price were refused — but a discount of up to 20 % needs no capability, and the emulator pass
 * booked a 10 % line from a station (16,90 € → 15,21 €). The server refuses any priced line in an
 * order registered under the station account, whichever way the station authenticates and whatever a
 * step-up lends (a step-up lends capabilities; the order is still registered under the station
 * account). The owner's rule (2026-09-28) is attribution, not device: a person's own login on the
 * station device prices like that person anywhere else. The till is unchanged.
 *
 * Every station leg runs under the shipped credentials: pairing over real HTTP, then either the
 * station JWT from `POST /api/v1/terminals/session` or the bare `X-Device-Token`.
 */
@DisplayName("A line may not be priced under the station account")
class StationPricingRouteTest {
    private val stationDevice = "station-pricing-device"
    private val tillDevice = "till-pricing-device"
    private val stepUpSessionId = "SESSION-station-pricing"

    @BeforeEach
    fun resetState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
    }

    @AfterEach
    fun removeStepUp() {
        TemporarySessionStore.removeSession(stepUpSessionId)
    }

    private fun setup() {
        TestData.createAdmin()
        TestData.createManager()
        TestData.createCashier()
        TestData.createTerminalDeviceEmployee()
        TestData.createDefaultVatRates()
        TestData.setTseMode(TseMode.SIMULATION)
        TestData.createFiscalRegisteredTerminal(deviceId = tillDevice, clientId = "TER-TILL-PRICING")
    }

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

    /** Exchanges [deviceToken] for the station JWT the order-station shell runs on. */
    private suspend fun stationJwt(
        client: HttpClient,
        deviceToken: String,
    ): String {
        val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", deviceToken) }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText()).accessToken
    }

    /** One line of [productId] onto [sessionId]; [extra] adds `discount`/`customPrice` JSON fields. */
    private suspend fun HttpClient.orderLine(
        sessionId: Long,
        productId: Long,
        extra: String = "",
        auth: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        post("/api/orders") {
            auth()
            setBody(
                TextContent(
                    """{"tableInfoId": $sessionId, "orderItems": [""" +
                        """{"productId": $productId, "unitPrice": "16.90", "quantity": 1$extra}]}""",
                    ContentType.Application.Json,
                ),
            )
        }

    private fun product(): Long {
        val category = TestData.createCategory(name = "SPR_Cat")
        return TestData
            .createProduct(
                categoryId = category.id.value,
                productName = "Tagesmenue",
                productNumber = "SPR_P",
                price = BigDecimal("16.90"),
            ).id.value
    }

    private fun itemsOn(sessionId: Long): List<OrderItem> = transaction { OrderItem.find { OrderItemTable.sessionId eq sessionId }.toList() }

    private suspend fun assertRefused(
        response: HttpResponse,
        code: PosErrorCode,
    ) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Forbidden, response.status, body)
        assertEquals(code.name, testJson.decodeFromString<ProblemDetail>(body).code, body)
    }

    @Test
    fun `a station JWT is refused a 10 percent discount and may still order without one`() =
        testApp(setupData = { setup() }) { client ->
            val station = stationJwt(client, pairStation(client))
            val productId = product()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T1")
                    .session.id.value

            val discounted = client.orderLine(sessionId, productId, """, "discount": 0.90""") { bearerAuth(station) }
            assertRefused(discounted, PosErrorCode.DISCOUNT_NOT_ALLOWED)
            assertTrue(itemsOn(sessionId).isEmpty(), "a refused order must book nothing")

            val plain = client.orderLine(sessionId, productId) { bearerAuth(station) }
            assertEquals(HttpStatusCode.Created, plain.status, plain.bodyAsText())
            val booked = itemsOn(sessionId).single()
            assertEquals(0, BigDecimal("16.90").compareTo(booked.finalPrice), "the line is booked at list price")
        }

    @Test
    fun `a station on its bare device token is refused a discount too`() =
        testApp(setupData = { setup() }) { client ->
            val deviceToken = pairStation(client)
            val productId = product()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T2")
                    .session.id.value

            val discounted =
                client.orderLine(sessionId, productId, """, "discount": 0.90""") { header("X-Device-Token", deviceToken) }
            assertRefused(discounted, PosErrorCode.DISCOUNT_NOT_ALLOWED)

            val plain = client.orderLine(sessionId, productId) { header("X-Device-Token", deviceToken) }
            assertEquals(HttpStatusCode.Created, plain.status, plain.bodyAsText())
        }

    @Test
    fun `a station is refused a custom price even on a product that allows one`() =
        testApp(setupData = { setup() }) { client ->
            val station = stationJwt(client, pairStation(client))
            val productId = product()
            transaction {
                Product.findById(productId)!!.allowCustomPrice = true
            }
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T3")
                    .session.id.value

            val custom = client.orderLine(sessionId, productId, """, "customPrice": true""") { bearerAuth(station) }
            assertRefused(custom, PosErrorCode.CUSTOM_PRICE_NOT_ALLOWED)
            assertTrue(itemsOn(sessionId).isEmpty())
        }

    @Test
    fun `a manager step-up lent to the station account does not unlock pricing under it`() =
        testApp(setupData = { setup() }) { client ->
            val station = stationJwt(client, pairStation(client))
            val productId = product()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T4")
                    .session.id.value
            stepUpAtStation()
            val discounted = client.orderLine(sessionId, productId, """, "discount": 0.90""") { bearerAuth(station) }
            assertRefused(discounted, PosErrorCode.DISCOUNT_NOT_ALLOWED)

            transaction { Product.findById(productId)!!.allowCustomPrice = true }
            val custom = client.orderLine(sessionId, productId, """, "customPrice": true""") { bearerAuth(station) }
            assertRefused(custom, PosErrorCode.CUSTOM_PRICE_NOT_ALLOWED)
            assertTrue(itemsOn(sessionId).isEmpty(), "a refused order must book nothing")
        }

    @Test
    fun `a station on its bare device token is refused a custom price too`() =
        testApp(setupData = { setup() }) { client ->
            val deviceToken = pairStation(client)
            val productId = customPriceProduct()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T10")
                    .session.id.value

            val custom =
                client.orderLine(sessionId, productId, """, "customPrice": true""") { header("X-Device-Token", deviceToken) }
            assertRefused(custom, PosErrorCode.CUSTOM_PRICE_NOT_ALLOWED)
            assertTrue(itemsOn(sessionId).isEmpty())
        }

    /** A manager step-up lent to the station account on the station device: both price authorities. */
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
                grantedCapabilities = setOf(Capability.DISCOUNT_GRANT, Capability.PRICE_OVERRIDE),
                createdAt = currentInstant(),
                expiresAt = currentInstant().plus(5.minutes),
            ),
        )
    }

    @Test
    fun `the till cashier keeps the unprivileged 10 percent discount`() =
        testApp(setupData = { setup() }) { client ->
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = tillDevice)
            val productId = product()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T5")
                    .session.id.value

            val discounted = client.orderLine(sessionId, productId, """, "discount": 0.90""") { bearerAuth(cashier) }
            assertEquals(HttpStatusCode.Created, discounted.status, discounted.bodyAsText())
            val booked = itemsOn(sessionId).single()
            assertEquals(0, BigDecimal("15.21").compareTo(booked.finalPrice), "16,90 € at 10 % off")
        }

    // --- The rule is about ATTRIBUTION, not about the device (owner, 2026-09-28) ----------------
    //
    // A manager's own login at the order station registers the order under the manager, so it prices
    // like the manager anywhere else: the order records the manager as its employee and the station's
    // terminal as the one it is signed under. (OBS-2 had classified it by the device and refused it.)

    /** A product that allows a custom price, so only the station rule can refuse one. */
    private fun customPriceProduct(): Long {
        val productId = product()
        transaction { Product.findById(productId)!!.allowCustomPrice = true }
        return productId
    }

    @Test
    fun `a manager's own login at the order station device may give a 10 percent discount`() =
        testApp(setupData = { setup() }) { client ->
            pairStation(client)
            val manager = TestAuthHelper.loginAs(client, "manager", deviceId = stationDevice)
            val productId = product()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T6")
                    .session.id.value

            val discounted =
                client.orderLine(sessionId, productId, """, "discount": 0.90""") {
                    bearerAuth(manager)
                    header("X-Device-ID", stationDevice)
                }
            assertEquals(HttpStatusCode.Created, discounted.status, discounted.bodyAsText())
            val booked = itemsOn(sessionId).single()
            assertEquals(0, BigDecimal("15.21").compareTo(booked.finalPrice), "16,90 € at 10 % off")
            val (employee, terminalDevice) =
                transaction {
                    val order = booked.order!!
                    order.employee.username to order.terminal?.deviceId
                }
            assertEquals("manager", employee, "the order is registered under the manager")
            assertEquals(stationDevice, terminalDevice, "and signed under the station's terminal")
        }

    @Test
    fun `a manager's own login at the order station device may set a custom price`() =
        testApp(setupData = { setup() }) { client ->
            pairStation(client)
            val manager = TestAuthHelper.loginAs(client, "manager", deviceId = stationDevice)
            val productId = customPriceProduct()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T7")
                    .session.id.value

            val custom =
                client.orderLine(sessionId, productId, """, "customPrice": true""") {
                    bearerAuth(manager)
                    header("X-Device-ID", stationDevice)
                }
            assertEquals(HttpStatusCode.Created, custom.status, custom.bodyAsText())
            assertEquals(1, itemsOn(sessionId).size)
        }

    @Test
    fun `the same manager on the till terminal keeps the discount and the custom price`() =
        testApp(setupData = { setup() }) { client ->
            pairStation(client)
            val manager = TestAuthHelper.loginAs(client, "manager", deviceId = tillDevice)
            val productId = customPriceProduct()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T8")
                    .session.id.value

            val discounted =
                client.orderLine(sessionId, productId, """, "discount": 0.90""") {
                    bearerAuth(manager)
                    header("X-Device-ID", tillDevice)
                }
            assertEquals(HttpStatusCode.Created, discounted.status, discounted.bodyAsText())

            val custom =
                client.orderLine(sessionId, productId, """, "customPrice": true""") {
                    bearerAuth(manager)
                    header("X-Device-ID", tillDevice)
                }
            assertEquals(HttpStatusCode.Created, custom.status, custom.bodyAsText())
            assertEquals(2, itemsOn(sessionId).size)
        }

    /**
     * The back office cannot book an order at all: `POST /api/orders` is a transactional route, so the
     * terminal ceiling answers 409 before any pricing rule runs (and the order service would refuse the
     * missing signing terminal after it). Pinned so a pricing 403 never becomes the back-office answer.
     */
    @Test
    fun `the same manager from the back office meets the terminal ceiling, not the pricing rule`() =
        testApp(setupData = { setup() }) { client ->
            val manager = TestAuthHelper.loginAs(client, "manager", deviceId = "admin-web-spr")
            val productId = product()
            val sessionId =
                TestData
                    .createTableWithSession(tableNumber = "SPR_T9")
                    .session.id.value

            val discounted =
                client.orderLine(sessionId, productId, """, "discount": 0.90""") {
                    bearerAuth(manager)
                    header("X-Device-ID", "admin-web-spr")
                }
            val body = discounted.bodyAsText()
            assertEquals(HttpStatusCode.Conflict, discounted.status, body)
            assertEquals(PosErrorCode.DEVICE_NOT_PAIRED.name, testJson.decodeFromString<ProblemDetail>(body).code, body)
        }
}
