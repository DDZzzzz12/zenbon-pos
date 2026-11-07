package org.example.project.features.backup.data.service

import org.example.project.utils.quoteSqlIdentifier
import org.postgresql.copy.CopyManager
import org.slf4j.LoggerFactory
import java.io.StringReader

/**
 * Loading an archive's per-table CSVs into a database, tolerant of a schema that differs from the
 * one the archive was taken from.
 *
 * Shared by the live restore and by [RestoreStaging], which loads a pre-1.1.0 archive into a
 * throwaway database first, so an archive meets one set of rules whichever database it lands in.
 * Every query here addresses the connection's own `public` schema.
 */
internal object ArchiveTables {
    private val logger = LoggerFactory.getLogger(ArchiveTables::class.java)

    /**
     * Imports one table's CSV via COPY. Columns the archive carries but the schema lacks are
     * dropped (with a warning); NOT-NULL columns the schema has but the archive lacks get a
     * synthetic value. An empty CSV is skipped. The caller owns the transaction and the triggers.
     */
    fun load(
        conn: java.sql.Connection,
        copyManager: CopyManager,
        table: String,
        csvBytes: ByteArray,
    ) {
        conn.createStatement().use { stmt ->
            val csvString = csvBytes.decodeToString()
            if (csvBytes.size <= 1 || csvString.isBlank()) {
                logger.debug("load: skipping empty table={}", table)
                return@use
            }

            val headerLine = csvString.lineSequence().first()
            val csvColumns = headerLine.split(",").map { it.trim().removeSurrounding("\"") }

            val columnMeta = getColumnMeta(conn, table)
            val tableColumns = columnMeta.keys
            val survivingColumns = csvColumns.filter { it in tableColumns }
            val survivingSet = survivingColumns.toSet()
            val droppedColumns = csvColumns.filter { it !in tableColumns }

            // Columns the CURRENT schema has but this (older) backup does not. Nullable or
            // defaulted ones are fine — the DB fills them. NOT-NULL-without-default ones
            // would make the COPY/INSERT violate the constraint, so they must be given a
            // synthetic value (type-appropriate, or a curated override for CHECK/enum cols).
            val requiredMissing =
                tableColumns.filter { col ->
                    col !in survivingSet && columnMeta.getValue(col).let { !it.nullable && !it.hasDefault }
                }

            if (droppedColumns.isNotEmpty()) {
                logger.warn(
                    "load: table={} dropping columns no longer in schema={}",
                    table,
                    droppedColumns,
                )
            }
            if (requiredMissing.isNotEmpty()) {
                logger.warn(
                    "load: table={} backup predates NOT-NULL columns={}, filling with synthetic defaults",
                    table,
                    requiredMissing,
                )
            }

            if (droppedColumns.isEmpty() && requiredMissing.isEmpty()) {
                val columns = csvColumns.joinToString(", ") { quoteSqlIdentifier(it) }
                val copySql =
                    "COPY ${quoteSqlIdentifier(table)} ($columns) FROM STDIN WITH (FORMAT csv, HEADER true)"
                copyManager.copyIn(copySql, StringReader(csvString))
            } else {
                val stagingName = "_restore_stage_$table".take(63)
                stmt.execute("DROP TABLE IF EXISTS ${quoteSqlIdentifier(stagingName)}")
                stmt.execute(
                    "CREATE TEMP TABLE ${quoteSqlIdentifier(stagingName)} " +
                        "(LIKE ${quoteSqlIdentifier(table)} INCLUDING DEFAULTS) ON COMMIT DROP",
                )
                for (col in tableColumns - survivingSet) {
                    stmt.execute(
                        "ALTER TABLE ${quoteSqlIdentifier(stagingName)} " +
                            "DROP COLUMN ${quoteSqlIdentifier(col)}",
                    )
                }
                for (col in droppedColumns) {
                    // `col` is a column name the current schema does NOT have, i.e. arbitrary
                    // text from the archive's CSV header. This is the one identifier here
                    // that is entirely attacker-controlled — it must be escaped, not merely
                    // wrapped in quotes.
                    stmt.execute(
                        "ALTER TABLE ${quoteSqlIdentifier(stagingName)} " +
                            "ADD COLUMN ${quoteSqlIdentifier(col)} TEXT",
                    )
                }
                val copyCols = csvColumns.joinToString(", ") { quoteSqlIdentifier(it) }
                val copySql =
                    "COPY ${quoteSqlIdentifier(stagingName)} ($copyCols) FROM STDIN WITH (FORMAT csv, HEADER true)"
                copyManager.copyIn(copySql, StringReader(csvString))

                // Insert surviving columns from staging, plus a synthetic value for each
                // NOT-NULL column the backup didn't carry (defaulted/nullable ones are
                // omitted so the DB fills them itself).
                val insertColNames =
                    (survivingColumns + requiredMissing)
                        .joinToString(", ") { quoteSqlIdentifier(it) }
                val selectExprs =
                    (
                        survivingColumns.map { quoteSqlIdentifier(it) } +
                            requiredMissing.map { syntheticFill(table, it, columnMeta.getValue(it).dataType) }
                    ).joinToString(", ")
                stmt.execute(
                    "INSERT INTO ${quoteSqlIdentifier(table)} ($insertColNames) " +
                        "SELECT $selectExprs FROM ${quoteSqlIdentifier(stagingName)}",
                )
                stmt.execute("DROP TABLE ${quoteSqlIdentifier(stagingName)}")
            }
            logger.debug("load: imported table={}", table)
        }
    }

