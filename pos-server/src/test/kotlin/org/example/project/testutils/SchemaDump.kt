package org.example.project.testutils

import java.sql.Connection
import java.sql.DatabaseMetaData

/**
 * A database schema as sorted text lines, read from JDBC metadata only — so the same dump works on
 * H2 and PostgreSQL and says what the database holds, not what the Exposed objects declare.
 *
 * Used to pin a fresh install's schema: a refactoring that moves a table or a foreign key between
 * modules must leave every line here unchanged.
 */
object SchemaDump {
    /**
     * Every foreign key of every table: `child.column -> parent.column`, the constraint name, the
     * delete and update rules, the deferrability and the column's position in the key.
     */
    fun foreignKeys(conn: Connection): List<String> {
        val meta = conn.metaData
        return tablesOf(conn)
            .flatMap { table ->
                meta.getImportedKeys(conn.catalog, conn.schema, table).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                "${rs.getString("FKTABLE_NAME").lowercase()}.${rs.getString("FKCOLUMN_NAME").lowercase()}" +
                                    " -> ${rs.getString("PKTABLE_NAME").lowercase()}.${rs.getString("PKCOLUMN_NAME").lowercase()}" +
                                    " name=${rs.getString("FK_NAME")?.lowercase()}" +
                                    " onDelete=${ruleName(rs.getShort("DELETE_RULE").toInt())}" +
                                    " onUpdate=${ruleName(rs.getShort("UPDATE_RULE").toInt())}" +
                                    " deferrability=${rs.getShort("DEFERRABILITY")}" +
                                    " seq=${rs.getShort("KEY_SEQ")}",
                            )
                        }
                    }
                }
            }.sorted()
    }

    /** Every column (type, size, nullability, default, auto-increment) and every index entry of every table. */
    fun columnsAndIndexes(conn: Connection): List<String> {
        val meta = conn.metaData
        val tables = tablesOf(conn)
        val columns =
            tables.flatMap { table ->
                meta.getColumns(conn.catalog, conn.schema, table, "%").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                "column ${table.lowercase()}.${rs.getString("COLUMN_NAME").lowercase()}" +
                                    " type=${rs.getString("TYPE_NAME")} size=${rs.getInt("COLUMN_SIZE")}" +
                                    " digits=${rs.getInt("DECIMAL_DIGITS")} nullable=${rs.getString("IS_NULLABLE")}" +
                                    " default=${rs.getString("COLUMN_DEF")} autoinc=${rs.getString("IS_AUTOINCREMENT")}" +
                                    " pos=${rs.getInt("ORDINAL_POSITION")}",
                            )
                        }
                    }
                }
            }
        val indexes =
            tables.flatMap { table ->
                meta.getIndexInfo(conn.catalog, conn.schema, table, false, false).use { rs ->
                    buildList {
                        while (rs.next()) {
                            val name = rs.getString("INDEX_NAME") ?: continue
                            add(
                                "index ${table.lowercase()}.${name.lowercase()}" +
                                    " unique=${!rs.getBoolean("NON_UNIQUE")}" +
                                    " pos=${rs.getShort("ORDINAL_POSITION")} column=${rs.getString("COLUMN_NAME")?.lowercase()}" +
                                    " order=${rs.getString("ASC_OR_DESC")} filter=${rs.getString("FILTER_CONDITION")}",
                            )
                        }
                    }
                }
            }
        return (columns + indexes).sorted()
    }

    private fun tablesOf(conn: Connection): List<String> =
        conn.metaData
            .getTables(conn.catalog, conn.schema, "%", arrayOf("TABLE"))
            .use { rs ->
                buildList { while (rs.next()) add(rs.getString("TABLE_NAME")) }
            }.sorted()

    private fun ruleName(rule: Int): String =
        when (rule) {
            DatabaseMetaData.importedKeyCascade -> "CASCADE"
            DatabaseMetaData.importedKeyRestrict -> "RESTRICT"
            DatabaseMetaData.importedKeySetNull -> "SET_NULL"
            DatabaseMetaData.importedKeyNoAction -> "NO_ACTION"
            DatabaseMetaData.importedKeySetDefault -> "SET_DEFAULT"
            else -> "UNKNOWN($rule)"
        }
}
