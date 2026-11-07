package org.example.project.admin.core.presentation.belegfluss

/**
 * What the "Nächster Schritt" bar proposes: the earliest stage with work waiting ([first]) and, if
 * there is one, the stage after it ([then]). Counts are the number of invoices/payments waiting.
 */
data class InvoiceNextStep(
    val first: InvoiceFlowStage,
    val firstCount: Long,
    val then: InvoiceFlowStage?,
    val thenCount: Long,
)