    /**
     * Discovers all user tables in the public schema, excluding Flyway history.
     */
    fun discover(conn: java.sql.Connection): List<String> {
        val tables = mutableListOf<String>()
        val stmt = conn.createStatement()
        val rs =
            stmt.executeQuery(
                """
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public'
                AND tablename != 'flyway_schema_history'
                ORDER BY tablename
                """.trimIndent(),
            )
        while (rs.next()) {
            tables.add(rs.getString("tablename"))
        }
        rs.close()
        stmt.close()
        return tables
    }

    /**
     * Resets all sequences to match the maximum value of their associated columns.
     *
     * Non-critical: failures are logged but never propagated; sequences may have gaps.
     */
    fun resetSequences(conn: java.sql.Connection) {
        try {
            val stmt = conn.createStatement()

            val rs =
                stmt.executeQuery(
                    """
                    SELECT c.relname AS seq, t.relname AS tab, a.attname AS col
                    FROM pg_class c
                    JOIN pg_depend d ON d.objid = c.oid AND d.deptype = 'a'
                    JOIN pg_class t ON t.oid = d.refobjid
                    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
                    WHERE c.relkind = 'S'
                    """.trimIndent(),
                )

            val sequences = mutableListOf<Triple<String, String, String>>()
            while (rs.next()) {
                sequences.add(
                    Triple(
                        rs.getString("seq"),
                        rs.getString("tab"),
                        rs.getString("col"),
                    ),
                )
            }
            rs.close()

            for ((seq, tab, col) in sequences) {
                try {
                    val maxRs =
                        stmt.executeQuery(
                            "SELECT MAX(\"$col\") AS max_val FROM \"$tab\"",
                        )
                    maxRs.next()
                    val maxVal = maxRs.getLong("max_val")
                    val wasNull = maxRs.wasNull()
                    maxRs.close()

                    if (wasNull) {
                        stmt.execute("SELECT setval('\"$seq\"', 1, false)")
                    } else {
                        stmt.execute("SELECT setval('\"$seq\"', $maxVal)")
                    }
                    logger.debug(
                        "resetSequences: reset sequence={} -> {}",
                        seq,
                        if (wasNull) "1 (empty)" else maxVal.toString(),
                    )
                } catch (e: Exception) {
                    logger.warn(
                        "resetSequences: failed to reset sequence={}, error={}",
                        seq,
                        e.message,
                    )
                }
            }

            stmt.close()
        } catch (e: Exception) {
            logger.warn("resetSequences: failed to reset sequences (non-critical)", e)
        }
    }

    private data class ColMeta(
        val nullable: Boolean,
        val hasDefault: Boolean,
        val dataType: String,
    )

