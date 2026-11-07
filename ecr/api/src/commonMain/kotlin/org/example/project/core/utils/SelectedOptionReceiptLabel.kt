package org.example.project.core.utils

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.money.Decimal

/**
 * How one chosen option (choice-group selection or legacy extra) reads under its item on a PRINTED
 * receipt: `2× Groß  (1,50)` — the quantity only above one, the price delta only when it is not
 * zero, two spaces before it and written by [printedAmount] like every other printed amount
 * (German comma since F63; it used to be `(1.50)`).
 *
 * The single definition for paper and PDF: the PDF receipt and reference print listed the options
 * without their deltas while paper showed them (QA emu21 NOTE-y). The wire carries the DB scale
 * (`"1.5000"`); an unparseable delta prints no suffix rather than a wrong one.
 *
 * **Not for screens.** A German screen formats money with a currency symbol, which a CP850
 * printer cannot render (QA emu22 NOTE-gg). Screens use shared-ui's
 * `screenLabel`, which goes through [optionLabel] too, so the two differ only in how the delta is
 * written and cannot drift on what is shown.
 */
fun OrderItemDto.SelectedOptionDto.printLabel(): String = optionLabel { delta -> "  (${printedAmount(delta.toPlainString())})" }

/**
 * The structure every option label shares, print or screen: `[n× ]name[suffix]`, the quantity
 * prefix only above one, the suffix only for a parseable non-zero delta. [deltaSuffix] writes the
 * delta, including whatever separates it from the name.
 */
fun OrderItemDto.SelectedOptionDto.optionLabel(deltaSuffix: (Decimal) -> String): String {
    val qtyPrefix = if (quantity > 1) "$quantity× " else ""
    val suffix =
        Decimal
            .parseOrNull(priceDelta.trim())
            ?.takeIf { !it.isZero }
            ?.let(deltaSuffix)
            .orEmpty()
    return qtyPrefix + name + suffix
}
