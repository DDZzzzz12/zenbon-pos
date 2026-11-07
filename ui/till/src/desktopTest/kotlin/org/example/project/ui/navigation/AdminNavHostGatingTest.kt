package org.example.project.ui.navigation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every adminApp NavHost destination must run its capability gate.
 *
 * `AdminNavItemTest` proves the predicate — which items a principal may see — and its own comment
 * says `GatedDestination` "is the only thing between a URL fragment and an ADMIN-only screen". What
 * nothing checked was whether every destination actually *calls* it. `ZBonDetailRoute` did not: the
 * Z-Bons sidebar entry is gated on `ZBON_MANAGE`, but the detail route it navigates to rendered
 * `ZBonHubScreen` unwrapped, so a URL fragment reached a Z-Bon hub — with its Print and Download-PDF
 * actions — for a principal the server refuses (`/api/z-bon/{id}`, `/{id}/pdf` and `/{id}/print` all
 * require `ZBON_MANAGE`).
 *
 * This is the adminApp twin of [NavContainersGateDestinationsTest], and the same failure mode: a
 * missing *call* is invisible to a test of the function being called.
 *
 * Scanned from composeApp for the reason [NoRoleGatesInScreensTest] gives — adminApp's own tests run
 * in a browser under karma, where `java.io.File` does not exist. `ui/till/build.gradle.kts`
 * registers the adminApp source tree as an input of this Test task, so the scan cannot pass by being
 * UP-TO-DATE after an adminApp-only edit.
 */
class AdminNavHostGatingTest {
    private val adminApp =
        java.io.File(
            "../backoffice/src/wasmJsMain/kotlin/org/example/project/admin/AdminApp.kt",
        )

    /**
     * Destinations that legitimately carry no gate.
     *
     * Only the landing screen. `AdminNavItem.DASHBOARD` declares `requiredCapability == null`
     * because every signed-in principal must have somewhere to land — and
     * `AdminNavItemTest.everyItemNamesACapabilityOrIsDeliberatelyOpen` asserts it is the *only* item
     * that does, so this entry cannot quietly grow to cover a destination that should be gated.
     */
    private val ungatedByDesign = setOf("DashboardRoute")

    @Test
    fun `every NavHost destination is wrapped in GatedDestination`() {
        assertTrue(
            adminApp.isFile,
            "Could not find AdminApp.kt at ${adminApp.path} — run this through Gradle, whose CWD is the project dir",
        )
        val lines = adminApp.readLines()
        val declaration = Regex("""composable<(\w+)>""")

        val ungated =
            lines
                .withIndex()
                .mapNotNull { (index, line) ->
                    declaration.find(line.substringBefore("//"))?.let { index to it.groupValues[1] }
                }.filterNot { (_, route) -> route in ungatedByDesign }
                // The gate opens the destination's body, so it sits within a few lines of the
                // declaration — every existing call site puts it first or just after the
                // `toRoute()`/`koinInject()` preamble.
                .filterNot { (index, _) ->
                    lines.subList(index, minOf(index + 14, lines.size)).any { "GatedDestination(" in it }
                }.map { (index, route) -> "$route (line ${index + 1})" }

        assertTrue(
            ungated.isEmpty(),
            "adminApp destinations reachable by URL fragment with no capability gate. Wrap each in " +
                "GatedDestination(AdminNavItem.X, tokenStore, onDenied) { … }:\n" + ungated.joinToString("\n"),
        )
    }
}