    /**
     * Column metadata (nullability, presence of a default, SQL data type) for a table, keyed by
     * column name in ordinal order. Used during restore to decide how to handle columns that the
     * current schema has but an older backup is missing.
     */
    private fun getColumnMeta(
        conn: java.sql.Connection,
        table: String,
    ): Map<String, ColMeta> {
        val meta = LinkedHashMap<String, ColMeta>()
        conn
            .prepareStatement(
                """
                SELECT column_name, is_nullable, column_default, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        meta[rs.getString("column_name")] =
                            ColMeta(
                                nullable = rs.getString("is_nullable") == "YES",
                                hasDefault = rs.getString("column_default") != null,
                                dataType = rs.getString("data_type"),
                            )
                    }
                }
            }
        return meta
    }

    /**
     * V54's own backfill, verbatim, so a restore reproduces exactly what the migration did rather
     * than inventing a second mapping. `ELSE 1` is V54's fallback too.
     */
    private object Legacy {
        const val LOCATION_TYPE_CASE =
            "CASE \"location_type\" " +
                "WHEN 'INDOOR' THEN 1 " +
                "WHEN 'OUTDOOR' THEN 2 " +
                "WHEN 'TAKEAWAY' THEN 3 " +
                "WHEN 'VOUCHER' THEN 4 " +
                "WHEN 'QUICK_SALE' THEN 5 " +
                "ELSE 1 END"
    }

    /**
     * SQL literal/expression used to fill a NOT-NULL column that exists in the current schema but
     * is absent from an older backup. A curated override is used for columns a generic zero value
     * would not satisfy — a CHECK, an enum, or a foreign key; otherwise a type-appropriate zero.
     */
    private fun syntheticFill(
        table: String,
        column: String,
        dataType: String,
    ): String {
        restoreFillOverrides[table to column]?.let { return it }
        return when (dataType.lowercase()) {
            "character varying", "varchar", "text", "character", "char", "bpchar", "name" -> "''"
            "integer", "bigint", "smallint", "int", "int2", "int4", "int8" -> "0"
            "numeric", "decimal", "real", "double precision", "money", "float4", "float8" -> "0"
            "boolean", "bool" -> "false"
            "timestamp without time zone", "timestamp with time zone", "timestamp", "timestamptz" -> "now()"
            "date" -> "now()::date"
            "time without time zone", "time with time zone", "time" -> "now()::time"
            "uuid" -> "gen_random_uuid()"
            "jsonb" -> "'{}'::jsonb"
            "json" -> "'{}'::json"
            "bytea" -> "''::bytea"
            else -> "''"
        }
    }

    /**
     * Curated synthetic values for NOT-NULL columns a generic type-zero value would not satisfy —
     * a CHECK, an enum, or a foreign key — when restoring a backup that predates the column.
     * Keyed by (table, column). Extend as new constrained columns are added.
     *
     * A foreign key is the case worth calling out: `0` is type-correct and always wrong, because
     * no table has a row with that id. Without an entry here such a column produces one dangling
     * row per archived row and the FK check aborts the restore.
     */
    private val restoreFillOverrides: Map<Pair<String, String>, String> =
        mapOf(
            ("datev_export_log" to "source") to "'MANUAL'",
            // A backup taken before V54 carries the enum string `location_type`; V54 replaced it
            // with an FK to `location_types`. The generic bigint fill would write 0, which no
            // location type has, so every row dangles and the FK check aborts the whole restore.
            //
            // The expression is V54's own backfill, verbatim, and reads the legacy column out of
            // the staging table — where it survives as TEXT precisely because the current schema
            // no longer has it. Mapping rather than defaulting matters fiscally: `is_takeaway`
            // follows from the location type, and flattening TAKEAWAY to Indoor would move rows
            // between VAT treatments. A backup missing `location_type_id` is by construction a
            // pre-V54 one, which therefore still has `location_type`.
            ("r_table" to "location_type_id") to Legacy.LOCATION_TYPE_CASE,
            ("room_element" to "location_type_id") to Legacy.LOCATION_TYPE_CASE,
        )
}
