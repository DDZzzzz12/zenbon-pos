package org.example.project.database

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ForeignKeyConstraint
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * The cross-area foreign keys the host declares (see [CrossAreaForeignKey]).
 *
 * Both operations run inside the caller's transaction, read the database's own JDBC metadata before
 * acting, and are idempotent: a constraint that exists — under its own name or any other on the same
 * column, as an install migrated by Flyway may carry — is never added twice, and one that is absent is
 * never dropped. The DDL itself is Exposed's ([ForeignKeyConstraint]), so it is the statement Exposed
 * emitted when the column was a `reference`.
 */
object CrossAreaForeignKeys {
    private val logger = LoggerFactory.getLogger(CrossAreaForeignKeys::class.java)

    /** The declared keys — [CrossAreaForeignKeyCatalog.all], which owns the list. */
    val all: List<CrossAreaForeignKey> = CrossAreaForeignKeyCatalog.all

    /**
     * Adds every declared foreign key whose two tables exist and whose column carries no foreign key
     * to the referenced table yet. Called after every `SchemaUtils.create`.
     */
    fun addMissing() {
        val tx = TransactionManager.current()
        val missing =
            all.filter { fk ->
                tableExists(tx, fk.table) && tableExists(tx, fk.refTable) && existingNames(tx, fk).isEmpty()
            }
        missing.forEach { fk ->
            constraintOf(fk).createStatement().forEach { tx.exec(it) }
            logger.info("Added cross-area foreign key {} ({}.{} -> {}.{})", fk.name, fk.table, fk.column, fk.refTable, fk.refColumn)
        }
        if (missing.isNotEmpty()) currentDialectMetadata.resetCaches()
    }

    /**
     * Drops every declared foreign key that references one of [dropping], before those tables are
     * dropped.
     *
     * Exposed cannot see these keys, so it no longer orders the drop around them (nor drops a parent
     * `CASCADE` because of them); without this, dropping `sales_invoice` or `documents` would be refused
     * while `bill` or `kassenbuch_header` still references it. A key whose parent is not being dropped
     * is left alone — it goes with its child table if that is dropped, exactly as before.
     */
    fun dropExisting(dropping: Collection<Table>) {
        val tx = TransactionManager.current()
        val droppedNames = dropping.map { it.tableName.lowercase() }.toSet()
        var dropped = false
        for (fk in all.filter { it.refTable in droppedNames }) {
            for (actual in existingNames(tx, fk)) {
                val statements =
                    if (actual.equals(fk.name, ignoreCase = true)) {
                        constraintOf(fk).dropStatement()
                    } else {
                        listOf("ALTER TABLE ${tx.identity(tableNamed(fk.table))} DROP CONSTRAINT \"$actual\"")
                    }
                statements.forEach { tx.exec(it) }
                dropped = true
                logger.info("Dropped cross-area foreign key {} on {}.{}", actual, fk.table, fk.column)
            }
        }
        if (dropped) currentDialectMetadata.resetCaches()
    }

    /**
     * The column [column] references through a declared cross-area foreign key, or null — see
     * [CrossAreaForeignKeyCatalog.refereeOf].
     */
    fun refereeOf(column: Column<*>): Column<*>? = CrossAreaForeignKeyCatalog.refereeOf(column)

    /** The Exposed constraint [fk] stands for — the one the `reference` declaration used to carry. */
    private fun constraintOf(fk: CrossAreaForeignKey): ForeignKeyConstraint =
        ForeignKeyConstraint(
            target = CrossAreaForeignKeyCatalog.columnNamed(fk.refTable, fk.refColumn),
            from = CrossAreaForeignKeyCatalog.columnNamed(fk.table, fk.column),
            onUpdate = null,
            onDelete = fk.onDelete?.let { ReferenceOption.valueOf(it) },
            name = fk.name,
        )

    private fun tableNamed(name: String): Table =
        SchemaManager.allTables.singleOrNull { it.tableName == name }
            ?: error("cross-area foreign key names table $name, which SchemaManager does not manage")

    private fun jdbc(tx: JdbcTransaction): Connection = tx.connection.connection as Connection

    /** The name as the database stores an unquoted identifier (H2 upper-cases, PostgreSQL lower-cases). */
    private fun stored(
        conn: Connection,
        identifier: String,
    ): String = if (conn.metaData.storesUpperCaseIdentifiers()) identifier.uppercase() else identifier.lowercase()

    private fun tableExists(
        tx: JdbcTransaction,
        table: String,
    ): Boolean {
        val conn = jdbc(tx)
        return conn.metaData.getTables(conn.catalog, conn.schema, stored(conn, table), arrayOf("TABLE")).use { it.next() }
    }

    /** Names of the foreign keys on [fk]'s column that reference [fk]'s table, as the database reports them. */
    private fun existingNames(
        tx: JdbcTransaction,
        fk: CrossAreaForeignKey,
    ): List<String> {
        val conn = jdbc(tx)
        return conn.metaData.getImportedKeys(conn.catalog, conn.schema, stored(conn, fk.table)).use { rs ->
            buildList {
                while (rs.next()) {
                    val sameColumn = rs.getString("FKCOLUMN_NAME").equals(fk.column, ignoreCase = true)
                    val sameTarget = rs.getString("PKTABLE_NAME").equals(fk.refTable, ignoreCase = true)
                    if (sameColumn && sameTarget) add(rs.getString("FK_NAME"))
                }
            }.distinct()
        }
    }
}
