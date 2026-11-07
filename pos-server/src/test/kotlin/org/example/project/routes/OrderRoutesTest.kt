package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DisplayName("Order Routes")
class OrderRoutesTest {
    @Nested
    @DisplayName("GET /api/orders/by-table-session/{tableSessionId}")
    inner class GetOrdersByTableSession {
        @Test
        fun `returns 200 with orders for valid table session`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTableWithSession()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/orders/by-table-session/1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

        @Test
        fun `returns 400 for invalid table session ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.get("/api/orders/by-table-session/abc") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 for negative table session ID`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // Distinct branch from `abc`: -1 parses as a Long, so this exercises the handler's
                // `tableSessionId <= 0` guard rather than the toLongOrNull() null path.
                val response =
                    client.get("/api/orders/by-table-session/-1") {
                        bearerAuth(token)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 401 without auth token`() =
            testApp { client ->
                val response = client.get("/api/orders/by-table-session/1")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/orders")
    inner class CreateOrder {
        private val deviceId = "order-routes-device"

        @Test
        fun `invalid table ID returns 400`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-ORDER-ROUTES-1")
                },
            ) { client ->
                val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

                val orderJson = """{"tableInfoId": -1, "orderItems": [{"productId": 1, "unitPrice": 9.99, "quantity": 1}]}"""

                val response =
                    client.post("/api/orders") {
                        bearerAuth(token)
                        setBody(TextContent(orderJson, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `empty items returns 400`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createTableWithSession()
                    TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-ORDER-ROUTES-2")
                },
            ) { client ->
                val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

                val orderJson = """{"tableInfoId": 1, "orderItems": []}"""

                val response =
                    client.post("/api/orders") {
                        bearerAuth(token)
                        setBody(TextContent(orderJson, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        // The terminal-capability ceiling in RouteAuthorizationPlugin runs in onCall, BEFORE
        // routing (and therefore before route-level authenticate("auth-jwt")) for the exact
        // transactional routes. A request with no auth at all also carries no resolvable device
        // identity, so it is now refused as "unpaired" (409) rather than reaching the JWT check
        // (401). This is the new, intended ordering for this exact route.
        @Test
        fun `returns 409 without auth (ceiling runs before authentication)`() =
            testApp { client ->
                val response =
                    client.post("/api/orders") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/orders — completed session guard")
    inner class CreateOrderCompletedSession {
        private val deviceId = "order-completed-session-device"

        @Test
        fun `returns 409 when creating an order for a completed session`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val tws = TestData.createTableWithSession()
                    TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-ORDER-COMPLETED-1")
                    transaction {
                        // End the session normally: completed=true, table freed
                        TableSession.findById(tws.session.id.value)!!.end()
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
                val orderJson = """{"tableInfoId":1,"orderItems":[{"productId":1,"unitPrice":9.99,"quantity":1}]}"""
                val response =
                    client.post("/api/orders") {
                        bearerAuth(token)
                        setBody(TextContent(orderJson, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Conflict, response.status)
            }

        @Test
        fun `does not re-occupy table when creating order for completed session`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val tws = TestData.createTableWithSession()
                    TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-ORDER-COMPLETED-2")
                    transaction { TableSession.findById(tws.session.id.value)!!.end() } // table freed
                },
            ) { client ->
                val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
                val orderJson = """{"tableInfoId":1,"orderItems":[{"productId":1,"unitPrice":9.99,"quantity":1}]}"""
                client.post("/api/orders") {
                    bearerAuth(token)
                    setBody(TextContent(orderJson, ContentType.Application.Json))
                }
                // Verify the table stayed FREE and currentSession stayed null.
                val stillFree =
                    transaction {
                        val t = TableSession.findById(1)!!.table
                        t.tableStatus == TableStatus.FREE && t.currentSession == null
                    }
                assertEquals(true, stillFree)
            }
    }

    /**
     * Part D, Task 7 — server-owned session resolution.
     *
     * When the client sends a `tableId`, the server resolves the table's current
     * active session under a table-row lock (creating one if none is active),
     * ignoring any (possibly stale) caller-supplied `tableInfoId`. This removes the
     * root cause of orders attaching to a completed session via a stale session id.
     *
     * These tests drive [OrderService.createOrder] directly (like OpenAmountBroadcastTest)
     * because a full HTTP round-trip needs the whole TSE/print graph; calling the
     * service lets us assert exactly which session the order attached to.
     */
    @Nested
    @DisplayName("POST /api/orders — tableId session resolution (Part D)")
    inner class CreateOrderByTableId {
        private val deviceId = "tableid-resolution-device"

        private fun createTerminal() =
            transaction {
                org.example.project.features.terminal.domain.models.Terminal.new {
                    this.deviceId = this@CreateOrderByTableId.deviceId
                    this.clientId = "TID-CLIENT"
                    this.isActive = true
                    this.registeredAt = currentTimeStamp()
                    this.lastSeenAt = currentTimeStamp()
                }
            }

        private fun oneItem(productId: Long) =
            listOf(
                OrderItemDto.CreateOrderItemRequestDto(
                    productId = productId,
                    unitPrice = "9.99",
                    quantity = 1,
                ),
            )

        @Test
        fun `order with tableId attaches to the table's current active session`() =
            testApp(
                setupData = {
                    val manager = TestData.createManager(employeeNumber = "TID_MGR", username = "tid_mgr")
                    val category = TestData.createCategory(name = "TID_Cat")
                    val product =
                        TestData.createProduct(
                            categoryId = category.id.value,
                            productName = "TID_Prod",
                            productNumber = "TID_P",
                        )
                    TestData.createDefaultVatRates()
                    val tws = TestData.createTableWithSession(tableNumber = "TID_ACTIVE")
                    createTerminal()

                    val service = GlobalContext.get().get<OrderService>()
                    val existingSessionId = tws.session.id.value
                    val tableId = tws.table.id.value

                    val response =
                        runBlocking {
                            service.createOrder(
                                request =
                                    OrderDto.CreateOrderRequestDto(
                                        tableInfoId = existingSessionId,
                                        tableId = tableId,
                                        orderItems = oneItem(product.id.value),
                                    ),
                                employeeId = manager.id.value.toString(),
                                deviceId = deviceId,
                                actorCapabilities = emptySet(),
                            )
                        }

                    // The order attached to the table's already-active session, not a new one.
                    val attachedSessionId =
                        transaction {
                            Orders
                                .findById(response.orderId)!!
                                .tableSession.id.value
                        }
                    assertEquals(existingSessionId, attachedSessionId)
                },
            ) { _ -> }

        @Test
        fun `order with tableId opens a fresh session when the table has none`() =
            testApp(
                setupData = {
                    val manager = TestData.createManager(employeeNumber = "TID_MGR", username = "tid_mgr")
                    val category = TestData.createCategory(name = "TID_Cat")
                    val product =
                        TestData.createProduct(
                            categoryId = category.id.value,
                            productName = "TID_Prod",
                            productNumber = "TID_P",
                        )
                    TestData.createDefaultVatRates()
                    // A FREE table with NO active session.
                    val table = TestData.createTable(tableNumber = "TID_FREE")
                    createTerminal()

                    val tableId = table.id.value
                    val sessionsBefore =
                        transaction {
                            org.example.project.features.table.domain.models.TableSession
                                .all()
                                .count()
                        }

                    val service = GlobalContext.get().get<OrderService>()
                    val response =
                        runBlocking {
                            service.createOrder(
                                request =
                                    OrderDto.CreateOrderRequestDto(
                                        // No prior session exists; tableInfoId is a placeholder the
                                        // server must ignore in favour of tableId.
                                        tableInfoId = 999_999L,
                                        tableId = tableId,
                                        orderItems = oneItem(product.id.value),
                                    ),
                                employeeId = manager.id.value.toString(),
                                deviceId = deviceId,
                                actorCapabilities = emptySet(),
                            )
                        }

                    // A brand-new active session was opened and the table is now OCCUPIED.
                    val (occupied, hasActiveSession, sessionsAfter) =
                        transaction {
                            val t = RTable.findById(tableId)!!
                            val open = t.currentSession
                            Triple(
                                t.tableStatus == TableStatus.OCCUPIED,
                                open != null && !open.completed,
                                org.example.project.features.table.domain.models.TableSession
                                    .all()
                                    .count(),
                            )
                        }
                    assertTrue(occupied, "table should be OCCUPIED after opening a session")
                    assertTrue(hasActiveSession, "table should reference a fresh non-completed session")
                    assertEquals(sessionsBefore + 1, sessionsAfter, "exactly one new session opened")

                    // Order attached to that fresh session.
                    val attachedCompleted =
                        transaction {
                            Orders.findById(response.orderId)!!.tableSession.completed
                        }
                    assertEquals(false, attachedCompleted)
                },
            ) { _ -> }

        @Test
        fun `stale completed tableInfoId is ignored when a valid tableId is present`() =
            testApp(
                setupData = {
                    val manager = TestData.createManager(employeeNumber = "TID_MGR", username = "tid_mgr")
                    val category = TestData.createCategory(name = "TID_Cat")
                    val product =
                        TestData.createProduct(
                            categoryId = category.id.value,
                            productName = "TID_Prod",
                            productNumber = "TID_P",
                        )
                    TestData.createDefaultVatRates()
                    val tws = TestData.createTableWithSession(tableNumber = "TID_STALE")
                    createTerminal()

                    val staleSessionId = tws.session.id.value
                    val tableId = tws.table.id.value

                    // Complete (bill) the session: completed=true, table freed. The client
                    // still holds the now-stale session id in tableInfoId.
                    transaction { TableSession.findById(staleSessionId)!!.end() }

                    val service = GlobalContext.get().get<OrderService>()
                    // With the OLD behaviour this would attach to the completed session (or 409);
                    // with tableId resolution the server opens/uses the live active session and succeeds.
                    val response =
                        runBlocking {
                            service.createOrder(
                                request =
                                    OrderDto.CreateOrderRequestDto(
                                        tableInfoId = staleSessionId, // STALE — points at completed session
                                        tableId = tableId, // valid — wins
                                        orderItems = oneItem(product.id.value),
                                    ),
                                employeeId = manager.id.value.toString(),
                                deviceId = deviceId,
                                actorCapabilities = emptySet(),
                            )
                        }

                    val attachedSessionId =
                        transaction {
                            Orders
                                .findById(response.orderId)!!
                                .tableSession.id.value
                        }
                    val attachedCompleted =
                        transaction {
                            TableSession.findById(attachedSessionId)!!.completed
                        }
                    // The order did NOT attach to the stale completed session, and its session is live.
                    assertNotEquals(staleSessionId, attachedSessionId)
                    assertEquals(false, attachedCompleted, "order must attach to a live (non-completed) session")
                },
            ) { _ -> }
    }
}
