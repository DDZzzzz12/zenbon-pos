package org.example.project.ui.navigation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Only [rememberVisibleDestinations] may enumerate the destination list.
 *
 * `PosNavDestinationAccessTest` already proves `isAccessibleTo` gives the right verdict — including
 * `anAccountantKeepsTheLedgerAndLosesTheFloor`, which asserts an ACCOUNTANT loses RESERVATIONS. That
 * test passed while the till nevertheless showed the destination, because **the rail never called
 * the gate**: `PosNavRail`, `PosBottomBar` and the since-deleted `PosNavSidebar` each walked
 * `PosNavDestination.entries.filter { it.group == NavGroup.PRIMARY }` directly, so only the
 * MANAGEMENT group (which did go through `rememberVisibleDestinations`) was ever filtered by
 * capability.
 *
 * The result was a live client/server disagreement: an ACCOUNTANT — who does not hold
 * `RESERVATION_READ` — was offered Reservierungen on the till, and `GET /api/reservations` answers
 * 403 for that group. Found by driving the wasm build in a browser; no unit test could see it,
 * because the defect was a *missing call*, not a wrong answer.
 *
 * Scanning source text is deliberate, and matches [NoRoleGatesInScreensTest]: a missing call is a
 * `String`-level absence, and there is nothing to reflect on until the UI is composed.
 */
class NavContainersGateDestinationsTest {
    /** The one file allowed to enumerate — it is the gate itself. */
    private val gateFile = "PosNavDestinationFilter.kt"

