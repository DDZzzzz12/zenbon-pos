package org.example.project.infrastructure.security

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Structural guards the equivalence test provably cannot provide.
 *
 * `CapabilityEquivalenceTest` proves the migration changed no verdict. It cannot prove the
 * assignments are *right*: give `GET /api/customers` `CATALOG_READ` instead of `CUSTOMER_READ` and,
 * because both sit in the same bundles, every verdict is unchanged and it passes. The error only
 * surfaces in Stage 3, when `ACCOUNTANT` receives `CATALOG_READ` and silently inherits the customer
 * database.
 *
 * These checks close that gap by holding each module to its own declarations.
 */
@DisplayName("Rule modules are internally and mutually consistent")
class RuleModuleConsistencyTest {
    private val modules = RoutePermissions.modules

    /** Prefix ownership is segment-aware: `/api/employee` must not be read as owning `/api/employees`. */
    private fun covers(
        prefix: String,
        path: String,
    ): Boolean = path == prefix || path.startsWith("$prefix/")

    @Test
    fun `every rule path is inside one of its own module's prefixes`() {
        val bad =
            modules.flatMap { m ->
                m.rules.keys
                    .filterNot { (path, _) -> m.pathPrefixes.any { covers(it, path) } }
                    .map { (path, method) -> "${m.name}: ${method.value} $path" }
            }
        assertTrue(bad.isEmpty(), "Rules outside their module's declared prefixes:\n${bad.joinToString("\n")}")
    }

    @Test
    fun `every capability a rule requires is declared by its own module`() {
        val bad =
            modules.flatMap { m ->
                m.rules.entries
                    .flatMap { (key, access) -> access.requiredCapabilities.map { key to it } }
                    .filterNot { (_, cap) -> cap in m.allowedCapabilities }
                    .map { (key, cap) -> "${m.name}: ${key.second.value} ${key.first} requires undeclared $cap" }
            }
        assertTrue(bad.isEmpty(), "Undeclared capabilities:\n${bad.joinToString("\n")}")
    }

    @Test
    fun `no module declares a capability none of its rules uses`() {
        // A stale entry here weakens the check above without anyone noticing.
        val bad =
            modules.mapNotNull { m ->
                val used =
                    m.rules.values
                        .flatMap { it.requiredCapabilities }
                        .toSet()
                val unused = m.allowedCapabilities - used
                if (unused.isEmpty()) null else "${m.name}: declares unused $unused"
            }
        assertTrue(bad.isEmpty(), "Stale allowedCapabilities:\n${bad.joinToString("\n")}")
    }

    @Test
    fun `module names are unique`() {
        // `name` is a free-text label, and the overlap check below now pairs modules by index so a
        // duplicate cannot hide a clash — but a duplicate would still make every failure message
        // ambiguous, so reject it outright.
        val dupes = modules.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "Duplicate module names: $dupes")
    }

    @Test
    fun `no two modules claim overlapping prefixes`() {
        // Pairs by INDEX, not by name. An earlier version skipped with `if (a.name >= b.name)`,
        // which also skipped two DISTINCT modules that happened to share a name — hiding exactly
        // the ambiguity this test exists to catch.
        val clashes = mutableListOf<String>()
        for (i in modules.indices) {
            for (j in i + 1 until modules.size) {
                val a = modules[i]
                val b = modules[j]
                for (pa in a.pathPrefixes) {
                    for (pb in b.pathPrefixes) {
                        if (covers(pa, pb) || covers(pb, pa)) {
                            clashes += "${a.name} '$pa' overlaps ${b.name} '$pb'"
                        }
                    }
                }
            }
        }
        assertTrue(clashes.isEmpty(), "Overlapping prefixes — ownership is ambiguous:\n${clashes.joinToString("\n")}")
    }

    @Test
    fun `every rule is claimed by exactly one module`() {
        val counts = mutableMapOf<Pair<String, io.ktor.http.HttpMethod>, MutableList<String>>()
        modules.forEach { m -> m.rules.keys.forEach { counts.getOrPut(it) { mutableListOf() } += m.name } }
        val dupes = counts.filterValues { it.size > 1 }.map { (key, names) -> "${key.second.value} ${key.first}: $names" }
        assertTrue(dupes.isEmpty(), "Rules claimed by more than one module:\n${dupes.joinToString("\n")}")
    }
}
