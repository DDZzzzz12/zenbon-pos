package org.example.project.infrastructure.security

import org.example.project.testutils.SourceRoots
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * An employee built with `Employee.new` and nothing else holds NO capabilities.
 *
 * `EmployeeRepositoryImpl` is the only code that assigns a permission group, and
 * `GroupCapabilityResolver` fails closed on an employee who has none — deliberately, because
 * falling back to a role-derived default would hand a mis-migrated employee somebody else's access.
 * The consequence is that constructing the entity directly produces an employee who can reach
 * nothing, and the failure surfaces far from its cause: a 403 on an unrelated route.
 *
 * **This has been the same bug three times**, which is why it is now a test rather than a comment:
 *  - `TestData.createEmployee` — caught during Stage 2a, when every route test started failing.
 *  - `TestData.createTerminalDeviceEmployee` — latent; the device-token path resolves through the
 *    bundle, so nothing noticed until this sweep.
 *  - `createDevUser` in PRODUCTION — dev mode created an ADMIN, gave them no group, and then refused
 *    every one of the ~181 routes Stage 1 turned from `Authenticated` into `Cap`. Dev mode
 *    authenticated you and locked you out.
 *
 * Source-level because the property is "no call site does X". A behavioural test would have to
 * enumerate the creators, which is exactly the enumeration that keeps going stale.
 */
@DisplayName("Employees are never created without a permission group")
class NoDirectEmployeeCreationTest {
    private companion object {
        /** Kotlin files under [SourceRoots.serverMainRoots] after the ecr data move (stage B2-2) added `ecr/data` to the roots: 934 files, floor 930. */
        const val MINIMUM_MAIN_FILES = 930

        /** Kotlin files under [SourceRoots.serverTestRoots] on 2026-10-27, before the platform extraction moved any. */
        const val MINIMUM_TEST_FILES = 944
    }

    private fun kotlinFilesUnder(
        guard: String,
        roots: List<File>,
        minimum: Int,
    ): List<File> = SourceRoots.kotlinFiles(roots).also { SourceRoots.requireScanned(guard, it.size, minimum) }

    @Test
    fun `production code that builds an Employee also assigns its group`() {
        // The property is PAIRING, not abstinence. `createDevUser` has a fair reason to build the
        // entity directly — it runs before any request, from a plugin with no repository injected —
        // so the rule is that building one and grouping it travel together.
        val offenders =
            kotlinFilesUnder("NoDirectEmployeeCreationTest (main)", SourceRoots.serverMainRoots(), MINIMUM_MAIN_FILES)
                .filter { it.name != "EmployeeRepositoryImpl.kt" }
                .map { it to it.readText() }
                .filter { (_, text) -> text.contains("Employee.new") }
                .filterNot { (_, text) -> text.contains("setPermissionGroupByCode") }
                .map { (file, _) -> file.name }

        assertEquals(
            emptyList(),
            offenders,
            "this file builds an Employee entity and never assigns a permission group — it can " +
                "reach nothing at all. Call EmployeeRepositoryImpl().setPermissionGroupByCode(id, role.name).",
        )
    }

    @Test
    fun `every test fixture that builds one assigns its group`() {
        // TestData may construct entities directly — it seeds fixtures, not production rows — but
        // each creator must call `assignRoleGroup`. Counting rather than matching per-creator: the
        // point is that no `Employee.new` goes ungrouped, and the counts moving apart is the signal.
        val testData = SourceRoots.fileUnder(SourceRoots.serverTestRoots(), "org/example/project/testutils/TestData.kt").readText()
        val built = Regex("Employee\\s*\\n?\\s*\\.?new \\{").findAll(testData).count()
        val assigned = Regex("assignRoleGroup\\(").findAll(testData).count() - 1 // minus the declaration

        assertEquals(
            built,
            assigned,
            "TestData builds $built employees but assigns $assigned groups — an ungrouped fixture " +
                "makes every capability-gated test fail for a reason that looks nothing like its cause",
        )
    }

    @Test
    fun `no other test fixture builds one behind the repository's back`() {
        val offenders =
            kotlinFilesUnder("NoDirectEmployeeCreationTest (test)", SourceRoots.serverTestRoots(), MINIMUM_TEST_FILES)
                .filter { it.name != "TestData.kt" && it.name != "NoDirectEmployeeCreationTest.kt" }
                .filter { it.readText().contains("Employee.new") }
                .map { it.name }

        assertEquals(emptyList(), offenders, "use TestData, which assigns the permission group")
    }
}
