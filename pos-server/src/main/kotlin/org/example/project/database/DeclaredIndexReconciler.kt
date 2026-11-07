package org.example.project.database

import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

/**
 * Makes a PostgreSQL database carry every index the Exposed tables declare, under the declared
 * name, on every start.
 *
 * A FRESH install never runs the Flyway migrations — `initDB` builds the schema from the Exposed
 * objects and baselines Flyway at the latest version — so the Exposed declarations are the ONLY
 * source of a fresh install's indexes. Each migration's index must therefore also be declared on
 * its table; `FreshInstallIndexParityTest` fails when one is not. `SchemaUtils.create` builds the
 * declared indexes only for a table it creates, though, so a database whose tables already exist
 * (an install made by an earlier build) would never gain an index declared later. This closes that
 * gap, idempotently:
 *
 * - an index whose name already exists is left alone — whatever its definition, so a migration's
 *   tolerant PLAIN fallback (V82's TSE guard on an install that already held duplicates) is never
 *   fought over;
 * - an index that exists under a name Exposed generated before the declaration was named after its
 *   migration (same table, uniqueness and key columns, no predicate) is RENAMED, so the database
 *   does not end up with the same index twice;
 * - otherwise it is CREATED.
 *
 * Every statement runs in its own autocommit, so one failure cannot roll back or block another.
 * A failure is logged at ERROR and startup continues: the realistic cause is existing duplicate
 * rows under a UNIQUE index, which the database cannot repair on its own, and refusing to start
 * would take the till down over a guard the service layer also enforces. The statement is simply
 * tried again on the next start, so cleaning up the duplicates is all an operator has to do.
 *
 * PostgreSQL only. H2 is the test suite's engine, always built fresh, and cannot express the
 * partial or functional indexes.
 */
internal object DeclaredIndexReconciler {
    private val logger = LoggerFactory.getLogger(DeclaredIndexReconciler::class.java)

    /**
     * Index names only Exposed ever generated, for a declaration that has since changed its key so
     * it no longer matches by shape. Dropped so an install made by an earlier build converges with an
     * upgraded one instead of keeping a redundant index. Never a name a migration created.
     */
    private val RETIRED_INDEXES =
        listOf(
            // Was (cash_book_id); the declaration is now V82's idx_cash_book_close_book
            // (cash_book_id, period_end DESC), which covers it.
            "cash_book_close_cash_book_id",
        )

    private const val EXISTING_INDEXES_SQL =
        """
        SELECT i.relname AS index_name,
               t.relname AS table_name,
               ix.indisunique AS is_unique,
               ix.indisprimary AS is_primary,
               (ix.indpred IS NULL AND ix.indexprs IS NULL AND 0 = ALL (ix.indoption::int2[])) AS is_plain,
               ARRAY(
                   SELECT a.attname::text
                   FROM unnest(ix.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                   JOIN pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum
                   ORDER BY k.ord
               ) AS column_names
        FROM pg_index ix
        JOIN pg_class i ON i.oid = ix.indexrelid
        JOIN pg_class t ON t.oid = ix.indrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
        """

    /** What [reconcile] found in the database for one index. */
    private data class ExistingIndex(
        val name: String,
        val table: String,
        val unique: Boolean,
        val primary: Boolean,
        val plain: Boolean,
        val columns: List<String>,
    )

    /** One statement to run, and what it is for — the log line names both. */
    private data class Step(
        val target: String,
        val table: String,
        val sql: String,
    )

    /**
     * Brings [dataSource] in line with the indexes declared on [tables]. Returns the number of
     * statements that failed (0 when everything is in place).
     */
    fun reconcile(
        database: Database,
        dataSource: DataSource,
        tables: List<Table>,
    ): Int {
        val steps =
            transaction(database) {
                if (currentDialect !is PostgreSQLDialect) {
                    logger.info("Declared-index reconciliation skipped: dialect is {}, not PostgreSQL", currentDialect.name)
                    return@transaction null
                }
                planSteps(tables, dataSource.connection.use { readExisting(it) })
            } ?: return 0

        val failures = steps.count { !run(dataSource, it) }
        if (failures == 0) {
            logger.info("Declared indexes verified ({} statement(s) applied)", steps.size)
        } else {
            logger.error(
                "Declared indexes: {} of {} statement(s) FAILED — the server runs without those database guards " +
                    "until they succeed; each is retried on the next start",
                failures,
                steps.size,
            )
        }
        return failures
    }

