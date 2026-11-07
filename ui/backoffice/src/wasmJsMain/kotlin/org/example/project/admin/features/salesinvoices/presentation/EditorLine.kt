package org.example.project.admin.features.salesinvoices.presentation

/** One gross-price line input. Money is a raw `String` typed by the operator; nothing parses it as Double. */
data class EditorLine(
    val itemName: String = "",
    val itemDescription: String = "",
    val quantity: String = "1",
    val unitCode: String = "C62",
    val grossUnitPrice: String = "",
    val vatRate: String = "19.00",
)
