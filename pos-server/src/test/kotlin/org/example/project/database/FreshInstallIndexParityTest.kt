package org.example.project.database

import org.example.project.features.backup.data.service.RestoreStaging
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A FRESH install and an UPGRADED install must end with the same indexes: same name, same
 * uniqueness, same key columns, same partial predicate.
 *
 * The two paths build the schema from different sources. An upgrade runs the Flyway chain; a fresh
 * install never runs it — `initDB` builds the schema from the Exposed objects and baselines Flyway
 * at the latest version. Every index a migration declares therefore has to be declared on the
 * Exposed table too, or a fresh install silently runs without it. The v1.1.0 upgrade rehearsal
 * (rt20) found exactly that: fresh installs lacked `idx_cash_book_one_fiscal_per_shop` and
 * `sales_invoice_one_live_per_bill` — one fiscal cash book per shop, one live invoice per bill —
 * and thirteen ordinary indexes. [SchemaFlywayParityTest] compares only the indexes Exposed
 * declares, one way, so an index that only the migrations carry was invisible to it.
 *
 * Both databases are built for real, in one embedded PostgreSQL:
 *  - **upgraded** — the frozen v1.0.10 schema (`db/restore-base/base_schema_v76.sql`, the same
 *    base the restore uses), then the real V77..Vmax chain, exactly as an in-place upgrade;
 *  - **fresh** — an empty database through the production `initDB`, exactly as a new shop;
 *  - **healed** — that fresh database wound back to what a fresh install made by an EARLIER build
 *    looks like (indexes under Exposed's generated names, the partial/functional ones absent, the
 *    retired one present), then started again. Nothing migrates such an install — V90 is the last
 *    migration — so `SchemaManager` has to bring it level on start, and this proves it does.
 *
 * PostgreSQL only: the partial predicates are the point, and H2 cannot express them.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Fresh install vs V76→Vmax upgrade — identical indexes (real PostgreSQL)")
class FreshInstallIndexParityTest {
    private lateinit var pg: PostgresTestDatabase
    private lateinit var upgraded: Map<String, String>
    private lateinit var fresh: Map<String, String>
    private lateinit var healed: Map<String, String>

