package org.example.project.infrastructure.security

import org.example.project.testutils.SourceRoots
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * No service or repository may decide **access or data scope** from a role.
 *
 * The route layer has `NoRoleGatesInRoutesTest`. This is the layer below it, and it is where the
 * surviving defects actually were: `resolveTargetEmployee` chose between "the whole shop" and "your
 * own rows" from `role.isManagerOrHigher()`, and it was found because somebody asked about the
 * reports screen — not because anything caught it. Five more of the same shape were sitting beside
 * it, in vouchers, time tracking, statistics and the staff directory.
 *
 * Role and capability legitimately disagree since the purpose-built groups landed, so a role check
 * here does not merely duplicate the route gate — it **contradicts** it. An AUDITOR holds
 * `EMPLOYEE_READ` but may carry role `CASHIER`, so the staff directory answered them with an empty
 * list; an auditor's own sales figures are the empty set, so scoping reports to "their own rows"
 * made the group useless.
 *
 * ### Scope: the three access-shaped predicates, not every mention of a role
 *
 * `isManagerOrHigher()`, `isManagerOrAdmin(...)` and `isCashier()` all ask one question — *is this
 * person senior enough* — which is precisely the question capabilities replaced.
 *
 * A bare `EmployeeRole.ADMIN` comparison is deliberately NOT flagged. Several are real business
 * rules rather than access: `BillRepositoryImpl` books an admin's tips to the house because the
 * admin IS the owner, `InitialSetupServiceImpl` looks for an admin to decide whether setup has run.
 * Flagging those would produce an allowlist longer than the findings, which is how a guard stops
 * being read.
 */
@DisplayName("No role scoping in services")
class NoRoleScopingInServicesTest {
    private val forbidden = listOf("isManagerOrHigher()", "isManagerOrAdmin(", "isCashier()")

    /**
     * Files that use one of these predicates for something other than access or scoping.
     *
     * Each says which — and "it has always been like that" is not one of the reasons.
     *
     * Deliberately short. Three more entries were dropped once [readsRole] learned to skip block
     * comments: EmployeeFilterResolver, TemporarySessionServiceImpl and OrderServiceImpl mention
     * these predicates only in prose EXPLAINING the checks they no longer have, and an allowlist
     * entry for a file that no longer reads a role protects nothing.
     */
    private val allowed =
        mapOf(
            "EmployeeServiceImpl.kt" to "builds the attribution PICKER list; the directory hierarchy is a staff-privacy rule",
        )

    /**
     * Whether this line of CODE uses one of the predicates — comments of either kind excluded.
     *
     * Both tests ask through here. The offender scan originally stripped only `//`, so the block
     * comment left behind where a role check USED to be counted as a role check — the guard's first
     * run failed on prose explaining a deletion. The client's twin test had the same blind spot in
     * the mirror image (its staleness scan read raw text), which is a good reason to have exactly
     * one definition of the question.
     */
    private fun readsRole(line: String): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return false
        return forbidden.any { line.substringBefore("//").contains(it) }
    }

    /**
     * `pos-server` with the extracted server and data modules (`platform/server`, `platform/data`, ...), plus the
     * listed area modules. Every root is mandatory: a renamed module fails here rather than leaving the
     * guard to scan less than it says.
     */
    private fun scannedFiles(): List<File> {
        val roots =
            SourceRoots.serverMainRoots() +
                SourceRoots.platformDataMainRoots() +
                SourceRoots.moduleMainRoots(
                    "platform/server-common",
                    "ecr/voucher",
                    "backoffice/timetracking",
                    "accounting/document",
                    "ecr/customer",
                    "platform/audit",
                    "ecr/printer",
                    "ecr/reservation",
                )
        return SourceRoots.kotlinFiles(roots).also {
            SourceRoots.requireScanned("NoRoleScopingInServicesTest", it.size, MINIMUM_SCANNED_FILES)
        }
    }

    private fun serviceFiles(): List<File> =
        scannedFiles().filter { f ->
            val path = "/" + SourceRoots.pathInRoot(f)
            listOf("/data/service/", "/data/repository/", "/domain/service/", "/utils/").any { it in path }
        }

    private companion object {
        /** Kotlin files under the scanned roots on 2026-10-27, before the platform extraction moved any. */
        const val MINIMUM_SCANNED_FILES = 1370
    }

    @Test
    fun `no service or repository scopes data by role`() {
        val files = serviceFiles()
        assertTrue(files.size > 40, "expected to find the service sources; found ${files.size}")

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
            "Services must scope on capabilities, not roles. Ask for the capability the feature " +
                "names — REPORT_ALL_READ, TIMETRACKING_ALL_READ, EMPLOYEE_READ — and pass it down " +
                "as a parameter rather than re-deriving it from the employee row.\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the allowlist has no stale entries`() {
        val names = serviceFiles().associateBy { it.name }
        val stale =
            allowed.keys.filter { name ->
                val file = names[name] ?: return@filter true
                file.readLines().none { readsRole(it) }
            }
        assertTrue(stale.isEmpty(), "allowlisted files that no longer mention a role predicate: $stale")
    }
}
