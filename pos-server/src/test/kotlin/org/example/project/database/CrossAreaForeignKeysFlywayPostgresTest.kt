package org.example.project.database

import org.example.project.features.backup.data.service.RestoreStaging
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.SchemaDump
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/**
 * The Flyway leg of the two cross-area foreign keys ([CrossAreaForeignKeys]).
 *
 * Exposed no longer declares `bill.clears_sales_invoice_id` or `kassenbuch_header.linked_document_id`
 * as references, so [SchemaFlywayParityTest]'s "Exposed has nothing to add" check no longer covers
 * them, and [FreshInstallForeignKeySnapshotPostgresTest] only covers a fresh install. This test covers
 * an UPGRADED install: the frozen v1.0.10 schema (`db/restore-base/base_schema_v76.sql`, the same base
 * [FreshInstallIndexParityTest] and the restore use) plus the real V77..Vmax Flyway chain.
 * [SchemaFlywayParityTest]'s own setup cannot serve: it baselines at the last release and hand-seeds
 * `kassenbuch_header`/`bill` as stubs without the two columns.
 *
 * Where each key comes from on that path: the bill key is created by V81; the Kassenbuch key is NOT
 * created by any V-migration — it is in the v76 base, which is a dump of a schema Exposed built while
 * the column was still a `reference`. Either way the migrated schema carries both, under the fresh
 * install's names and rules, so [CrossAreaForeignKeys.addMissing] must be a no-op at boot.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cross-area foreign keys on a V76→Vmax Flyway-upgraded install (real PostgreSQL)")
class CrossAreaForeignKeysFlywayPostgresTest {
    private lateinit var pg: PostgresTestDatabase
    private lateinit var db: Database

    @BeforeAll
    fun upgrade() {
        pg = PostgresTestDatabase.start()
        pg.dataSource.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { it.execute(resource(BASE_SCHEMA)) }
        }
        val migrated = FlywayConfig.migrateFromBaseline(pg.dataSource, RestoreStaging.BASE_VERSION.toString())
        check(migrated.success && migrated.migrationsExecuted > 0) { "the V77..Vmax chain did not run on the v76 base" }
        db = Database.connect(pg.dataSource)
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    @DisplayName("the migrated schema carries both keys as a fresh install does, and addMissing() changes nothing")
    fun addMissingIsANoOpAfterFlyway() {
        val before = foreignKeys()
        val expected = freshInstallLines()
        assertEquals(CrossAreaForeignKeys.all.size, expected.size, "the fresh-install snapshot must pin every cross-area key")
        assertEquals(expected, crossArea(before), "the Flyway-migrated schema must carry both cross-area keys as a fresh install does")

        transaction(db) { CrossAreaForeignKeys.addMissing() }

        val after = foreignKeys()
        assertEquals(before, after, "addMissing() must issue no ALTER on a Flyway-migrated install")
        assertEquals(expected, crossArea(after))
    }

    /** The two cross-area lines of `fresh-install-foreign-keys.pg.snapshot.txt`. */
    private fun freshInstallLines(): List<String> =
        resource(FRESH_SNAPSHOT)
            .lines()
            .filter { line -> CrossAreaForeignKeys.all.any { line.startsWith(prefixOf(it)) } }
            .sorted()

    private fun crossArea(keys: List<String>): List<String> = keys.filter { line -> CrossAreaForeignKeys.all.any { line.startsWith(prefixOf(it)) } }.sorted()

    private fun prefixOf(fk: CrossAreaForeignKey): String = "${fk.table}.${fk.column} -> ${fk.refTable}.${fk.refColumn} "

    private fun foreignKeys(): List<String> = pg.dataSource.connection.use { SchemaDump.foreignKeys(it) }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "$name is missing" }
            .use { it.readBytes().decodeToString() }

    private companion object {
        const val BASE_SCHEMA = "db/restore-base/base_schema_v76.sql"
        const val FRESH_SNAPSHOT = "fresh-install-foreign-keys.pg.snapshot.txt"
    }
}
