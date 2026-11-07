package org.example.project.ui.navigation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No client UI — composeApp or adminApp — may gate on a ROLE.
 *
 * The client twin of the server's `NoRoleGatesInRoutesTest`. Both exist because the same cleanup was
 * done once before and grew back: the server accumulated 26 role gates after its capability
 * migration, ten of which checked nothing at all.
 *
 * The client failure mode is different and quieter. Role and capability legitimately disagree since
 * the purpose-built groups landed — an AUDITOR holds `CASHBOOK_READ` but may carry role `CASHIER` —
 * so a role gate does not merely duplicate the capability gate, it CONTRADICTS it. That produced a
 * real defect: the menu offered Financial Docs on the capability while the destination gated it on
 * the role, and arriving there rendered a blank screen.
 *
 * Scanning source text rather than reflecting on composables is deliberate. The failure mode is a
 * `String`-level pattern — somebody typed a role comparison into a screen — and there is nothing to
 * reflect on until the UI is composed.
 *
 * **The allowlist is the interesting part.** Every entry is a place where a role answers a question
 * a capability cannot, and each says which.
 */
class NoRoleGatesInScreensTest {
    private val forbidden =
        listOf(
            "isManagerOrHigher()",
            "isManagerOrAdmin(",
            "hasRole(",
        )

    /**
     * Files where a role is read for something other than access.
     *
     * Two legitimate reasons, and no third:
     *  - **Station vs person** is no longer a role question. "Is this an unattended station" is
     *    `TokenStore.stationRole != null` — the client mirror of the server's `station` JWT claim —
     *    and everything a station may not DO is withheld by its capability mask. No file needs a
     *    role for it any more.
     *  - **Display only.** Rendering a role's NAME, or recording an approver's role for the
     *    degraded path, is not branching on one.
     *
     * The reports screen used to be the third reason — "layout variant, not access". It no longer
     * is: REPORT_ALL_READ named the thing that actually separated a cashier's view from a manager's,
     * so all nine of its controls now ask the same question the server scopes on.
     */
    private val allowed =
        mapOf(
            // Display only: renders a role, never branches on one.
            "AdminUserMenu.kt" to "prints the signed-in principal's role",
            "EmployeeProfileSection.kt" to "prints the employee's role",
            "EmployeeDetailHeader.kt" to "prints the employee's role",
            "CashierProfileView.kt" to "prints the employee's role name (localizedRoleName)",
            "EmployeeEditorDialog.kt" to "the role PICKER — offering EmployeeRole.entries is the point",
            // Records the approving principal's role for the degraded path and for display. Not a
            // gate — see PermissionManager.setTemporaryRole.
            "TemporarySessionManager.kt" to "parses the approver's role for setTemporaryRole",
            // Builds the principal the SERVER already minted, so PermissionManager reads back what
            // the station JWT says. A constructor argument, not a branch.
            "LaunchCoordinator.kt" to "names the role in the station's own EmployeeInfo",
        )

    /*
     * `core/domain/rules/` (TableAccessRuleEngine) is scanned as well since the order-station shell
     * (2026-09-06) converted its two TERMINAL_DEVICE branches. `core/auth/` is still out: that is the
     * PermissionManager which OWNS the role, and reading it there is not a gate.
     */

    /**
     * Whether this line of code reads a role — comments excluded.
     *
     * Shared by both tests on purpose. They used to ask it differently: the offender scan stripped
     * `//` and the staleness scan did not, so a file whose only remaining mention was a comment
     * EXPLAINING the role check it no longer has still counted as reading one. `PosReportsScreen`
     * hit exactly that after its nine gates moved to REPORT_ALL_READ, and the allowlist would have
     * kept an entry that no longer protected anything.
     */
    private fun readsRole(line: String): Boolean {
        val code = line.substringBefore("//")
        return forbidden.any { code.contains(it) } || Regex("""\bEmployeeRole\.\w+""").containsMatchIn(code)
    }

    /**
     * Both clients' UI sources.
     *
     * adminApp is scanned from HERE rather than from its own module because its tests run in a
     * browser under karma, where there is no filesystem to walk — `java.io.File` does not exist on
     * wasm. composeApp's desktop test is JVM, so it can reach across, the same way the server's
     * `NoRoleGatesInRoutesTest` reaches into `../ecr/reservation`.
     *
     * adminApp had no role gates when this was written — 67 capability call sites and every
     * `EmployeeRole` use a display one. The scan exists so that stays true.
     */
    private fun uiFiles(): List<java.io.File> =
        listOf(
            // composeApp: screens and feature UI.
            java.io.File("src/commonMain/kotlin/org/example/project") to listOf("/ui/", "/features/", "/core/domain/rules/"),
            // adminApp: feature UI, plus `core/presentation` — which is where AdminNavItem declares
            // each destination's capability and where the sidebar decides what to show. A role check
            // there would gate as surely as one in a screen.
            java.io.File("../backoffice/src/wasmJsMain/kotlin/org/example/project") to
                listOf("/features/", "/core/presentation/"),
        ).filter { (root, _) -> root.isDirectory }
            .flatMap { (root, subpaths) ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { f -> subpaths.any { it in f.path.replace('\\', '/') } }
                    .toList()
            }

    @Test
    fun `no screen gates on a role`() {
        val files = uiFiles()
        assertTrue(files.size > 100, "expected to find BOTH clients' UI sources; found ${files.size}")

        val offenders =
            files
                .filterNot { it.name in allowed }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> readsRole(line) }
                        .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
                }

        assertTrue(
            offenders.isEmpty(),
            "UI must gate on capabilities, not roles — use holds(), CapabilityGate or " +
                "RequireCapability. If the role answers something a capability cannot (station vs " +
                "person, or a layout variant), add the file to `allowed` with the reason.\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the allowlist has no stale entries`() {
        // An allowlist that outlives its reason is how the next sweep talks itself into leaving a
        // real gate alone. If a file no longer reads a role, it does not belong here.
        val names = uiFiles().associateBy { it.name }
        val stale =
            allowed.keys.filter { name ->
                val file = names[name] ?: return@filter true
                file.readLines().none { readsRole(it) }
            }
        assertTrue(stale.isEmpty(), "allowlisted files that no longer read a role: $stale")
    }
}
