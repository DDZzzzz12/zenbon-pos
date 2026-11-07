package org.example.project.routes

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.data.service.PermissionGroupServiceImpl
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two group guardrails the HTTP route cannot provoke, exercised against the service directly.
 *
 * Both are unreachable from outside for the same structural reason: only `ADMIN` holds
 * `PERMISSION_ADMIN`, and `ADMIN` is every capability by construction — so an admin can never grant
 * something they lack, and their own group is a system group no edit may touch. Driving these
 * through the API would be testing nothing.
 *
 * They are still worth having, and worth testing. The escalation bound is what stops "may manage
 * groups" from meaning "may become admin" the moment a later stage widens who holds
 * `PERMISSION_ADMIN`, and a guardrail written *after* that widening is a guardrail that was missing
 * in between. `PermissionGroupAssignmentRoutesTest` recorded that debt in Stage 3; this pays it.
 *
 * **Not covered, deliberately:** the shop-wide half of the lockout floor — "this edit would leave
 * *nobody* holding it". It cannot be reached at all while both critical capabilities are RESERVED
 * and `ADMIN` holds everything: any admin account keeps the holder count above zero. Faking it would
 * need a shop with no admin, which the migration itself forbids.
 */
@DisplayName("Permission group guardrails")
class PermissionGroupGuardrailTest {
    private fun service() = PermissionGroupServiceImpl(PermissionGroupRepositoryImpl(), GroupCapabilityResolver())

    private fun idOf(username: String) =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
        }

    private fun findGroup(code: String) = transaction { PermissionGroupRepositoryImpl().findGroup(code) }

    private fun putInGroup(
        employeeId: UUID,
        code: String,
    ) {
        transaction {
            val groupId =
                PermissionGroupTable
                    .selectAll()
                    .where { PermissionGroupTable.code eq code }
                    .first()[PermissionGroupTable.id]
            EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq employeeId }
            EmployeePermissionGroupTable.insert {
                it[employee] = employeeId
                it[group] = groupId
            }
        }
    }

    @Test
    fun `an actor cannot grant a capability they do not hold themselves`() =
        testApp(setupData = { TestData.createManager() }) { _ ->
            val manager = idOf("manager")

            // TIMETRACKING_ALL_READ — reading everyone's hours — is an AUDITOR capability MANAGER
            // deliberately lacks, and it is ASSIGNABLE, so it gets past the reserved check and lands
            // on the escalation bound rather than being refused for the wrong reason.
            assertTrue(
                Capability.TIMETRACKING_ALL_READ !in runBlocking { GroupCapabilityResolver().forEmployee(manager) },
                "the fixture assumes a manager does not hold TIMETRACKING_ALL_READ",
            )

            val failure =
                assertFailsWith<PosException> {
                    runBlocking {
                        service().create(
                            PermissionGroupDto.CreateGroupRequestDto(
                                code = "SUPERVISORS",
                                name = "Supervisors",
                                capabilities = setOf("TIMETRACKING_READ", "TIMETRACKING_ALL_READ"),
                            ),
                            manager,
                        )
                    }
                }

            assertEquals(HttpStatusCode.Forbidden, failure.httpStatus)
            assertTrue(
                failure.message!!.contains("TIMETRACKING_ALL_READ"),
                "the rejection must name what was refused, or the operator cannot act on it",
            )
            assertNull(findGroup("SUPERVISORS"), "a refused create must leave nothing behind")
        }

    @Test
    fun `an edit removing a lockout-critical capability from the actor's own group is refused`() =
        testApp(setupData = { TestData.createAdmin() }) { _ ->
            val admin = idOf("admin")

            // A non-system group carrying SYSTEM_CONFIG cannot be built through the API, because
            // SYSTEM_CONFIG is RESERVED. It is written straight to the tables here — which is exactly
            // the state a database seeded by a build with different assignability rules would be in,
            // and the state this guardrail exists for.
            transaction {
                PermissionGroupRepositoryImpl().createGroup(
                    code = "OPS",
                    name = "Ops",
                    description = null,
                    capabilities = setOf(Capability.SYSTEM_CONFIG, Capability.EMPLOYEE_READ, Capability.SELF_READ),
                )
            }
            putInGroup(admin, "OPS")

            val failure =
                assertFailsWith<PosException> {
                    runBlocking {
                        service().update(
                            "OPS",
                            PermissionGroupDto.UpdateGroupRequestDto(
                                capabilities = setOf("EMPLOYEE_READ", "SELF_READ"),
                            ),
                            admin,
                        )
                    }
                }

            assertEquals(HttpStatusCode.Forbidden, failure.httpStatus)
            assertTrue(failure.message!!.contains("SYSTEM_CONFIG"))
            assertTrue(
                Capability.SYSTEM_CONFIG in findGroup("OPS")!!.capabilities,
                "an edit the actor could not undo must change nothing",
            )
        }
}
