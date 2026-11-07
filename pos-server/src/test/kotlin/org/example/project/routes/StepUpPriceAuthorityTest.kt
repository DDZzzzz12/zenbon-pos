package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R5-1: a manager step-up must reach the order service's price checks, not only the route gate.
 *
 * A step-up lends `DISCOUNT_GRANT` / `PRICE_OVERRIDE`; `/me` listed them and the till unlocked its
 * discount panel, but `OrderServiceImpl` re-resolved the cashier's GROUP for the steep-discount and
 * custom-price checks. That second opinion could not see the step-up, so the server answered
 * `403 DISCOUNT_NOT_ALLOWED` / `403 CUSTOM_PRICE_NOT_ALLOWED` for exactly what the panel offered
 * (checklist case 13.7). The service now reads the set the route gate resolved.
 *
 * Each case runs the whole life of one step-up over real HTTP — refused, lent, withdrawn — on the
 * same cashier token, so the only thing that changes between the three orders is the step-up.
 */
@DisplayName("Step-up reaches the order price checks")
class StepUpPriceAuthorityTest {
    /** A till per test: `TemporarySessionStore` is process-wide and survives between tests. */
    private var device = "POS-STEPUP-PRICE"

    @BeforeEach
    fun freshDevice(info: TestInfo) {
        device = "POS-STEPUP-PRICE-${info.displayName.hashCode()}"
        TemporarySessionStore.getSessionByDevice(device)?.let { TemporarySessionStore.removeSession(it.sessionId) }
    }

    private fun setupTill() {
        TestData.createManager()
        TestData.createCashier()
        TestData.createDefaultVatRates()
        TestData.setTseMode(TseMode.SIMULATION)
        TestData.createFiscalRegisteredTerminal(deviceId = device, clientId = "TER-${device.hashCode()}")
    }

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    /** One line of a product with no open price, onto [sessionId]. */
    private suspend fun HttpClient.order(
        token: String,
        sessionId: Long,
        line: String,
    ): HttpResponse =
        post("/api/orders") {
            bearerAuth(token)
            setBody(TextContent("""{"tableInfoId": $sessionId, "orderItems": [$line]}""", ContentType.Application.Json))
        }

