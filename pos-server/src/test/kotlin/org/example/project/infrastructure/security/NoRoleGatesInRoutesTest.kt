package org.example.project.infrastructure.security

import org.example.project.testutils.SourceRoots
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * No route handler may gate on a ROLE.
 *
 * The sweep that removed the last 26 of them found three distinct failures, which is why this is a
 * standing test rather than a one-off cleanup:
 *
 * 1. **Ten checked nothing.** `call.isManagerOrHigher()` written as a bare statement — the boolean
 *    discarded — in `ZBonRoutes` and `DsfinvkExportRoutes`. Three more in `LocationTypeRoutes`
 *    returned without responding at all, so a refused caller would have received an empty 404.
 * 2. **The rest contradict the group model.** Role and group legitimately disagree since Stage 3: an
 *    AUDITOR holds `ZBON_MANAGE` but may carry role `CASHIER`, so a role gate refuses them at a
 *    route their group admits. That is defect D2 reappearing one route at a time.
 * 3. **Two copies of the helpers had already drifted** — `server-common`'s `hasAnyRole` never got
 *    the ADMIN escape `hasRole` has (defect D3), because the Stage 1 fix landed on the other copy.
 *
 * Scanning source text rather than reflecting on handlers is deliberate: the failure mode is a
 * `String`-level pattern ("someone typed a role check into a route file"), and a reflective test
 * cannot see a discarded boolean at all.
 *
 * **Scope is route files only.** Role comparisons survive elsewhere on purpose — "may this actor
 * reset THAT employee's password", the rotation pool, the notification fan-out. Those are
 * relationship rules the registry cannot express, and they are not route gates.
 */
@DisplayName("No role gates in route files")
class NoRoleGatesInRoutesTest {
    /**
     * Patterns that mean "a route handler is deciding access from a role".
     *
     * The `call.`-prefixed helpers are the family this sweep deleted. The three below them were
     * **added after this test first shipped and passed**: it only matched the `call.` forms, so
     * `EmployeeRole.isManagerOrAdmin(claim.role)` in `StatisticsRoutes` and `role.isManagerOrHigher()`
     * in `EmployeeRoutes` sailed through a guard that reported the sweep complete. A guard that asks
     * a narrower question than its name claims is worse than none — this one asserted "no role gates"
     * while three remained.
     */
    private val forbidden =
        listOf(
            "call.isManagerOrHigher()",
            "call.hasRole(",
            "call.hasAnyRole(",
            "call.hasPermission(",
            "call.hasAnyPermission(",
            "call.requirePermissionOrRole(",
            "call.requireAnyPermission(",
            "isManagerOrAdmin(",
            ".isManagerOrHigher()",
            "isCashier()",
        )

    /**
     * Route files that compare roles for something other than gating the route.
     *
     * One reason only, and it is a RELATIONSHIP between actor and target — "may this person act on
     * THAT person" — which a route rule cannot express because the registry never sees the target.
     */
    private val allowed =
        mapOf(
            "EmployeeRoutes.kt" to "only an ADMIN may reset an ADMIN's credentials — actor vs target, not a route gate",
        )

    /** `pos-server` (and its `platform/server` successor) plus `:ecr-reservation`, the route files only. */
    private fun scannedFiles(): List<File> = SourceRoots.kotlinFiles(SourceRoots.serverMainRoots() + SourceRoots.moduleMainRoots("ecr/reservation"))

    private fun routeFiles(): List<File> {
        val scanned = scannedFiles()
        SourceRoots.requireScanned("NoRoleGatesInRoutesTest", scanned.size, MINIMUM_SCANNED_FILES)
        return scanned.filter { SourceRoots.pathInRoot(it).contains("/routes/") }
    }

    private companion object {
        /** Kotlin files under the scanned roots after the ecr data move (stage B2-2): 945 files, floor 940. */
        const val MINIMUM_SCANNED_FILES = 940
    }

    @Test
    fun `no route file gates on a role or a retired permission`() {
        val files = routeFiles()
        assertTrue(files.size > 30, "expected to find the route files; found ${files.size}")

        val offenders =
            files.filterNot { it.name in allowed }.flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val code = line.substringBefore("//")
                        forbidden.any { code.contains(it) }
                    }.map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }

        assertTrue(
            offenders.isEmpty(),
            "Route handlers must not gate on a role. Add a capability rule to RoutePermissions " +
                "instead — the registry is the only authorization seam.\n" + offenders.joinToString("\n"),
        )
    }
}
