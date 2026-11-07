package org.example.project.einvoice.model

import java.math.BigDecimal

/**
 * EN 16931 BG-25 invoice line.
 *
 * [unitCode] is UN/ECE Recommendation 20 (`C62` piece, `KGM` kilogram, `LTR` litre, `HUR` hour).
 * [netUnitPrice] (BT-146) routinely carries four decimal places — do not round it to two.
 */
data class EInvoiceLine(
    val lineId: String,
    val itemName: String,
    val quantity: BigDecimal,
    val unitCode: String,
    val netUnitPrice: BigDecimal,
    val lineNetAmount: BigDecimal,
    val vatCategoryCode: String,
    val vatRate: BigDecimal,
    val itemDescription: String? = null,
    val sellerItemId: String? = null,
    val buyerItemId: String? = null,
    val itemStandardId: String? = null,
)