    /** A manager enters their PIN at this till and lends [capability] to the cashier holding [token]. */
    private suspend fun HttpClient.stepUp(
        token: String,
        capability: Capability,
    ): String {
        transaction { Employee.find { EmployeeTable.username eq "manager" }.first().pin = "4321" }
        val created =
            post("/api/auth/temporary-session") {
                bearerAuth(token)
                header("X-Device-ID", device)
                setBody(
                    TextContent(
                        """{"managerUsername":"manager","managerPin":"4321","deviceId":"$device","requestedCapability":"${capability.name}"}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return assertNotNull(TemporarySessionStore.getSessionByDevice(device)).sessionId
    }

    /** "Sitzung beenden" — the step-up ends at once, not at expiry. */
    private suspend fun HttpClient.endStepUp(
        token: String,
        sessionId: String,
    ) {
        val ended =
            delete("/api/auth/temporary-session/$sessionId") {
                bearerAuth(token)
                header("X-Device-ID", device)
            }
        assertEquals(HttpStatusCode.NoContent, ended.status, ended.bodyAsText())
    }

    private fun assertRefused(
        response: HttpResponse,
        code: String,
        body: String,
    ) {
        assertEquals(HttpStatusCode.Forbidden, response.status, body)
        assertTrue(code in body, "expected $code, got: $body")
    }

    /** The ids of the orders booked on [sessionId]. */
    private fun ordersOn(sessionId: Long): List<Long> =
        transaction {
            OrderItem
                .find { OrderItemTable.sessionId eq sessionId }
                .mapNotNull { it.order?.id?.value }
                .distinct()
        }

    /** The CREATE_ORDER audit row written for [orderId]. */
    private fun auditRowFor(orderId: Long) =
        transaction {
            TransactionAuditLogTable
                .selectAll()
                .where {
                    (TransactionAuditLogTable.operation eq "CREATE_ORDER") and
                        (TransactionAuditLogTable.entityId eq orderId.toString())
                }.single()
        }

    @Test
    fun `a lent DISCOUNT_GRANT lets the server accept 25 percent, and only while the step-up lasts`() =
        testApp(setupData = { setupTill() }) { client ->
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            val category = TestData.createCategory(name = "SUD_Cat")
            val product = TestData.createProduct(categoryId = category.id.value, productName = "SUD_Prod", productNumber = "SUD_P")
            val tws = TestData.createTableWithSession(tableNumber = "SUD_T1")
            val sessionId = tws.session.id.value
            // 25 % off: the multiplier 0.75 is below the 0.80 a cashier may grant unaided.
            val line = """{"productId": ${product.id.value}, "unitPrice": "9.99", "quantity": 1, "discount": 0.75}"""

            val before = client.order(cashier, sessionId, line)
            assertRefused(before, "DISCOUNT_NOT_ALLOWED", before.bodyAsText())
            assertTrue(ordersOn(sessionId).isEmpty(), "a refused order must book nothing")

            val stepUp = client.stepUp(cashier, Capability.DISCOUNT_GRANT)
            val during = client.order(cashier, sessionId, line)
            assertEquals(
                HttpStatusCode.Created,
                during.status,
                "the step-up lends DISCOUNT_GRANT, so the server must accept what the panel offers: ${during.bodyAsText()}",
            )
            val orderId = ordersOn(sessionId).single()

            // The actor stays the cashier; the manager whose PIN lent the authority is the second name.
            val row = auditRowFor(orderId)
            assertEquals(idOf("cashier"), row[TransactionAuditLogTable.employeeId])
            assertEquals("TEMPORARY_SESSION", row[TransactionAuditLogTable.authorizationType])
            assertEquals(idOf("manager"), row[TransactionAuditLogTable.authorizingManagerId])

            client.endStepUp(cashier, stepUp)
            val after = client.order(cashier, sessionId, line)
            assertRefused(after, "DISCOUNT_NOT_ALLOWED", after.bodyAsText())
            assertEquals(listOf(orderId), ordersOn(sessionId), "the withdrawn step-up must book nothing more")
        }

    @Test
    fun `a lent PRICE_OVERRIDE lets the server accept a custom price, and only while the step-up lasts`() =
        testApp(setupData = { setupTill() }) { client ->
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            val category = TestData.createCategory(name = "SUP_Cat")
            // A product with no open price: a custom price on it needs PRICE_OVERRIDE.
            val product = TestData.createProduct(categoryId = category.id.value, productName = "SUP_Prod", productNumber = "SUP_P")
            val tws = TestData.createTableWithSession(tableNumber = "SUP_T1")
            val sessionId = tws.session.id.value
            val line = """{"productId": ${product.id.value}, "unitPrice": "5.00", "quantity": 1, "customPrice": true}"""

            val before = client.order(cashier, sessionId, line)
            assertRefused(before, "CUSTOM_PRICE_NOT_ALLOWED", before.bodyAsText())

            val stepUp = client.stepUp(cashier, Capability.PRICE_OVERRIDE)
            val during = client.order(cashier, sessionId, line)
            assertEquals(HttpStatusCode.Created, during.status, during.bodyAsText())
            val booked =
                transaction {
                    OrderItem.find { OrderItemTable.sessionId eq sessionId }.single().unitPrice
                }
            assertEquals(0, BigDecimal("5.00").compareTo(booked), "the line is booked at the custom price, got $booked")

            client.endStepUp(cashier, stepUp)
            val after = client.order(cashier, sessionId, line)
            assertRefused(after, "CUSTOM_PRICE_NOT_ALLOWED", after.bodyAsText())
        }

    @Test
    fun `a step-up lends exactly what was asked for, so PRICE_OVERRIDE does not unlock a steep discount`() =
        testApp(setupData = { setupTill() }) { client ->
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            val category = TestData.createCategory(name = "SUS_Cat")
            val product = TestData.createProduct(categoryId = category.id.value, productName = "SUS_Prod", productNumber = "SUS_P")
            val tws = TestData.createTableWithSession(tableNumber = "SUS_T1")

            // T5-5 on the server side: the two authorities stay separate even when lent.
            client.stepUp(cashier, Capability.PRICE_OVERRIDE)
            val response =
                client.order(
                    cashier,
                    tws.session.id.value,
                    """{"productId": ${product.id.value}, "unitPrice": "9.99", "quantity": 1, "discount": 0.75}""",
                )
            assertRefused(response, "DISCOUNT_NOT_ALLOWED", response.bodyAsText())
        }

    @Test
    fun `a step-up granted to one cashier does not unlock a steep discount for a colleague at the same till`() =
        testApp(
            setupData = {
                setupTill()
                TestData.createCashier(employeeNumber = "CSH004", username = "colleague")
            },
        ) { client ->
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            val colleague = TestAuthHelper.loginAs(client, "colleague", deviceId = device)
            val category = TestData.createCategory(name = "SUC_Cat")
            val product = TestData.createProduct(categoryId = category.id.value, productName = "SUC_Prod", productNumber = "SUC_P")
            val tws = TestData.createTableWithSession(tableNumber = "SUC_T1")

            client.stepUp(cashier, Capability.DISCOUNT_GRANT)
            val response =
                client.order(
                    colleague,
                    tws.session.id.value,
                    """{"productId": ${product.id.value}, "unitPrice": "9.99", "quantity": 1, "discount": 0.75}""",
                )
            assertRefused(response, "DISCOUNT_NOT_ALLOWED", response.bodyAsText())
        }
}
