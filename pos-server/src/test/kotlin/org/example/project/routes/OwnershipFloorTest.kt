package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.RoutePermissions
import org.example.project.infrastructure.security.requiredCapabilities
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Stage 1c: the routes that address one employee's row must actually check *whose* row it is.
 *
 * `AccessLevel.Custom` used to tell the plugin to skip enforcement and trust the handler. **Three of
 * its eight handlers checked nothing.** The registry said "self or manager", one route's own comment
 * said "manager only", and neither was true.
 *
 * Of the three, exactly one was **live**: `GET /api/employees/{employeeId}/schedule`, where any
 * authenticated caller — a login-less terminal included — could read anyone's shifts. The other two
 * are time-tracking routes, and that feature has been unmounted since 2026-05-07, so they were
 * unreachable rather than exposed. Their tests are `@Disabled` for that reason and will run when the
 * feature returns; fixing the handlers now is what stops the hole shipping with the redesign.
 * Discovering which was which took a behavioural test — reading the handlers only showed that all
 * three were unguarded, not which ones anyone could reach.
 *
 * That is the failure mode a per-route escape hatch produces: the central gate defers, the handler
 * is assumed to compensate, and nobody checks that it does. This test is what checks.
 *
 * Behavioural on purpose. A static assertion that "the handler calls `isSelfOrHolds`" would pass for
 * a handler that calls it and ignores the answer; only a real 403 proves the guard.
 */
@DisplayName("Ownership floor")
class OwnershipFloorTest {
    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    /** Two employees, so "someone else's row" is a real row rather than a 404 in disguise. */
    private fun twoStaff() {
        TestData.createManager()
        TestData.createCashier()
    }

    private suspend fun HttpClient.getAs(
        token: String,
        path: String,
    ) = get(path) { bearerAuth(token) }

    @Test
    fun `the registry lists exactly the routes this test covers`() {
        // A new CapWithOwnership rule added without a test here would otherwise inherit the exact
        // problem Stage 1c fixed: a rule that says ownership is checked, and nothing confirming it.
        val declared =
            RoutePermissions.modules
                .flatMap { it.rules.entries }
                .filter { (_, level) -> level is AccessLevel.CapWithOwnership }
                .map { (key, _) -> "${key.second.value} ${key.first}" }
                .toSet()

        assertEquals(
            setOf(
                "GET /api/employees/{id}",
                "PUT /api/employees/{id}",
                "PATCH /api/employees/{id}/password",
                "GET /api/employees/{employeeId}/schedule",
                "GET /api/timetracking/employee/{employeeId}",
                "GET /api/timetracking/employee/{employeeId}/overview",
                // The manager step-up (2026-10-03, emu21 NOTE-v: the order station's way in is
                // ORDER_WRITE). Behaviour: `StepUpElevationTest` — "a colleague cannot end or extend
                // someone else's step-up"; the POST takes the requester from the token, never the body.
                "POST /api/auth/temporary-session",
                "PUT /api/auth/temporary-session/{sessionId}/extend",
                "DELETE /api/auth/temporary-session/{sessionId}",
            ),
            declared,
            "add the new ownership route to this test before adding it to the registry",
        )
    }

    @Test
    fun `no rule can still bypass the central gate`() {
        // The point of Stage 1c in one assertion. `Custom` is gone from the sealed class, so this
        // cannot regress by adding a rule — only by reintroducing the arm, which this would catch
        // at compile time anyway. Kept as a statement of intent for the next reader.
        val ungated =
            RoutePermissions.modules
                .flatMap { it.rules.entries }
                .filter { (_, level) ->
                    level !is AccessLevel.Public && level !is AccessLevel.Maintainer && level.requiredCapabilities.isEmpty()
                }.map { (key, _) -> "${key.second.value} ${key.first}" }

        assertEquals(emptyList(), ungated, "every non-public rule must name at least one capability")
    }

    @Test
    fun `the maintainer arm is the one gate that names no capability, and it is still enforced centrally`() {
        // `Maintainer` is not a capability (the maintainer is no employee, so no permission group can
        // grant it), which is why the test above exempts it. The exemption must stay exactly this wide:
        // a second route on this arm would be a new door nobody reviewed. The plugin enforces the arm
        // before routing, and `MaintainerContainmentTest` proves nothing else accepts its token.
        val maintainerRules =
            RoutePermissions.modules
                .flatMap { it.rules.entries }
                .filter { (_, level) -> level is AccessLevel.Maintainer }
                .map { (key, _) -> "${key.second.value} ${key.first}" }
                .toSet()

        assertEquals(setOf("GET /api/maintenance/logs"), maintainerRules)
    }

