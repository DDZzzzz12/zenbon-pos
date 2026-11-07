package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.money.Decimal

/** One VAT line of the editor's Summen: the [rate] in percent units (`19.00`) and the VAT [vat] it contributes. */
data class SalesTotalsVatRow(
    val rate: String,
    val vat: Decimal,
)
