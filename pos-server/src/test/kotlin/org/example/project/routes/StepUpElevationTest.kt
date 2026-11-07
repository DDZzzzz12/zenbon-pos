package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Step-up: a manager lends a cashier one authority for a few minutes.
 *
 * The behaviour under test is a **union**, and every assertion here is about a way the previous
 * *replacement* got it wrong:
 *
 * - it granted the manager's entire reach for a PIN entered to void one bill;
 * - being a replacement it could also take capabilities away, since the manager's own restrictions
 *   applied instead of the cashier's;
 * - it attributed every action in the window to the manager, when a cashier performed them;
 * - and it was keyed on the device alone, so a second person at the same till inherited it.
 */
@OptIn(ExperimentalTime::class)
@DisplayName("Step-up elevation")
class StepUpElevationTest {
    /**
     * A device id per test.
     *
     * `TemporarySessionStore` is a process-wide `object`, so a session created by one test is still
     * there when the next runs and `getSessionByDevice` would answer with it. Keying each test to
     * its own till is cheaper than clearing global state and mirrors reality — a step-up belongs to
     * one station.
     */
    private var device = "POS-STEPUP"

    @BeforeEach
    fun freshDevice(info: TestInfo) {
        device = "POS-STEPUP-${info.displayName.hashCode()}"
        TemporarySessionStore.getSessionByDevice(device)?.let { TemporarySessionStore.removeSession(it.sessionId) }
    }

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    /** The fixtures create no PIN, and a step-up is authorised by PIN. */
    private fun setPin(
        username: String,
        pin: String,
    ) = transaction {
        Employee.find { EmployeeTable.username eq username }.first().pin = pin
    }

    private fun body(
        manager: String,
        pin: String,
        capability: String? = null,
    ): TextContent {
        val cap = capability?.let { ""","requestedCapability":"$it"""" } ?: ""
        return TextContent(
            """{"managerUsername":"$manager","managerPin":"$pin","deviceId":"$device"$cap}""",
            ContentType.Application.Json,
        )
    }

    private fun session() = TemporarySessionStore.getSessionByDevice(device)

    @Test
    fun `a step-up lends exactly the capability that was asked for`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)

