package org.example.project.database

import org.example.project.testutils.PostgresTestDatabase
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The business-partner migration's no-backfill guard: it must fire when a role table holds rows,
 * and it must not fire when they are empty.
 *
 * ## Why this test exists
 *
 * The business-partner migration adds `NOT NULL` foreign keys to `supplier`, `debtor` and
 * `supplier_bank_account`. Those `ALTER`s can only succeed on empty tables, and the migration
 * ships **no backfill** on purpose — nothing is deployed, so the schema is created in its final
 * shape and a developer with seeded data re-creates the database instead of migrating it.
 *
 * The `DO $$ … END $$;` block at the top of that migration is the only thing standing between such
 * a developer and an unreadable `column "partner_id" contains null values`. Until this test, it had
 * **never been executed**: the H2 suite builds the schema from the Exposed objects and never runs
 * the SQL, a fresh install baselines Flyway rather than migrating, and every other PostgreSQL
 * migration test migrates an empty database — where the guard is a silent no-op. A typo in it, or a
 * reference to a column that no longer exists, would have gone unnoticed until it hit a colleague.
 *
 * ## What each case proves
 *
 * - [supplierRowsAbortTheMigrationWithAnActionableMessage], [debtorRowsAlsoAbortTheMigration] and
 *   [bankAccountRowsAlsoAbortTheMigration] drive the chain to the version immediately below the
 *   guard, plant a single row, and let the guard run for real. All three assert on the **text**:
 *   naming the tables and telling the reader to re-create the database is the entire value of the
 *   guard, and a test that only asserted "the migration failed" would pass just as happily on an
 *   unrelated failure — which is precisely the outcome worth catching.
 * - [anEmptyDatabaseMigratesStraightThrough] is what stops a future failure from being "fixed" by
 *   deleting the guard.
 *
 * ## Why `supplier_bank_account` now has a case of its own
 *
 * It could not have one before. `supplier_bank_account.supplier_id` was `NOT NULL` with `ON DELETE
 * CASCADE`, so a bank-account row could not exist without a supplier row and any database that
 * would trip the third branch tripped the first one too. Folding the identity drops back out of
 * V81 removed that column — the account hangs off `partner_id`, which the guarded migration is
 * about to add — so at this point in the chain a bank account stands on its own and the third
 * branch is independently reachable. [bankAccountRowsAlsoAbortTheMigration] reaches it.
 *
 * Modelled on [MigrationChainTest] — same zonky harness, same hand-seeded pre-V78 base, same
 * baseline — and, like [V110LegacyGrantGuardTest], it finds its migration by **what the script
 * does** rather than by the version number it currently carries. Every migration identifier written
 * as a literal on this branch has rotted at least once.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("business_partner no-backfill guard — real PostgreSQL (zonky)")
class BusinessPartnerBackfillGuardTest {
    private val migrationLocation = "classpath:db/migration"

    private lateinit var pg: PostgresTestDatabase

    /** The version carrying the guard — resolved from disk by content, see [resolveGuardVersion]. */
    private lateinit var guardVersion: MigrationVersion

    /** The highest version strictly below [guardVersion]: where a seeded database gets planted. */
    private lateinit var versionBelowGuard: MigrationVersion

    /** The top of the chain, so the "empty migrates through" case really reaches the end. */
    private lateinit var maxVersion: MigrationVersion

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
        val onDisk = onDiskMigrations()
        guardVersion = resolveGuardVersion(onDisk)
        versionBelowGuard =
            checkNotNull(onDisk.keys.filter { it < guardVersion }.maxOrNull()) {
                "no migration below the guard at V${guardVersion.version} — the chain cannot be stopped short of it"
            }
        maxVersion = onDisk.keys.max()
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    @DisplayName("A single supplier row aborts the chain, and the abort says what to do about it")
    fun supplierRowsAbortTheMigrationWithAnActionableMessage() {
        migrateToVersionBelowGuard()
        pg.dataSource.connection.use { c ->
            c.createStatement().use { st ->
                // Only the columns that exist at THIS point in the chain. The creditor role
                // never carried identity of its own once the drops were folded back out of V81:
                // `name`/`normalized_name` are declared on `business_partner`, which the guarded
                // migration is about to create. `created_at` is the one NOT NULL without a default.
                st.execute(
                    "INSERT INTO supplier (created_at) VALUES (TIMESTAMP '2026-01-01 09:00:00')",
                )
            }
        }

        assertGuardAborts()
    }

    @Test
    @DisplayName("A single debtor row aborts it too — the second branch is not decoration")
    fun debtorRowsAlsoAbortTheMigration() {
        migrateToVersionBelowGuard()
        pg.dataSource.connection.use { c ->
            c.createStatement().use { st ->
                // `debtor.shop_id` is a NOT NULL reference to shop_info, whose single row comes from
                // the hand-seeded pre-V78 base. No `name` here either — see the supplier case.
                st.execute(
                    "INSERT INTO debtor (shop_id, created_at) " +
                        "SELECT shop_id, TIMESTAMP '2026-01-01 09:00:00' FROM shop_info LIMIT 1",
                )
            }
        }

        assertGuardAborts()
    }

    @Test
    @DisplayName("A bank-account row aborts it too — the third branch is independently reachable")
    fun bankAccountRowsAlsoAbortTheMigration() {
        migrateToVersionBelowGuard()
        pg.dataSource.connection.use { c ->
            c.createStatement().use { st ->
                // `iban` is the only NOT NULL without a default at this point in the chain: the
                // creditor-role link is gone and `partner_id` does not exist yet.
                st.execute("INSERT INTO supplier_bank_account (iban) VALUES ('DE02120300000000202051')")
            }
        }

        assertGuardAborts()
    }