    @Test
    fun `a cashier may read their own employee record`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(HttpStatusCode.OK, client.getAs(token, "/api/employees/${idOf("cashier")}").status)
        }

    @Test
    fun `a cashier may not read another employee's record`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            assertEquals(HttpStatusCode.Forbidden, client.getAs(token, "/api/employees/${idOf("manager")}").status)
        }

    @Test
    fun `a manager may read another employee's record`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(HttpStatusCode.OK, client.getAs(token, "/api/employees/${idOf("cashier")}").status)
        }

    @Test
    fun `a cashier may read their own schedule but not a colleague's`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)

            // Unguarded before Stage 1c: this returned 200 for anyone.
            assertEquals(
                HttpStatusCode.Forbidden,
                client.getAs(token, "/api/employees/${idOf("manager")}/schedule").status,
                "a cashier must not read a colleague's shifts",
            )
            assertEquals(HttpStatusCode.OK, client.getAs(token, "/api/employees/${idOf("cashier")}/schedule").status)
        }

    @Test
    fun `a manager may read a colleague's schedule`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            assertEquals(HttpStatusCode.OK, client.getAs(token, "/api/employees/${idOf("cashier")}/schedule").status)
        }

    @Test
    @Disabled(
        "Time tracking is de-integrated: Routing.kt does not call timeTrackingRoutes(), so these " +
            "endpoints 404. The ownership guard they assert IS implemented (Stage 1c) and this will " +
            "pass as-is once the feature is re-mounted — re-enable in that same change.",
    )
    fun `a cashier may read their own timesheet but not a colleague's`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            val mine = idOf("cashier")
            val theirs = idOf("manager")

            // Unguarded before Stage 1c: personnel hours, readable by anyone with a token.
            assertEquals(
                HttpStatusCode.Forbidden,
                client.getAs(token, "/api/timetracking/employee/$theirs?year=2026&month=8").status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.getAs(token, "/api/timetracking/employee/$mine?year=2026&month=8").status,
            )
        }

    @Test
    @Disabled(
        "Time tracking is de-integrated: Routing.kt does not call timeTrackingRoutes(), so these " +
            "endpoints 404. The ownership guard they assert IS implemented (Stage 1c) and this will " +
            "pass as-is once the feature is re-mounted — re-enable in that same change.",
    )
    fun `a cashier may not read a colleague's monthly overview`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)
            // Its own comment said "manager only" and it enforced nothing.
            assertEquals(
                HttpStatusCode.Forbidden,
                client.getAs(token, "/api/timetracking/employee/${idOf("manager")}/overview?year=2026&month=8").status,
            )
        }

    @Test
    @Disabled(
        "Time tracking is de-integrated: Routing.kt does not call timeTrackingRoutes(), so these " +
            "endpoints 404. The ownership guard they assert IS implemented (Stage 1c) and this will " +
            "pass as-is once the feature is re-mounted — re-enable in that same change.",
    )
    fun `a manager may read a colleague's timesheet and overview`() =
        testApp(setupData = { twoStaff() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val theirs = idOf("cashier")
            assertEquals(
                HttpStatusCode.OK,
                client.getAs(token, "/api/timetracking/employee/$theirs?year=2026&month=8").status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.getAs(token, "/api/timetracking/employee/$theirs/overview?year=2026&month=8").status,
            )
        }

    @Test
    fun `the write verbs are covered by the registry check above`() {
        // PUT /api/employees/{id} and PATCH /{id}/password already had working ownership checks
        // before Stage 1c; EmployeeAdminGuardsRoutesTest covers their behaviour. Stage 1c only moved
        // them from `role.isManagerOrHigher()` to EMPLOYEE_WRITE / EMPLOYEE_CREDENTIAL_RESET, which
        // is verdict-identical — both capabilities sit in MANAGER and above and in no lesser group.
        val writeRules =
            RoutePermissions.modules
                .flatMap { it.rules.entries }
                .filter { (key, level) ->
                    level is AccessLevel.CapWithOwnership && key.second != HttpMethod.Get
                }
        // + the three step-up verbs (start / extend / end), whose ownership StepUpElevationTest covers.
        assertEquals(5, writeRules.size)
    }
}
