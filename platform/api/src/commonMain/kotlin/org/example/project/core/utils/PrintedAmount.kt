package org.example.project.core.utils

import org.example.project.money.Decimal
import org.example.project.money.MoneyFormat
import org.example.project.money.format

/**
 * German number form for amounts a person reads on paper: comma decimal separator, dot grouping,
 * two decimals, ASCII minus — [MoneyFormat.DE_EUR] without its symbol, because the symbol depends
 * on what the target can render (an ESC/POS CP850 page has no `€`).
 */
private val PRINTED_AMOUNT_FORMAT: MoneyFormat = MoneyFormat.DE_EUR.copy(symbol = null)

/**
 * A wire amount (`"1234.5"`, `"-11.5000"`) as a printed receipt, Z-Bon or PDF shows it to a
 * German reader: `"1.234,50"`, `"-11,50"`.
 *
 * Display only. The TSE block, process data, QR payloads, DSFinV-K and DATEV all keep the dot
 * form — never feed this result into any of them. A value that is not a decimal string is returned
 * unchanged rather than failing the print.
 */
fun printedAmount(amount: String): String = Decimal.parseOrNull(amount.trim())?.format(PRINTED_AMOUNT_FORMAT) ?: amount
