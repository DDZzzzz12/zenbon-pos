package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof that adminApp's accountant sidebar and the server agree.
 *
 * The client track's whole premise is that a client gating on `role` offers screens the server
 * refuses. `AdminNavItemTest` asserts which items an accountant SEES, computed from the capability
 * definitions; this asserts that the endpoints behind those items actually ANSWER when a real
 * accountant calls them — and that the ones hidden from them genuinely 403.
 *
 * Neither test is sufficient alone. The nav test could agree perfectly with a mapping that is wrong
 * about what each screen calls; this one could pass while the sidebar shows the wrong things.
 *
 * **Asserts NOT-403 rather than 200 for the reachable half**, deliberately. A screen's endpoint may
 * 404 on an unmounted feature or 400 on a missing query parameter, and neither is an authorization
 * result. Folding those into the assertion would make the test fail for reasons it is not about —
 * and, worse, make someone "fix" it by loosening a gate.
 */
@DisplayName("Accountant reachability")
class AccountantReachabilityTest {
    /** The endpoint behind each sidebar item an accountant can see, from `RoutePermissions`. */
    private val reachable =
        mapOf(
            "STAFF" to "/api/employees/summary",
            "VOUCHERS" to "/api/vouchers/statistics",
            "DOCUMENTS" to "/api/documents/document-types",
            "INVOICES / SALES_INVOICES" to "/api/v1/sales-invoices",
            "DEBTORS" to "/api/debtors",
            "ALLOCATIONS" to "/api/v1/datev/export-log",
            "SUPPLIERS / BANK" to "/api/suppliers",
            "CONTRACTS" to "/api/contracts",
            "KASSENBUCH" to "/api/v1/kassenbuch/balance",
            "ANALYTICS" to "/api/statistics/sales/overview",
            "AUDIT_LOG" to "/api/items/cancellations",
            // Added after V113. The sidebar offered no Z-Bon screen to an accountant until then,
            // because ZBON_READ lists the closes and ZBON_MANAGE is what opens one.
            "Z_BONS" to "/api/z-bon/recent",
            "DATA_EXPORT" to "/api/v1/dsfinvk/validate",
        )

    /** And the endpoints behind the items their sidebar deliberately withholds. */
    private val withheld =
        mapOf(
            "PRODUCTS" to "/api/payment-types/active",
            "TERMINALS" to "/api/v1/terminals",
            "TSE" to "/api/tse/config",
            "SCHEDULED_JOBS" to "/api/scheduled-jobs",
        )

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    private suspend fun HttpClient.asAccountant(block: suspend (String) -> Unit) {
        val adminToken = TestAuthHelper.loginAsAdmin(this)
        val target = idOf("bookkeeper")
        put("/api/employees/$target/permission-group") {
            bearerAuth(adminToken)
            setBody(TextContent("""{"groupCode":"ACCOUNTANT"}""", ContentType.Application.Json))
        }
        // Log in AFTER the assignment: the resolver caches per employee, and the assignment
        // endpoint invalidates it — this is also the path that would catch a missing invalidation.
        block(TestAuthHelper.loginAs(this, "bookkeeper"))
    }

    private fun staff() {
        TestData.createAdmin()
        // Role MANAGER, group ACCOUNTANT — the two disagree on purpose. That is the case a
        // role-gated client gets wrong, and the reason this test exists.
        TestData.createManager(employeeNumber = "ACC800", username = "bookkeeper")
    }

    @Test
    fun `every screen the accountant sidebar offers is reachable`() =
        testApp(setupData = { staff() }) { client ->
            client.asAccountant { token ->
                val refused =
                    reachable
                        .filter { (_, path) ->
                            client.get(path) { bearerAuth(token) }.status == HttpStatusCode.Forbidden
                        }.map { (item, path) -> "$item -> $path" }

                assertEquals(
                    emptyList(),
                    refused,
                    "the sidebar offers these and the server refuses them — the mapping in " +
                        "AdminNavItem names a capability the screen's endpoint does not require",
                )
            }
        }

    @Test
    fun `every screen it withholds is genuinely refused`() =
        testApp(setupData = { staff() }) { client ->
            client.asAccountant { token ->
                withheld.forEach { (item, path) ->
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        client.get(path) { bearerAuth(token) }.status,
                        "$item is hidden from an accountant's sidebar, so the server must refuse it too — " +
                            "hiding a control the API still allows is a UI convention, not a guard",
                    )
                }
            }
        }

    @Test
    fun `the same person as a plain manager reaches what the accountant cannot`() =
        testApp(setupData = { staff() }) { client ->
            // The control, and the SAME PERSON — logged in without the group assignment, so they
            // are still in the role-derived MANAGER group. Without this, the test above would pass
            // just as well if these endpoints were broken for everyone, or if the assignment had
            // silently done nothing at all.
            val token = TestAuthHelper.loginAs(client, "bookkeeper")
            withheld
                .filterKeys { it != "TSE" } // TSE_ADMIN is admin-only, not a manager screen
                .forEach { (item, path) ->
                    assertTrue(
                        client.get(path) { bearerAuth(token) }.status != HttpStatusCode.Forbidden,
                        "$item must stay reachable for a manager — the accountant group is what narrows it",
                    )
                }
        }

    @Test
    fun `the accountant is still role MANAGER, so a role-gated client would get this wrong`() =
        testApp(setupData = { staff() }) { client ->
            client.asAccountant { token ->
                // Stated as an assertion because it is the premise of the whole client track: the
                // assignment endpoint does not touch the role, so role and capabilities disagree and
                // only the capability answer is correct.
                val me = client.get("/api/auth/me") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, me.status)

                val catalogManage = client.get("/api/payment-types/active") { bearerAuth(token) }
                assertEquals(
                    HttpStatusCode.Forbidden,
                    catalogManage.status,
                    "role MANAGER would allow this; group ACCOUNTANT is what refuses it",
                )
            }
        }
}
