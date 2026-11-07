package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.data.service.TransactionSigningGuard
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * A3 Task 4: defense-in-depth signing guard beneath A4's route-level interceptor.
 *
 * `OrderServiceImpl.createOrder`'s Phase 2 must reject signing (before ever calling the TSE) when
 * the resolved terminal is non-fiscal (in ANY non-DISABLED mode) or not TSE-registered
 * (NORMAL only — SIMULATION mock-signs and must not 424 an unregistered fiscal terminal).
 *
 * `ShopConfigurationManager` is a process-wide singleton (mirrors `DsfinvkOrderDiscountPreisfindungTest`
 * / `DsfinvkSignedPayloadGateTest`'s pattern), so `setTseConfig` needs a `ShopInfo` row to persist
 * into — each test seeds its own via `TestData.createShopInfo()` inside `testApp`'s own fresh DB
 * (an `@AfterEach` reset is NOT used here: `testApp` tears its DB down internally before the
 * `@Test` method returns, so anything DB-bound in `@AfterEach` would run against a closed
 * connection). `setTseConfig` invalidates the process-wide cache on write, so the next test's own
 * `setTseConfig`/fresh-DB read is what keeps these isolated, exactly as the existing DSFinV-K
 * TSE-mode tests already rely on.
 */
@DisplayName("Order signing guard: never sign under a non-fiscal / TSE-unregistered terminal (A3 Task 4)")
class OrderSigningGuardTest {
    private fun seedTerminal(
        deviceId: String,
        clientId: String,
        fiscal: Boolean,
        tseClientRegistered: Boolean,
        isActive: Boolean = true,
    ) = transaction {
        val now = currentTimeStamp()
        Terminal.new {
            this.deviceId = deviceId
            this.clientId = clientId
            this.registeredAt = now
            this.lastSeenAt = now
            this.fiscal = fiscal
            this.tseClientRegistered = tseClientRegistered
            this.isActive = isActive
        }
    }

    /**
     * BLOCKER rollback proof: the guard must run INSIDE Phase 1 (pre-commit) so a rejection rolls
     * back the whole transaction — no order row may exist for the session afterward. Counts are
     * scoped to the test's own freshly-created session (testApp gives each test a fresh DB).
     */
    private fun countOrdersForSession(sessionId: Long): Long =
        transaction {
            Orders.find { OrdersTable.tableSessionId eq sessionId }.count()
        }

    /** Creates a one-item order request against the given deviceId's terminal. */
    private fun createOrderRequest(
        deviceId: String,
        employeeId: String,
        tableInfoId: Long,
        tableId: Long,
        productId: Long,
    ) = runBlocking {
        GlobalContext.get().get<OrderService>().createOrder(
            request =
                OrderDto.CreateOrderRequestDto(
                    tableInfoId = tableInfoId,
                    tableId = tableId,
                    orderItems =
                        listOf(
                            OrderItemDto.CreateOrderItemRequestDto(
                                productId = productId,
                                unitPrice = "5.0",
                                quantity = 1,
                                discount = 1.0,
                            ),
                        ),
                ),
            employeeId = employeeId,
            deviceId = deviceId,
            actorCapabilities = emptySet(),
        )
    }

    @Test
    fun `NORMAL mode rejects an unregistered fiscal terminal with TERMINAL_NOT_TSE_REGISTERED`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OSG_N1", username = "osg_n1")
                val category = TestData.createCategory(name = "OSG_Cat_N1")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OSG_Prod_N1",
                        productNumber = "OSG_N1",
                        price = BigDecimal("5.0000"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "OSG_N1")
                val deviceId = "osg-n1-device"
                seedTerminal(deviceId, "OSG-N1-CLIENT", fiscal = true, tseClientRegistered = false)
                TestData.createShopInfo()

                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.NORMAL),
                    )
                }

                val ex =
                    assertFailsWith<PosException> {
                        createOrderRequest(
                            deviceId = deviceId,
                            employeeId = manager.id.value.toString(),
                            tableInfoId = tws.session.id.value,
                            tableId = tws.table.id.value,
                            productId = product.id.value,
                        )
                    }

                assertEquals(
                    PosErrorCode.TERMINAL_NOT_TSE_REGISTERED,
                    ex.code,
                    "an unregistered fiscal terminal must be refused in NORMAL mode",
                )
                assertEquals(HttpStatusCode.FailedDependency, ex.httpStatus)

                // BLOCKER rollback proof: the guard must fire INSIDE Phase 1 (pre-commit), so the
                // thrown 424 must leave NO order committed for this session — before the fix, the
                // guard lived in Phase 2 (post-commit) and the order was already committed here.
                assertEquals(
                    0L,
                    countOrdersForSession(tws.session.id.value),
                    "the rejected order must NOT be committed — the guard must roll back Phase 1",
                )
            },
        ) { _ -> }

    @Test
    fun `NORMAL mode rejects a non-fiscal terminal with VALIDATION_FAILED (Forbidden)`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OSG_N2", username = "osg_n2")
                val category = TestData.createCategory(name = "OSG_Cat_N2")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OSG_Prod_N2",
                        productNumber = "OSG_N2",
                        price = BigDecimal("5.0000"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "OSG_N2")
                val deviceId = "osg-n2-device"
                // Non-fiscal AND unregistered — the fiscal check must fire first regardless.
                seedTerminal(deviceId, "OSG-N2-CLIENT", fiscal = false, tseClientRegistered = false)
                TestData.createShopInfo()

                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.NORMAL),
                    )
                }

                val ex =
                    assertFailsWith<PosException> {
                        createOrderRequest(
                            deviceId = deviceId,
                            employeeId = manager.id.value.toString(),
                            tableInfoId = tws.session.id.value,
                            tableId = tws.table.id.value,
                            productId = product.id.value,
                        )
                    }

                assertEquals(
                    PosErrorCode.VALIDATION_FAILED,
                    ex.code,
                    "a non-fiscal terminal must never sign, in any non-DISABLED mode",
                )
                assertEquals(HttpStatusCode.Forbidden, ex.httpStatus)

                // BLOCKER rollback proof: same as the NORMAL/unregistered case above — a non-fiscal
                // rejection must also roll back Phase 1, leaving no committed order behind.
                assertEquals(
                    0L,
                    countOrdersForSession(tws.session.id.value),
                    "the rejected order must NOT be committed — the guard must roll back Phase 1",
                )
            },
        ) { _ -> }

    @Test
    fun `DISABLED mode succeeds regardless of terminal fiscal or registration state`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OSG_D1", username = "osg_d1")
                val category = TestData.createCategory(name = "OSG_Cat_D1")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OSG_Prod_D1",
                        productNumber = "OSG_D1",
                        price = BigDecimal("5.0000"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "OSG_D1")
                val deviceId = "osg-d1-device"
                // Worst case: non-fiscal AND unregistered — DISABLED must not care.
                seedTerminal(deviceId, "OSG-D1-CLIENT", fiscal = false, tseClientRegistered = false)
                TestData.createShopInfo()

                // testApp's TSE config defaults to DISABLED; set it explicitly for clarity/robustness.
                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.DISABLED),
                    )
                }

                val response =
                    createOrderRequest(
                        deviceId = deviceId,
                        employeeId = manager.id.value.toString(),
                        tableInfoId = tws.session.id.value,
                        tableId = tws.table.id.value,
                        productId = product.id.value,
                    )

                assertNotNull(response, "order creation must succeed when TSE is DISABLED")
            },
        ) { _ -> }

    @Test
    fun `DISABLED mode still refuses an order from a device that is not a registered till`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OSG_D2", username = "osg_d2")
                val category = TestData.createCategory(name = "OSG_Cat_D2")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OSG_Prod_D2",
                        productNumber = "OSG_D2",
                        price = BigDecimal("5.0000"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "OSG_D2")
                TestData.createShopInfo()
                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.DISABLED),
                    )
                }

                // Orders persist order.terminal and feed DSFinV-K attribution, so they keep
                // requireSigningTerminal: only the Kassenbuch family relaxes in DISABLED.
                val ex =
                    assertFailsWith<PosException> {
                        createOrderRequest(
                            deviceId = "admin-web-d2",
                            employeeId = manager.id.value.toString(),
                            tableInfoId = tws.session.id.value,
                            tableId = tws.table.id.value,
                            productId = product.id.value,
                        )
                    }
                assertEquals(PosErrorCode.AUTH_FORBIDDEN, ex.code)
                assertEquals(HttpStatusCode.Forbidden, ex.httpStatus)
            },
        ) { _ -> }

    /**
     * MAJOR ceiling-bypass fix: `deactivateTerminal` only flips `isActive=false` (the token/binding
     * stay intact) and `TransactionSigningGuard.assertMaySign` previously never looked at
     * `isActive` at all — so a deactivated-but-still-fiscal-and-registered terminal could still
     * reach a TSE sign call via any HTTP caller that isn't covered by the route-level ceiling in
     * `TransactionalRoutes` (e.g. `GET /api/bills/create-with-card`, fixed alongside this test).
     * `isActive` is now the FIRST check in `assertMaySign`, unconditionally — this direct
     * object-level test pins that DISABLED mode (which bypasses every other check) still refuses a
     * deactivated terminal.
     */
    @Test
    fun `assertMaySign rejects a deactivated terminal even in DISABLED mode`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                val deviceId = "osg-deact-device"
                // Worst case for every OTHER check: fiscal + TSE-registered (would pass in any mode
                // if isActive were not checked) but isActive=false.
                val terminal =
                    seedTerminal(
                        deviceId,
                        "OSG-DEACT-CLIENT",
                        fiscal = true,
                        tseClientRegistered = true,
                        isActive = false,
                    )

                val ex =
                    assertFailsWith<PosException> {
                        TransactionSigningGuard.assertMaySign(terminal, TseMode.DISABLED)
                    }

                assertEquals(
                    PosErrorCode.VALIDATION_FAILED,
                    ex.code,
                    "a deactivated terminal must never sign, in any mode — including DISABLED",
                )
                assertEquals(HttpStatusCode.Forbidden, ex.httpStatus)
            },
        ) { _ -> }

    @Test
    fun `SIMULATION mode does NOT refuse an unregistered fiscal terminal`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "OSG_S1", username = "osg_s1")
                val category = TestData.createCategory(name = "OSG_Cat_S1")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OSG_Prod_S1",
                        productNumber = "OSG_S1",
                        price = BigDecimal("5.0000"),
                    )
                TestData.createDefaultVatRates()
                val tws = TestData.createTableWithSession(tableNumber = "OSG_S1")
                val deviceId = "osg-s1-device"
                // Fiscal but never TSE-registered — SIMULATION mock-signs and must accept this.
                seedTerminal(deviceId, "OSG-S1-CLIENT", fiscal = true, tseClientRegistered = false)
                TestData.createShopInfo()

                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.SIMULATION),
                    )
                }

                val response =
                    createOrderRequest(
                        deviceId = deviceId,
                        employeeId = manager.id.value.toString(),
                        tableInfoId = tws.session.id.value,
                        tableId = tws.table.id.value,
                        productId = product.id.value,
                    )

                assertNotNull(
                    response,
                    "SIMULATION mock-signs — an unregistered fiscal terminal must NOT be refused",
                )
            },
        ) { _ -> }

    /**
     * BLOCKER rollback proof for `BillServiceImpl.createBill`: the guard used to live in Phase 2
     * (after the bill/payments/item-status transaction committed), so a fiscal terminal with no
     * registered TSE client could commit a PAID bill (items marked PAID, session possibly closed)
     * and only then get a 424 — money taken, no signature, no recovery. Moving the guard into
     * Phase 1 means the throw rolls back the WHOLE transaction: no bill, no payment, the order
     * item stays OPEN, and the table session stays open. Harness mirrors
     * [org.example.project.service.BillTerminalLinkTest]'s `seedOrderItem`.
     */
    @Test
    fun `NORMAL mode rejects an unregistered fiscal terminal on createBill with no committed bill and an open session`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "OSG_B1", username = "osg_b1")
                val cash = TestData.createPaymentType("Cash_OSG_B1", isCash = true)
                val vat19 = TestData.createDefaultVatRates().first()
                val tws = TestData.createTableWithSession(tableNumber = "OSG_B1")
                val category = TestData.createCategory(name = "OSG_Cat_B1")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "OSG_Prod_B1",
                        productNumber = "OSG_B1",
                        price = BigDecimal("20.0000"),
                    )
                val deviceId = "osg-b1-device"
                seedTerminal(deviceId, "OSG-B1-CLIENT", fiscal = true, tseClientRegistered = false)
                TestData.createShopInfo()

                val itemId =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = admin
                        OrderItem
                            .new {
                                this.session = session
                                this.product = product
                                this.unitPrice = BigDecimal("20.00")
                                this.finalPrice = BigDecimal("20.00")
                                this.itemStatus = ItemStatus.OPEN
                                this.vat = vat19
                                this.discount = BigDecimal.ONE
                                this.customPrice = false
                                this.takeAway = false
                                this.transferred = false
                                this.sideNotes = null
                            }.id.value
                    }

                runBlocking {
                    ShopConfigurationManager.setTseConfig(
                        TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.NORMAL),
                    )
                }
                // NORMAL lives in ShopConfigurationManager's process-wide cache, which the next
                // test's fresh database does not clear. Run alone (a `*Bill*` filter selects only this
                // method of the class) it left NORMAL behind and failed OrderTerminalLinkTest.
                try {
                    val billService = GlobalContext.get().get<BillService>()

                    val ex =
                        assertFailsWith<PosException> {
                            runBlocking {
                                billService.createBill(
                                    employeeId = admin.id.value.toString(),
                                    deviceId = deviceId,
                                    request =
                                        BillDto.CreateBillRequestDto(
                                            tableSessionId = tws.session.id.value,
                                            orderItems = listOf(itemId),
                                            billAmount = "20.00",
                                            tips = "0.00",
                                            payments =
                                                listOf(
                                                    PaymentDto.PaymentRequest(amount = "20.00", paymentTypeId = cash.id.value),
                                                ),
                                        ),
                                )
                            }
                        }

                    assertEquals(
                        PosErrorCode.TERMINAL_NOT_TSE_REGISTERED,
                        ex.code,
                        "an unregistered fiscal terminal must be refused in NORMAL mode",
                    )
                    assertEquals(HttpStatusCode.FailedDependency, ex.httpStatus)

                    val billCount =
                        transaction {
                            Bill.find { BillTable.tableSession eq tws.session.id }.count()
                        }
                    assertEquals(
                        0L,
                        billCount,
                        "no bill may be committed when the pre-commit guard rejects createBill",
                    )

                    val sessionAfter = transaction { TableSession.findById(tws.session.id)!! }
                    assertEquals(
                        false,
                        sessionAfter.completed,
                        "the table session must NOT be closed when the bill is rejected pre-commit",
                    )

                    val itemAfter = transaction { OrderItem.findById(itemId)!! }
                    assertEquals(
                        ItemStatus.OPEN,
                        itemAfter.itemStatus,
                        "the order item must remain OPEN (not marked PAID) when the bill is rejected pre-commit",
                    )
                } finally {
                    runBlocking {
                        ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(timeAdminPin = "12345", mode = TseMode.DISABLED))
                    }
                }
            },
        ) { _ -> }
}
