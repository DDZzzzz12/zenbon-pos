package org.example.project.features.financialdocuments.presentation.state

import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind

/**
 * State for a single line in the Kassenbuch entry form.
 *
 * @property kind Line classification (STANDARD/PFAND/GUTSCHEIN — NON_CASH_PAYMENT is never offered).
 * @property vatRate VAT rate as a string ("0", "7", "19"); always editable, PFAND merely defaults to 19.
 * @property amount Signed amount as printed on the Beleg (e.g. "-19.11").
 */
data class EntryLineState(
    val kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
    val vatRate: String = "19",
    val amount: String = "",
)
