package org.example.project.admin.core.presentation.format

import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The currency's display symbol (`EUR` → `€`), for an input field's suffix. Taken from the shared
 * money formatter so the symbol can never disagree with what the same amount shows read-only; a blank
 * code falls back to euro, like the read-only bank-statement fields do.
 */
fun currencySymbolForDisplay(currencyCode: String): String = formatMoneyForDisplay("0", currencyCode.ifBlank { "EUR" }).removePrefix("0,00").trim()
