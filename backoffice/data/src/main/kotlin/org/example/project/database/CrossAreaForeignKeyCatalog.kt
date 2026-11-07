package org.example.project.database

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * The cross-area foreign keys (see [CrossAreaForeignKey]) as data: which column references which,
 * resolved against [ManagedTables].
 *
 * The declarations sit below the host so the custom-export schema graph can read them without
 * reaching into `pos-server`; the DDL that adds and drops them stays in the host's
 * `CrossAreaForeignKeys`, which reads this list.
 */
object CrossAreaForeignKeyCatalog {
    val all: List<CrossAreaForeignKey> =
        listOf(
            // bill (ecr) → sales_invoice (accounting): a receivable clearing names the invoice it settles.
            CrossAreaForeignKey(
                name = "fk_bill_clears_sales_invoice_id__id",
                table = "bill",
                column = "clears_sales_invoice_id",
                refTable = "sales_invoice",
                refColumn = "id",
                onDelete = "RESTRICT",
            ),
            // kassenbuch_header (ecr) → documents (accounting): the entry's Beleg.
            CrossAreaForeignKey(
                name = "fk_kassenbuch_header_linked_document_id__id",
                table = "kassenbuch_header",
                column = "linked_document_id",
                refTable = "documents",
                refColumn = "id",
                onDelete = null,
            ),
        )

    /**
     * The column [column] references through a declared cross-area foreign key, or null. The Exposed
     * counterpart of `Column.referee` for these keys, which the column itself no longer knows.
     */
    fun refereeOf(column: Column<*>): Column<*>? =
        all
            .firstOrNull { it.table == column.table.tableName && it.column == column.name }
            ?.let { columnNamed(it.refTable, it.refColumn) }

    private fun tableNamed(name: String): Table =
        ManagedTables.all.singleOrNull { it.tableName == name }
            ?: error("cross-area foreign key names table $name, which SchemaManager does not manage")

    /** The column [column] of the managed table [table]; fails if either is unknown. */
    fun columnNamed(
        table: String,
        column: String,
    ): Column<*> =
        tableNamed(table).columns.singleOrNull { it.name == column }
            ?: error("cross-area foreign key names column $table.$column, which the table does not declare")
}
