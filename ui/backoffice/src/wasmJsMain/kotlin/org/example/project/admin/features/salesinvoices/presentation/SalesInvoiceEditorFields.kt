package org.example.project.admin.features.salesinvoices.presentation

/**
 * Focus/error keys of the invoice editor, in on-screen order. Strings rather than an enum because the
 * lines are a list: each line's name, quantity and price is its own field, keyed by its index.
 */
object SalesInvoiceEditorFields {
    const val BUYER_NAME = "buyerName"
    const val SERVICE_DATE = "serviceDate"
    const val DUE_DATE = "dueDate"

    fun itemName(line: Int): String = "line$line.itemName"

    fun quantity(line: Int): String = "line$line.quantity"

    fun price(line: Int): String = "line$line.price"

    /** Every key of an editor with [lineCount] lines, in on-screen order. */
    fun order(lineCount: Int): List<String> =
        listOf(BUYER_NAME, SERVICE_DATE, DUE_DATE) +
            (0 until lineCount).flatMap { listOf(itemName(it), quantity(it), price(it)) }
}
