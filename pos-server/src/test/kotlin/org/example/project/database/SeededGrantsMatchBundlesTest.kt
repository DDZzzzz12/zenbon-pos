package org.example.project.database

import org.example.project.core.auth.CapabilityBundles
import org.example.project.testutils.PostgresTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.assertEquals

/**
 * The `(group, capability)` grants the migration chain seeds into **real PostgreSQL** must equal
 * what [CapabilityBundles] declares in code.
 *
 * ## The gap this closes
 *
 * There are two seed paths and neither is checked by the compiler: `V85__seed_permission_groups.sql`
 * is static SQL that was generated once and pasted in, and `SchemaManager.initializeDefaults` is
 * ordinary Kotlin. `SeededGroupsMatchBundlesTest` covers the second one — it runs against H2, which
 * never executes a Flyway migration — so until this test the *migration* half was unguarded. A
 * capability added to a bundle but forgotten in V85 reaches an upgraded production database as a
 * silent 403; one added to V85 but not to the bundle reaches it as a silent over-grant, and a fresh
 * H2/`SchemaManager` install would then disagree with an upgraded one.
 *
 * ## Why it replaced `SquashEquivalenceTest`'s grant leg
 *
 * That leg compared the current chain's grants against the frozen pre-squash copy. V85 is now the
 * single final-state seed and the *required* home for every new capability grant, so the seed
 * legitimately evolves and the legacy copy — which is a frozen record of what shipped and must not
 * be edited — can never match it again. Every new grant re-broke it. The property worth asserting is
 * not "the migration still matches what shipped last year" but "the migration matches the code",
 * which is what this test does.
 *
 * ## Why it survives the legacy copy's deletion
 *
 * It never reads `db/migration-legacy`. It runs the real chain from `db/migration` with no upper
 * bound and compares against Kotlin, so deleting the frozen copy at release time cannot affect it.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Seeded grants in PostgreSQL reproduce CapabilityBundles")
class SeededGrantsMatchBundlesTest {
    private lateinit var pg: PostgresTestDatabase

    @BeforeAll
    fun setUpAndMigrate() {
        pg = PostgresTestDatabase.start()
        pg.dataSource.connection.use { MigrationChainTest.seedPreV78State(it) }
        val result =
            Flyway
                .configure()
                .dataSource(pg.dataSource)
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("77")
                .baselineDescription("pre-fiscal base (hand-seeded fiscal tables)")
                .load()
                .migrate()
        check(result.success) { "Flyway migrate() from $MIGRATION_LOCATION failed" }
        // The seed must be seen to execute. A chain that baselined past it would compare an empty
        // grant table against the bundles and fail for the wrong reason, or — worse, if the
        // assertion were ever loosened — pass vacuously.
        val executed = result.migrations.map { it.description }.toSet()
        check(SEED_DESCRIPTION in executed) {
            "$SEED_DESCRIPTION did not execute — the comparison would not be about the seed at all; " +
                "executed=$executed"
        }
    }

    @AfterAll
    fun tearDown() {
        pg.close()
    }

    @Test
    @DisplayName("every seeded (group, capability) pair matches CapabilityBundles, in both directions")
    fun seededGrantsMatchBundles() {
        val inMigration = pg.dataSource.connection.use { readGrants(it) }
        val inCode =
            CapabilityBundles
                .definitions()
                .flatMap { definition -> definition.capabilities.map { definition.code to it.name } }
                .toSet()

        val migrationOnly = (inMigration - inCode).sortedBy { "${it.first}:${it.second}" }
        val codeOnly = (inCode - inMigration).sortedBy { "${it.first}:${it.second}" }

        // Not assertEquals on the two sets: a raw set diff of ~330 pairs buries the handful that
        // actually differ, and the two directions are different bugs with different fixes.
        assertEquals(
            emptyList<Pair<String, String>>() to emptyList<Pair<String, String>>(),
            migrationOnly to codeOnly,
            "the V85 seed and CapabilityBundles disagree.\n" +
                "  in the migration but NOT in the code (over-grant; either add it to the bundle " +
                "or remove the INSERT and decrement that group's count in V85's DO \$\$ block): " +
                "${describe(migrationOnly)}\n" +
                "  in the code but NOT in the migration (silent 403 on every upgraded database; " +
                "add the grant to V85 and increment that group's count in its DO \$\$ block): " +
                "${describe(codeOnly)}",
        )
    }

    private fun describe(pairs: List<Pair<String, String>>): String =
        if (pairs.isEmpty()) {
            "(none)"
        } else {
            pairs.joinToString(", ") { "${it.first}:${it.second}" }
        }

    /** Every (group code, capability) pair the migrated database holds. */
    private fun readGrants(conn: Connection): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        conn.createStatement().use { st ->
            st
                .executeQuery(
                    """
                    SELECT g.code, c.capability
                    FROM permission_group g
                    JOIN permission_group_capability c ON c.group_id = g.id
                    ORDER BY g.code, c.capability
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) out += rs.getString(1) to rs.getString(2)
                }
        }
        check(out.isNotEmpty()) { "no permission grants found — the comparison would be vacuous" }
        return out
    }

    private companion object {
        const val MIGRATION_LOCATION = "classpath:db/migration"

        /** Matched by description, not by version number, so renumbering the seed cannot rot it. */
        const val SEED_DESCRIPTION = "seed permission groups"
    }
}
