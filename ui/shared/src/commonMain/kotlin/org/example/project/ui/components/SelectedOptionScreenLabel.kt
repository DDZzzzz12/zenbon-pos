package org.example.project.ui.components

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.utils.optionLabel

/**
 * How one chosen option reads under its item ON SCREEN: `2× Groß (+1,50 €)` — the shop's money
 * format with a sign and the currency, one space before the bracket.
 *
 * The screen twin of the printed `printLabel` (`Groß  (1.50)`), which keeps the receipt's dot
 * convention. Both go through `optionLabel`, so the quantity prefix and the zero/unparseable
 * suppression are one definition and only the delta's spelling differs (QA emu22 NOTE-gg: the bill
 * panel showed the print spelling inside an otherwise comma-formatted German screen).
 */
fun OrderItemDto.SelectedOptionDto.screenLabel(currencyCode: String): String =
    optionLabel { delta -> " (${formatMoneyForDisplay(delta.toPlainString(), currencyCode, forceSign = true)})" }