            val created =
                client.post("/api/auth/temporary-session") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                    setBody(body("manager", "4321", "BILL_VOID"))
                }
            assertEquals(HttpStatusCode.Created, created.status)

            val session = assertNotNull(session())
            assertEquals(
                setOf(Capability.BILL_VOID),
                session.grantedCapabilities,
                "a PIN entered to void one bill must not hand over the manager's whole reach",
            )
            assertEquals(
                idOf("cashier").toString(),
                session.requestingEmployeeId,
                "the session belongs to the cashier who asked for it, not to the approver",
            )
        }

    @Test
    fun `an unscoped step-up lends the till overrides and nothing else`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)

            // The generic "manager override" dialog is opened from the drawer and does not know what
            // prompted it, so it names no capability. That must not mean "everything".
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321"))
            }

            val granted = assertNotNull(session()).grantedCapabilities
            assertTrue(Capability.BILL_VOID in granted)
            assertTrue(Capability.PRICE_OVERRIDE in granted)
            assertFalse(Capability.EMPLOYEE_WRITE in granted, "the back office is not a till override")
            assertFalse(Capability.ZBON_CLOSE in granted, "closing the day is not something to lend across a counter")
            assertFalse(Capability.SYSTEM_CONFIG in granted)
        }

    @Test
    fun `a capability nobody may lend is refused outright`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier()
            },
        ) { client ->
            setPin("admin", "9999")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)

            // An ADMIN holds BACKUP_RESTORE. That is not a reason to be able to hand it across a
            // counter for three minutes, so it is rejected rather than quietly intersected away.
            assertStatus(HttpStatusCode.Forbidden) {
                client.post("/api/auth/temporary-session") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                    setBody(body("admin", "9999", "BACKUP_RESTORE"))
                }
            }
            assertEquals(null, session(), "a refused step-up must leave no session behind")
        }

    @Test
    fun `an approver who cannot authorise a step-up is refused`() =
        testApp(
            setupData = {
                TestData.createCashier()
                TestData.createCashier(employeeNumber = "CSH002", username = "colleague")
            },
        ) { client ->
            setPin("colleague", "1111")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)

            // Correct PIN, active account, and still refused: a cashier does not hold
            // ELEVATION_APPROVE. Two cashiers must not be able to authorise each other.
            val response =
                client.post("/api/auth/temporary-session") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                    setBody(body("colleague", "1111", "BILL_VOID"))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(null, session())
        }

    @Test
    fun `an unknown capability is a 400 rather than a session that lends nothing`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)

            assertStatus(HttpStatusCode.BadRequest) {
                client.post("/api/auth/temporary-session") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                    setBody(body("manager", "4321", "BILL_VOIDD"))
                }
            }
        }

    @Test
    fun `the lent capability reaches the gate on the cashier's own token`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)

            // A cashier holds no TABLE_SESSION_OVERRIDE, so force-closing a live session is refused.
            //
            // Deliberately NOT a bill route: DELETE /api/v1/bills/{id} is transactional, so the
            // terminal-capability ceiling answers 409 for an unpaired device before the capability
            // gate ever runs. It cannot probe this at all.
            assertStatus(HttpStatusCode.Forbidden) {
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            }

            setPin("manager", "4321")
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321", "TABLE_SESSION_OVERRIDE"))
            }

            // Same token, same person — now past the gate. The table does not exist, and that is the
            // point: the refusal is no longer an authorization one.
            val afterElevation =
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            assertTrue(
                afterElevation.status != HttpStatusCode.Forbidden,
                "the lent capability must reach the route gate; got ${afterElevation.status} ${afterElevation.bodyAsText()}",
            )
        }

    /** T9-9: the till's "extend" had no server route (403) — it must move the expiry. */
    @Test
    fun `extending a step-up moves its expiry`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321", "TABLE_SESSION_OVERRIDE"))
            }
            // Pretend most of the window has passed.
            val nearlyOver = assertNotNull(session()).let { it.copy(expiresAt = it.createdAt.plus(10.seconds)) }
            TemporarySessionStore.addSession(nearlyOver)

            val extended =
                client.put("/api/auth/temporary-session/${nearlyOver.sessionId}/extend") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            assertEquals(HttpStatusCode.OK, extended.status, extended.bodyAsText())
            val after = assertNotNull(session())
            assertTrue(after.expiresAt > nearlyOver.expiresAt, "extend must move the expiry forward")
            assertTrue(
                after.expiresAt <= Clock.System.now().plus(180.seconds),
                "an extension never grants more than a fresh step-up would",
            )
        }

    /**
     * The hard cap (user decision, T9 follow-up): a till that extends on every navigation kept one
     * PIN entry alive for a whole shift. An extension may move the expiry, but never past
     * `createdAt + 15 min`.
     */
    @Test
    fun `an extension never moves the expiry past the 15-minute hard cap`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321", "TABLE_SESSION_OVERRIDE"))
            }
            // The PIN was entered 14 minutes ago and the session was kept alive since; one minute of
            // the cap is left, less than the three a plain extension would add.
            val now = Clock.System.now()
            val lateInLife =
                assertNotNull(session()).copy(createdAt = now.minus(14.minutes), expiresAt = now.plus(10.seconds))
            TemporarySessionStore.addSession(lateInLife)
            val cap = lateInLife.createdAt.plus(15.minutes)

            val extended =
                client.put("/api/auth/temporary-session/${lateInLife.sessionId}/extend") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            assertEquals(HttpStatusCode.OK, extended.status, extended.bodyAsText())
            val after = assertNotNull(session())
            assertTrue(after.expiresAt > lateInLife.expiresAt, "within the cap an extension still moves the expiry")
            assertEquals(cap, after.expiresAt, "an extension is clipped to createdAt + 15 min, never beyond")
            assertTrue(
                extended.bodyAsText().contains(""""hardCapAt":"$cap""""),
                "the till learns the cap from the response: ${extended.bodyAsText()}",
            )

            // A second extension at the cap cannot move it either.
            client.put("/api/auth/temporary-session/${lateInLife.sessionId}/extend") {
                bearerAuth(staff)
                header("X-Device-ID", device)
            }
            assertEquals(cap, assertNotNull(session()).expiresAt)
        }

    /** Past the cap the approval is over: extend is refused by name, and the lend stops working. */
    @Test
    fun `an extension after the hard cap is refused and the lent capability is gone`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321", "TABLE_SESSION_OVERRIDE"))
            }
            // Entered 16 minutes ago; its sliding expiry alone would still say "a minute left".
            val now = Clock.System.now()
            val pastCap =
                assertNotNull(session()).copy(createdAt = now.minus(16.minutes), expiresAt = now.plus(60.seconds))
            TemporarySessionStore.addSession(pastCap)

            val refused =
                client.put("/api/auth/temporary-session/${pastCap.sessionId}/extend") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
            assertTrue(refused.bodyAsText().contains("AUTH_STEP_UP_CAP_REACHED"), refused.bodyAsText())
            assertEquals(null, session(), "the session ends at the cap")

            assertStatus(HttpStatusCode.Forbidden) {
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            }
        }

    /**
     * T9-9: "Sitzung beenden" ended the step-up only on the till; with no server route the lent
     * capability stayed live until expiry. Ending it must withdraw it on the next request.
     */
    @Test
    fun `ending a step-up withdraws the lent capability at once`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321", "TABLE_SESSION_OVERRIDE"))
            }
            val sessionId = assertNotNull(session()).sessionId
            val whileLent =
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            assertTrue(whileLent.status != HttpStatusCode.Forbidden, "precondition: the lend works")

            val ended =
                client.delete("/api/auth/temporary-session/$sessionId") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            assertEquals(HttpStatusCode.NoContent, ended.status, ended.bodyAsText())
            assertEquals(null, session())

            assertStatus(HttpStatusCode.Forbidden) {
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(staff)
                    header("X-Device-ID", device)
                }
            }
        }

    @Test
    fun `a colleague cannot end or extend someone else's step-up`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
                TestData.createCashier(employeeNumber = "CSH003", username = "colleague")
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321", "TABLE_SESSION_OVERRIDE"))
            }
            val sessionId = assertNotNull(session()).sessionId
            val other = TestAuthHelper.loginAs(client, "colleague", deviceId = device)

            assertStatus(HttpStatusCode.Forbidden) {
                client.delete("/api/auth/temporary-session/$sessionId") {
                    bearerAuth(other)
                    header("X-Device-ID", device)
                }
            }
            assertStatus(HttpStatusCode.Forbidden) {
                client.put("/api/auth/temporary-session/$sessionId/extend") {
                    bearerAuth(other)
                    header("X-Device-ID", device)
                }
            }
            assertNotNull(session(), "the owner's step-up must survive a colleague's attempt")
        }

    @Test
    fun `a step-up granted to one employee does not elevate another at the same till`() =
        testApp(
            setupData = {
                TestData.createManager()
                TestData.createCashier()
                TestData.createCashier(employeeNumber = "CSH003", username = "colleague")
            },
        ) { client ->
            setPin("manager", "4321")
            val staff = TestAuthHelper.loginAs(client, "cashier", deviceId = device)
            client.post("/api/auth/temporary-session") {
                bearerAuth(staff)
                header("X-Device-ID", device)
                setBody(body("manager", "4321", "TABLE_SESSION_OVERRIDE"))
            }
            assertNotNull(session())

            // The old lookup was by device alone, so every request arriving from this till inherited
            // the elevation — including a second cashier's, and including queued background calls.
            val other = TestAuthHelper.loginAs(client, "colleague", deviceId = device)
            assertStatus(HttpStatusCode.Forbidden) {
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(other)
                    header("X-Device-ID", device)
                }
            }
        }
}
