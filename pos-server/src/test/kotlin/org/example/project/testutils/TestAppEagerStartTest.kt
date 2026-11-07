package org.example.project.testutils

import org.example.project.database.DatabaseFactory
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard for [testApp]'s ordering contract: `setupData` runs BEFORE the trailing block, against a
 * database that is still connected.
 *
 * Ktor's `testApplication` starts the `application { }` module lazily — on the first client request,
 * or failing that only at the very end of `runTestApplication`. Without the eager `startApplication()`
 * in [testApp], a trailing block issuing no HTTP request ran `TestDatabase.setup()` and `setupData`
 * only *after* `finally { TestDatabase.teardown() }` had already fired. That stranded the H2 pool for
 * the next class and forced ~35 test classes to keep every assertion inside `setupData` behind an
 * empty `{ _ -> }` block, because anything in the block ran against an unconnected database.
 *
 * Removing the `startApplication()` call makes this test fail with
 * `IllegalStateException: No database specified and no default database found` — verified, not assumed.
 */
@DisplayName("testApp runs setupData before the trailing block, against a live database")
class TestAppEagerStartTest {
    @Test
    fun `setupData runs before the trailing block against a live database`() {
        val events = mutableListOf<String>()
        var dbLiveInBlock = false
        var setupRowVisibleInBlock = false

        testApp(
            setupData = {
                events += "setupData"
                TestData.createAdmin(employeeNumber = "EAGER_ADM", username = "eager_adm")
            },
        ) { _ ->
            events += "block"
            dbLiveInBlock = DatabaseFactory.isInitialized()
            setupRowVisibleInBlock = transaction {
                Employee.find { EmployeeTable.username eq "eager_adm" }.count()
            } == 1L
        }

        assertEquals(listOf("setupData", "block"), events, "setupData must run BEFORE the trailing block")
        assertTrue(dbLiveInBlock, "database must still be connected inside the trailing block")
        assertTrue(setupRowVisibleInBlock, "a row created in setupData must be readable from the trailing block")
    }
}