    @Test
    @DisplayName("With the role tables empty the guard is a no-op and the whole chain applies")
    fun anEmptyDatabaseMigratesStraightThrough() {
        resetAndSeed()
        val result = flyway(maxVersion).migrate()

        assertTrue(result.success, "Flyway migrate() must succeed on a database with no role rows")
        val executed = result.migrations.mapNotNull { it.version }.toSet()
        // Without this the case would be vacuous: a guard that was skipped rather than run proves
        // nothing about whether it fires on empty tables.
        assertTrue(
            guardVersion.version in executed,
            "V${guardVersion.version} must have EXECUTED, not been skipped; executed=$executed",
        )
        assertEquals(
            maxVersion.version,
            result.targetSchemaVersion,
            "the chain must reach the highest migration on disk",
        )
    }

    /**
     * Runs the rest of the chain and asserts the guard stops it with its own message.
     *
     * Flyway wraps the driver's exception, which wraps PostgreSQL's `RAISE`, so the whole cause
     * chain is flattened before matching. The three assertions are the three things the message has
     * to carry to be worth having: what happened, which tables are in the way, and what to do.
     */
    private fun assertGuardAborts() {
        val failure = assertFailsWith<Exception> { flyway(maxVersion).migrate() }
        val message = flattenedMessages(failure)

        assertContains(message, "business_partner ships no backfill", message = "the abort must say why it aborted: $message")
        assertContains(
            message,
            "supplier/debtor/supplier_bank_account must be empty",
            message = "the abort must name the tables the developer has to look at: $message",
        )
        assertContains(
            message,
            "re-create this database rather than migrating it",
            message = "the abort must state the remedy — that is the whole point of raising instead of failing on a constraint: $message",
        )
    }

    /** Every message in the cause chain, joined. Self-referencing causes terminate the walk. */
    private fun flattenedMessages(t: Throwable): String =
        generateSequence(t as Throwable?) { current -> current.cause?.takeIf { it !== current } }
            .mapNotNull { it.message }
            .joinToString("\n")

    /** A database freshly built by the chain, stopped one migration short of the guard. */
    private fun migrateToVersionBelowGuard() {
        resetAndSeed()
        val result = flyway(versionBelowGuard).migrate()
        check(result.success) { "could not reach V${versionBelowGuard.version}" }
        check(guardVersion.version !in result.migrations.mapNotNull { it.version }) {
            "the guard already ran while stopping short of it — the target is wrong"
        }
    }

    /**
     * Wipes the database back to nothing and re-materialises the pre-V78 base.
     *
     * One embedded PostgreSQL serves the whole class (starting one costs seconds), so each case
     * clears `public` — Flyway's own history table included — rather than sharing a database with
     * an aborted migration recorded in it.
     */
    private fun resetAndSeed() {
        pg.dataSource.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute("DROP SCHEMA public CASCADE")
                st.execute("CREATE SCHEMA public")
            }
        }
        pg.dataSource.connection.use { MigrationChainTest.seedPreV78State(it) }
    }

    /** The same Flyway wiring [MigrationChainTest] uses, targeted at [target]. */
    private fun flyway(target: MigrationVersion): Flyway =
        Flyway
            .configure()
            .dataSource(pg.dataSource)
            .locations(migrationLocation)
            .baselineOnMigrate(true)
            .baselineVersion("77")
            .baselineDescription("pre-fiscal base (hand-seeded fiscal tables)")
            .target(target)
            .validateMigrationNaming(true)
            .load()

    /**
     * The version whose script carries the guard, found by the sentence the guard raises.
     *
     * Not by filename: both halves of a Flyway name move — a squash rewrites the description, a
     * rename pass rewrites the number — and a pinned identifier here would fail on bookkeeping
     * rather than on a defect. Exactly one script must match; zero means the guard was deleted or
     * reworded (in which case the assertions below are stale and must be revisited), more than one
     * means it was duplicated.
     */
    private fun resolveGuardVersion(onDisk: Map<MigrationVersion, String>): MigrationVersion {
        val carriers = onDisk.filterValues { GUARD_SENTINEL in it }.keys
        assertEquals(
            1,
            carriers.size,
            "exactly one migration must carry the no-backfill guard (a script containing " +
                "\"$GUARD_SENTINEL\"); found ${carriers.map { "V${it.version}" }}",
        )
        return carriers.single()
    }

    /** Every versioned migration on the test classpath, keyed by version. */
    private fun onDiskMigrations(): Map<MigrationVersion, String> {
        val dir =
            checkNotNull(javaClass.classLoader.getResource("db/migration")) {
                "db/migration not on the test classpath"
            }
        val scripts =
            checkNotNull(File(dir.toURI()).listFiles { f: File -> f.name.startsWith("V") && f.name.endsWith(".sql") }) {
                "db/migration is not a readable directory"
            }
        val byVersion =
            scripts.associate { file ->
                val version =
                    checkNotNull(VERSION_PREFIX.find(file.name)?.groupValues?.get(1)) {
                        "cannot read a version out of ${file.name}"
                    }
                MigrationVersion.fromVersion(version) to file.readText()
            }
        check(byVersion.isNotEmpty()) { "no versioned migrations at $migrationLocation" }
        return byVersion
    }

    private companion object {
        /** The opening words of the guard's `RAISE EXCEPTION`, which identify it uniquely. */
        const val GUARD_SENTINEL = "business_partner ships no backfill"

        val VERSION_PREFIX = Regex("^V(\\d+(?:\\.\\d+)*)__")
    }
}