    /** Must run inside a transaction: Exposed renders the CREATE statements against its dialect. */
    private fun planSteps(
        tables: List<Table>,
        existing: Map<String, ExistingIndex>,
    ): List<Step> {
        val declaredNames =
            tables.flatMap { table -> table.indices.map { it.indexName.lowercase() } + listOfNotNull(customPrimaryKeyName(table)) }.toSet()
        val legacy = existing.values.filter { it.name !in declaredNames }

        val indexSteps =
            tables.flatMap { table ->
                table.indices
                    .filter { it.indexName.lowercase() !in existing }
                    .map { index -> renameOrCreate(index, legacy) }
            }
        val primaryKeySteps =
            tables.mapNotNull { table ->
                val name = customPrimaryKeyName(table) ?: return@mapNotNull null
                if (name in existing) return@mapNotNull null
                val columns = table.primaryKey!!.columns.map { it.name }
                legacy
                    .firstOrNull { it.primary && it.table == table.tableName && it.columns == columns }
                    ?.let { Step(name, table.tableName, "ALTER INDEX ${quote(it.name)} RENAME TO ${quote(name)}") }
            }
        val retiredSteps =
            RETIRED_INDEXES
                .filter { it in existing }
                .map { Step(it, existing.getValue(it).table, "DROP INDEX IF EXISTS ${quote(it)}") }
        return indexSteps + primaryKeySteps + retiredSteps
    }

    private fun renameOrCreate(
        index: Index,
        legacy: List<ExistingIndex>,
    ): Step {
        val name = index.indexName.lowercase()
        val table = index.table.tableName
        val sameShape =
            legacy.takeIf { index.filterCondition == null && index.functions == null }?.firstOrNull {
                !it.primary &&
                    it.plain &&
                    it.table == table &&
                    it.unique == index.unique &&
                    it.columns == index.columns.map { column -> column.name }
            }
        if (sameShape != null) {
            return Step(name, table, "ALTER INDEX ${quote(sameShape.name)} RENAME TO ${quote(name)}")
        }
        val create = index.createStatement().filter { it.isNotBlank() }.joinToString(";\n")
        return Step(name, table, create)
    }

    /**
     * The primary key's name when the table names it explicitly. A default-named single-column key
     * is written inline by Exposed and named by PostgreSQL (`<table>_pkey`), so its declared name is
     * not the one in the database and must not be "repaired".
     */
    private fun customPrimaryKeyName(table: Table): String? {
        val primaryKey = table.primaryKey ?: return null
        return primaryKey.name.lowercase().takeIf { it != "pk_${table.tableName}".lowercase() }
    }

    private fun readExisting(conn: Connection): Map<String, ExistingIndex> =
        conn.createStatement().use { st ->
            st.executeQuery(EXISTING_INDEXES_SQL.trimIndent()).use { rs ->
                buildMap {
                    while (rs.next()) {
                        @Suppress("UNCHECKED_CAST")
                        val columns = (rs.getArray("column_names").array as Array<String>).toList()
                        val name = rs.getString("index_name")
                        put(
                            name,
                            ExistingIndex(
                                name = name,
                                table = rs.getString("table_name"),
                                unique = rs.getBoolean("is_unique"),
                                primary = rs.getBoolean("is_primary"),
                                plain = rs.getBoolean("is_plain"),
                                columns = columns,
                            ),
                        )
                    }
                }
            }
        }

    /** Runs one step in its own autocommit connection. Returns false — after logging why — on failure. */
    private fun run(
        dataSource: DataSource,
        step: Step,
    ): Boolean =
        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { it.execute(step.sql) }
            }
            logger.info("Declared index {} on {}: {}", step.target, step.table, step.sql.lineSequence().first())
            true
        } catch (e: Exception) {
            logger.error(
                "Declared index {} on {} could NOT be applied ({}). If it is UNIQUE, existing rows violate it: find " +
                    "and resolve the duplicates; the statement is retried on every start. Statement: {}",
                step.target,
                step.table,
                e.message,
                step.sql,
            )
            false
        }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
