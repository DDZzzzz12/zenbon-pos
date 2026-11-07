package org.example.project.features.salesinvoice.domain.service

/**
 * A bill reconciled for its Ausgangsrechnung: the VAT rows of the meal (owner tip taken out) and the
 * promotions as allowances. Per VAT key, `Σ line gross − Σ allowance gross == meal row gross`.
 */
data class ReconciledBillInvoice(
    val mealRows: List<BillInvoiceVatFact>,
    val allowances: List<DraftAllowance>,
)
