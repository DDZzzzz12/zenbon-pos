package org.example.project.admin.features.documents.presentation.capture

/**
 * One VAT-rate row as the operator types it: a **rate in percent units** (`"19"`, never `"0.19"` —
 * matching the DB and `Percentage`) and the gross taken at that rate.
 *
 * [gross] is only meaningful when the invoice carries **more than one** row. A single-rate invoice
 * — the ordinary German case — takes its row gross from [InvoiceCaptureForm.grossTotal], so the
 * operator types the amount once; see [InvoiceCaptureForm.grossFor]. A second row is the mixed-rate
 * Gastro bill (7 % food, 19 % drinks), where the gross per rate is the only thing the paper states.
 *
 * `taxableAmount`, `vatAmount` and `vatCategoryCode` are deliberately absent: they are **derived**
 * (see `deriveVatRow`) and holding them as editable strings would let the three disagree.
 */
internal data class VatRowForm(
    val rate: String = "",
    val gross: String = "",
    val description: String = "",
)