    private fun commonMainFiles(): List<java.io.File> =
        java.io
            .File("src/commonMain/kotlin/org/example/project")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `no UI enumerates PosNavDestination entries outside the gate`() {
        val files = commonMainFiles()
        assertTrue(files.isNotEmpty(), "Scanned no sources — run this through Gradle, whose CWD is the project dir")

        val offenders =
            files
                .filter { it.name != gateFile }
                .filter { file ->
                    file.readLines().any { line ->
                        line.substringBefore("//").contains("PosNavDestination.entries")
                    }
                }.map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "These render destinations without the capability gate. Use " +
                "rememberVisibleDestinations(group) instead of PosNavDestination.entries:\n" +
                offenders.joinToString("\n"),
        )
    }

    /**
     * Hiding a menu entry is not gating a destination — the screen must refuse too.
     *
     * The rail fix above stops a principal being *offered* Reservierungen, but the destination
     * stayed reachable by every other door: the browser URL on the wasm build, a stale back-stack
     * entry, and — the one that matters most — a mid-session capability change, which leaves a user
     * already sitting on the screen exactly where they were. MainScreen's eviction effect covers
     * only NavGroup.MANAGEMENT, so PRIMARY and UTILITY destinations had nothing.
     *
     * `MainNotifications`/`MainReservations`/`AppSettings` are asserted by name rather than derived
     * from `PosNavDestination`, because the mapping from a route class to its destination is the
     * `currentRoute.contains("…")` string match in MainScreen — there is no typed link to follow.
     */
    @Test
    fun `every MainNavigation destination with a capability is gated`() {
        val file =
            commonMainFiles().firstOrNull { it.name == "MainNavigation.kt" }
                ?: throw AssertionError("MainNavigation.kt not found")
        val lines = file.readLines()
        val declaration = Regex("""composable<(\w+)>""")
        // Dashboard is PosNavDestination.DASHBOARD, which declares no capability: every signed-in
        // principal needs somewhere to land. PosNavDestinationAccessTest pins it as the only one.
        val openByDesign = setOf("Dashboard")

        val declarations =
            lines.withIndex().mapNotNull { (index, line) ->
                declaration.find(line.substringBefore("//"))?.let { index to it.groupValues[1] }
            }

        // A destination's block runs to the next declaration, so the gate is looked for in exactly
        // the lines that render it — a fixed-size window would miss a gate that sits below a long
        // BackHandler preamble, which is where several of them are.
        val ungated =
            declarations
                .filter { (start, route) ->
                    if (route in openByDesign) return@filter false
                    val next = declarations.firstOrNull { it.first > start }?.first ?: lines.size
                    lines.subList(start, next).none { "RequireCapability(" in it }
                }.map { (index, route) -> "$route (line ${index + 1})" }

        assertTrue(
            ungated.isEmpty(),
            "MainNavigation destinations that render without asking for a capability. Wrap each in " +
                "RequireCapability(Capability.X, onDenied = toDashboard) { … }:\n" + ungated.joinToString("\n"),
        )
    }

    /**
     * The billing screen is gated where it is ENTERED, not by the control that usually opens it.
     *
     * `PosTableActionGrid`'s "create bill" action asks for `BILL_WRITE`, and that was taken as
     * covering the pay/split/tender screen behind it — "reachable only through create bill". It is
     * not. `DashboardNavHost` navigates to `TableBilling` straight from a `"billing_request"`
     * notification, and `RTableServiceImpl` addresses that notification to the table session's
     * OWNER employee. An unattended order station may open a FREE table (it holds `TABLE_WRITE` —
     * it must, to book onto one), so the owner is the shared terminal employee: a waiter tapping
     * "Rechnung anfordern" dropped the station into the full payment column with no gate anywhere
     * on the path. The server still refused every call, which is what kept it a UI-half violation
     * of rule 8 rather than a breach — and rule 8 exists precisely so that is never the last line.
     *
     * Pinned as a pair (route, capability) rather than derived: a nested route class has no typed
     * link to the capability its screen spends, and inventing one to satisfy a test would put the
     * answer in the same place as the question.
     */
    @Test
    fun `the billing destination gates itself, not the action that usually reaches it`() {
        val required = mapOf("TableBilling" to "BILL_WRITE")
        val file =
            commonMainFiles().firstOrNull { it.name == "DashboardNavigation.kt" }
                ?: throw AssertionError("DashboardNavigation.kt not found")
        val lines = file.readLines()
        val declaration = Regex("""composable<(\w+)>""")

        val declarations =
            lines.withIndex().mapNotNull { (index, line) ->
                declaration.find(line.substringBefore("//"))?.let { index to it.groupValues[1] }
            }
        assertTrue(
            declarations.map { it.second }.containsAll(required.keys),
            "DashboardNavigation no longer declares ${required.keys} — has the host been restructured?",
        )

        val ungated =
            declarations
                .mapNotNull { (start, route) ->
                    val capability = required[route] ?: return@mapNotNull null
                    val next = declarations.firstOrNull { it.first > start }?.first ?: lines.size
                    val body = lines.subList(start, next)
                    val gate = "RequireCapability(Capability.$capability"
                    if (body.any { gate in it.substringBefore("//") }) null else "$route needs $gate"
                }

        assertTrue(
            ungated.isEmpty(),
            "A dashboard destination renders without asking for the capability its screen spends. " +
                "Gating the control that opens it is not enough — a notification opens this one " +
                "too:\n" + ungated.joinToString("\n"),
        )
    }

    /**
     * The gate must actually be reachable from the nav containers, or the test above passes
     * vacuously the moment somebody deletes the call sites rather than fixing them.
     *
     * Asked per GROUP, not per file. A container that gated PRIMARY and then hand-rolled its
     * UTILITY items still contains the string `rememberVisibleDestinations(` — which is exactly
     * what the rail and the sidebar did with Settings until 2026-09-06: the item sat behind a
     * hardcoded `if (!isStation)`, so `PosNavDestination.SETTINGS.visibleToTerminal` was asserted
     * by tests while a second, untested copy of the rule was what ran, and `CONFIG_READ` was not
     * asked for at all.
     */
    @Test
    fun `every nav container asks the gate for every group it renders`() {
        val expected =
            mapOf(
                "PosNavRail.kt" to listOf(NavGroup.PRIMARY, NavGroup.UTILITY),
                // The bar's own slots are PRIMARY; its Verwaltung menu also offers Settings, picked
                // from rememberVisibleDestinations(NavGroup.UTILITY) by `settingsEntry` (PhoneBarPlan.kt).
                // MANAGEMENT reaches it as a parameter, already gated by MainScreen.
                "PosBottomBar.kt" to listOf(NavGroup.PRIMARY),
            )
        val byName = commonMainFiles().associateBy { it.name }

        val missing =
            expected.flatMap { (name, groups) ->
                val text = byName[name]?.readText() ?: return@flatMap listOf("$name (file not found)")
                groups
                    .filterNot { text.contains("rememberVisibleDestinations(NavGroup.${it.name})") }
                    .map { "$name does not gate NavGroup.${it.name}" }
            }

        assertTrue(
            missing.isEmpty(),
            "Nav containers that render a group without consulting rememberVisibleDestinations:\n" +
                missing.joinToString("\n"),
        )
    }

    /**
     * A gated group is only gated if no item of it is *also* rendered by hand beside the loop.
     *
     * Naming a UTILITY destination inside a nav container is the shape of the defect above — an
     * item that reaches the screen without passing the filter. MANAGEMENT and PRIMARY constants
     * are still legitimate there. The bar's Settings entry is chosen from the already-gated UTILITY list
     * in PhoneBarPlan.kt, which is why this scan does not cover that file.
     */
    @Test
    fun `no nav container names a utility destination`() {
        val containers = listOf("PosNavRail.kt", "PosBottomBar.kt")
        val utility = PosNavDestination.entries.filter { it.group == NavGroup.UTILITY }
        val byName = commonMainFiles().associateBy { it.name }

        val offenders =
            containers.flatMap { name ->
                val lines = byName[name]?.readLines() ?: return@flatMap listOf("$name (file not found)")
                lines.withIndex().flatMap { (index, line) ->
                    val code = line.substringBefore("//")
                    utility
                        .filter { code.contains("PosNavDestination.${it.name}") }
                        .map { "$name:${index + 1} names PosNavDestination.${it.name}" }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "Utility destinations rendered outside rememberVisibleDestinations(NavGroup.UTILITY):\n" +
                offenders.joinToString("\n"),
        )
    }
}