    @BeforeAll
    fun buildBothSchemas() {
        pg = PostgresTestDatabase.start()
        pg.dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("CREATE DATABASE $UPGRADED_DB")
                it.execute("CREATE DATABASE $FRESH_DB")
            }
        }

        val upgradedDs = dataSourceFor(UPGRADED_DB)
        upgradedDs.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { it.execute(baseSchemaSql()) }
        }
        val migrated = FlywayConfig.migrateFromBaseline(upgradedDs, RestoreStaging.BASE_VERSION.toString())
        check(migrated.success && migrated.migrationsExecuted > 0) { "the V77..Vmax chain did not run on the v76 base" }
        upgraded = upgradedDs.connection.use { indexesOf(it) }

        stopKoin()
        DatabaseFactory.close()
        initDB(pg.hikariConfig().apply { jdbcUrl = urlFor(FRESH_DB) }, runMigrations = true)
        fresh = dataSourceFor(FRESH_DB).connection.use { indexesOf(it) }

        dataSourceFor(FRESH_DB).connection.use { windBackToAnEarlierBuild(it) }
        SchemaManager.createTables()
        healed = dataSourceFor(FRESH_DB).connection.use { indexesOf(it) }
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    @DisplayName("every index an upgraded install has, a fresh install has too — same name, uniqueness, columns and predicate")
    fun freshInstallHasEveryUpgradedIndex() {
        val missing = upgraded.filter { (name, def) -> fresh[name] != def }
        assertTrue(
            missing.isEmpty(),
            "A fresh install lacks (or differs on) ${missing.size} index(es) an upgraded install has. Declare each on " +
                "its Exposed table (SchemaManager creates the declared ones on every start):\n" +
                missing.entries.joinToString("\n") { (name, def) -> "  upgraded: $def\n     fresh: ${fresh[name] ?: "<absent>"}" },
        )
    }

    @Test
    @DisplayName("a fresh install has no index an upgraded install lacks")
    fun freshInstallHasNoExtraIndex() {
        val extra = fresh.filterKeys { it !in upgraded }
        assertTrue(
            extra.isEmpty(),
            "A fresh install carries ${extra.size} index(es) no migration creates — add them to a migration " +
                "or drop the Exposed declaration:\n" + extra.values.joinToString("\n") { "  $it" },
        )
    }

    @Test
    @DisplayName("a fresh install made by an earlier build heals on the next start — renamed, created, retired")
    fun earlierFreshInstallHealsOnStart() {
        val wrong = (upgraded.keys + healed.keys).filter { upgraded[it] != healed[it] }
        assertTrue(
            wrong.isEmpty(),
            "After a restart the earlier fresh install still differs from an upgraded one:\n" +
                wrong.joinToString("\n") { "  upgraded: ${upgraded[it]}\n    healed: ${healed[it]}" },
        )
    }

    @Test
    @DisplayName("duplicate rows under a unique guard: logged, startup continues, and it is built once the data is clean")
    fun duplicateRowsDoNotStopStartup() {
        val ds = dataSourceFor(FRESH_DB)
        ds.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use {
                it.execute("DROP INDEX idx_cash_book_one_fiscal_per_shop")
                // Two fiscal books for one shop — exactly what the index forbids. FK checks off: the
                // shop row is irrelevant to the index.
                it.execute("SET session_replication_role = replica")
                for (book in listOf("A", "B")) {
                    it.execute(
                        "INSERT INTO cash_book (shop_id, name, is_fiscal, datev_account, close_interval, is_active, " +
                            "sort_order, created_at) VALUES (4711, '$book', TRUE, '1000', 'MANUAL', TRUE, 0, NOW())",
                    )
                }
            }
        }

        val failed = DeclaredIndexReconciler.reconcile(DatabaseFactory.getDatabase(), ds, listOf(CashBookTable))
        assertEquals(1, failed, "the unique guard cannot be built over duplicates, and must be reported, not thrown")
        ds.connection.use { assertFalse("idx_cash_book_one_fiscal_per_shop" in indexesOf(it), "no half-built index") }

        ds.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { it.execute("DELETE FROM cash_book WHERE shop_id = 4711 AND name = 'B'") }
        }
        assertEquals(0, DeclaredIndexReconciler.reconcile(DatabaseFactory.getDatabase(), ds, listOf(CashBookTable)))
        ds.connection.use {
            assertEquals(upgraded["idx_cash_book_one_fiscal_per_shop"], indexesOf(it)["idx_cash_book_one_fiscal_per_shop"])
        }
    }

    @Test
    @DisplayName("the comparison is not vacuous: both schemas carry the integrity guards rt20 found missing")
    fun comparisonIsNotVacuous() {
        for (name in listOf("idx_cash_book_one_fiscal_per_shop", "sales_invoice_one_live_per_bill")) {
            assertTrue(upgraded[name]?.contains("UNIQUE") == true, "the upgraded schema must carry unique $name; got ${upgraded[name]}")
        }
        assertTrue(upgraded.size > 250, "expected the full schema's indexes, got ${upgraded.size}")
    }

    /**
     * index name → its definition from `pg_get_indexdef`, which carries the name, `UNIQUE`, the
     * table, the key columns with their ordering and the `WHERE` predicate. Constraint-backed
     * indexes (primary keys, `UNIQUE` constraints) are included: they are indexes too.
     */
    private fun indexesOf(conn: Connection): Map<String, String> =
        conn.createStatement().use { st ->
            st
                .executeQuery(
                    """
                    SELECT i.relname, pg_get_indexdef(ix.indexrelid)
                    FROM pg_index ix
                    JOIN pg_class i ON i.oid = ix.indexrelid
                    JOIN pg_class t ON t.oid = ix.indrelid
                    JOIN pg_namespace n ON n.oid = t.relnamespace
                    WHERE n.nspname = 'public'
                    """.trimIndent(),
                ).use { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString(1), rs.getString(2).replace("public.", ""))
                    }
                }
        }

    /**
     * Rewinds [conn]'s database to the index set a fresh install from an earlier build has: every
     * plain declared index back under the name Exposed generates when none is given, every partial
     * or functional one dropped (earlier builds declared none), every explicitly named primary key
     * under an older name, and the since-retired `cash_book_close_cash_book_id` present.
     */
    private fun windBackToAnEarlierBuild(conn: Connection) {
        conn.autoCommit = true
        val statements =
            transaction(DatabaseFactory.getDatabase()) {
                SchemaManager.allTables.flatMap { table ->
                    val indexes =
                        table.indices.mapNotNull { index ->
                            val generated = Index(index.columns, index.unique).indexName
                            when {
                                index.filterCondition != null || index.functions != null -> "DROP INDEX \"${index.indexName}\""
                                generated == index.indexName -> null
                                else -> "ALTER INDEX \"${index.indexName}\" RENAME TO \"$generated\""
                            }
                        }
                    val renamedPk =
                        table.primaryKey
                            ?.name
                            ?.lowercase()
                            ?.takeIf { it != "pk_${table.tableName}" }
                            ?.let { "ALTER INDEX \"$it\" RENAME TO \"pk_earlier_${table.tableName}\"" }
                    indexes + listOfNotNull(renamedPk)
                }
            }
        conn.createStatement().use { st ->
            statements.forEach { st.execute(it) }
            st.execute("CREATE INDEX cash_book_close_cash_book_id ON cash_book_close (cash_book_id)")
        }
    }

    private fun baseSchemaSql(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(BASE_SCHEMA)) { "$BASE_SCHEMA is missing" }
            .use { it.readBytes().decodeToString() }

    private fun urlFor(db: String): String = RestoreStaging.withDatabase(pg.jdbcUrl, db)

    private fun dataSourceFor(db: String): DataSource =
        PGSimpleDataSource().apply {
            setURL(urlFor(db))
            user = "postgres"
            password = "postgres"
        }

    private companion object {
        const val BASE_SCHEMA = "db/restore-base/base_schema_v76.sql"
        const val UPGRADED_DB = "parity_upgraded"
        const val FRESH_DB = "parity_fresh"
    }
}
