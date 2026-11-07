package org.example.project.admin.features.supplier.presentation

/**
 * The columns Lieferanten can be ordered by, each carrying the table column index it sits at.
 *
 * All six sort. The two money columns were inert at first because they are **aggregates** rather
 * than stored fields; the server now orders them with a correlated subquery, so the sort still
 * happens before paging — see `SupplierSort`'s KDoc on the server.
 *
 * [wireValue] rather than `name` at the call site, so a rename here cannot silently change what is
 * sent to a server that has never heard of the new name.
 */
enum class SupplierSortColumn(
    val wireValue: String,
    val columnIndex: Int,
) {
    NAME("NAME", 0),
    VAT_ID("VAT_ID", 1),
    CREDITOR_NO("CREDITOR_NO", 2),
    OPEN_TOTAL("OPEN_TOTAL", 3),
    YTD_SPEND("YTD_SPEND", 4),
    STATUS("STATUS", 5),
    ;

    companion object {
        fun fromColumnIndex(index: Int): SupplierSortColumn? = entries.firstOrNull { it.columnIndex == index }
    }
}
