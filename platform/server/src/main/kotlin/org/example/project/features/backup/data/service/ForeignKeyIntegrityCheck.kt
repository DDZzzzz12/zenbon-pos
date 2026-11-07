package org.example.project.features.backup.data.service

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Verifies that every foreign key in the database is satisfied.
 *
 * The restore loads data with `DISABLE TRIGGER ALL`, so foreign keys are NOT enforced while rows
 * are inserted — and re-enabling triggers does not re-validate rows already there. Without this
 * check a restore can commit a database that violates its own constraints and report success; the
 * damage then surfaces arbitrarily later, which is how a pre-V84 backup silently orphaned an
 * entire Kassenbuch and only failed at DSFinV-K generation.
 *
 * Runs on EVERY restore, not only when a synthetic fill happened: a key can dangle for reasons
 * other than a fill (a hand-edited archive, a truncated CSV, a table dropped between versions).
 */
object ForeignKeyIntegrityCheck {
    private val logger = LoggerFactory.getLogger(ForeignKeyIntegrityCheck::class.java)

    data class Violation(
        val constraint: String,
        val childTable: String,
        val childColumns: List<String>,
        val orphanCount: Long,
    )

    /** @return every violated foreign key, empty when the database is consistent. */
    fun findViolations(conn: Connection): List<Violation> {
        val constraints = enumerateForeignKeys(conn)
        logger.info("ForeignKeyIntegrityCheck: verifying {} foreign key(s)", constraints.size)

        return constraints.mapNotNull { fk ->
            val count = countOrphans(conn, fk)
            if (count == 0L) null else Violation(fk.name, fk.childTable, fk.childColumns, count)
        }
    }

    private data class ForeignKey(
        val name: String,
        val childTable: String,
        val childColumns: List<String>,
        val parentTable: String,
        val parentColumns: List<String>,
    )

    private fun enumerateForeignKeys(conn: Connection): List<ForeignKey> {
        val sql =
            """
            SELECT con.conname,
                   child.relname  AS child_table,
                   parent.relname AS parent_table,
                   ARRAY(SELECT a.attname FROM unnest(con.conkey)  WITH ORDINALITY AS k(attnum, ord)
                         JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
                         ORDER BY k.ord)  AS child_cols,
                   ARRAY(SELECT a.attname FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                         JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum
                         ORDER BY k.ord)  AS parent_cols
            FROM pg_constraint con
            JOIN pg_class child  ON child.oid  = con.conrelid
            JOIN pg_class parent ON parent.oid = con.confrelid
            JOIN pg_namespace ns ON ns.oid = child.relnamespace
            WHERE con.contype = 'f' AND ns.nspname = 'public'
            """.trimIndent()

        val out = mutableListOf<ForeignKey>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val childCols = (rs.getArray("child_cols").array as Array<String>).toList()

                    @Suppress("UNCHECKED_CAST")
                    val parentCols = (rs.getArray("parent_cols").array as Array<String>).toList()
                    out +=
                        ForeignKey(
                            name = rs.getString("conname"),
                            childTable = rs.getString("child_table"),
                            childColumns = childCols,
                            parentTable = rs.getString("parent_table"),
                            parentColumns = parentCols,
                        )
                }
            }
        }
        return out
    }

    private fun countOrphans(
        conn: Connection,
        fk: ForeignKey,
    ): Long {
        val on =
            fk.childColumns.indices.joinToString(" AND ") { i ->
                "c.${quote(fk.childColumns[i])} = p.${quote(fk.parentColumns[i])}"
            }
        // A NULL in any child column means "no reference", which is legal for a nullable FK.
        val notNull = fk.childColumns.joinToString(" AND ") { "c.${quote(it)} IS NOT NULL" }
        val firstParent = quote(fk.parentColumns.first())

        val sql =
            "SELECT count(*) FROM ${quote(fk.childTable)} c " +
                "LEFT JOIN ${quote(fk.parentTable)} p ON $on " +
                "WHERE $notNull AND p.$firstParent IS NULL"

        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""
}
