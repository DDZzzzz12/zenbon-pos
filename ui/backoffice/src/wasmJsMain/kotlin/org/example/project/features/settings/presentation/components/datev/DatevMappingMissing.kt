package org.example.project.features.settings.presentation.components.datev

import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.settings.presentation.state.DatevAccountMappingCellState
import org.example.project.features.settings.presentation.state.DatevAccountMappingState

/** Rate keys accepted per line kind (canonical VatRateNormalizer output). */
val DATEV_RATES_PER_KIND: Map<KassenbuchLineKind, List<String>> =
    mapOf(
        KassenbuchLineKind.STANDARD to listOf("19", "7", "0"),
        KassenbuchLineKind.PFAND to listOf("19"),
        KassenbuchLineKind.GUTSCHEIN to listOf("19", "7"),
    )

/** Stable id of an income/expense rate cell, used to find it again (scroll target, missing list). */
fun datevRateCellKey(
    isIncome: Boolean,
    kind: KassenbuchLineKind,
    rate: String,
): String = "${if (isIncome) "income" else "expense"}:${kind.name}:$rate"

fun datevPaymentCellKey(paymentTypeId: Long): String = "payment:$paymentTypeId"

fun datevSpecialCellKey(slot: KassenbuchSpecialBooking): String = "special:${slot.name}"

/** A cell lacks its account when automatic assignment is on and the Konto field is blank. */
fun DatevAccountMappingCellState.isKontoMissing(): Boolean = autoAssign && konto.isBlank()

/**
 * Ids of every cell still lacking a Konto, in the order the screen shows them: income rate cells,
 * expense rate cells, payment types, special bookings. A rate cell absent from the state counts as
 * a default cell (automatic on, blank), exactly how the screen renders it.
 */
fun missingKontoCells(state: DatevAccountMappingState): List<String> =
    buildList {
        for (isIncome in listOf(true, false)) {
            val side = if (isIncome) state.income else state.expense
            DATEV_RATES_PER_KIND.forEach { (kind, rates) ->
                rates.forEach { rate ->
                    val cell = side[kind]?.get(rate) ?: DatevAccountMappingCellState()
                    if (cell.isKontoMissing()) add(datevRateCellKey(isIncome, kind, rate))
                }
            }
        }
        state.paymentTypes.forEach { row ->
            if (row.cell.isKontoMissing()) add(datevPaymentCellKey(row.paymentTypeId))
        }
        KassenbuchSpecialBooking.entries.forEach { slot ->
            val cell = state.special[slot] ?: DatevAccountMappingCellState()
            if (cell.isKontoMissing()) add(datevSpecialCellKey(slot))
        }
    }

fun missingKontoCount(state: DatevAccountMappingState): Int = missingKontoCells(state).size

/**
 * Payment types arrive upper-cased from older seeds (`EC-KARTE`). When the whole name is upper-case
 * it is shown capitalised per word (`EC-Karte`; one- and two-letter words such as `EC` are kept as
 * abbreviations). A name that already mixes cases (`PayPal`) or has no cased letters (CJK) is left alone.
 */
fun displayPaymentTypeName(name: String): String {
    val allUpper = name.any { it.isUpperCase() } && name.none { it.isLowerCase() }
    if (!allUpper) return name
    val result = StringBuilder(name.length)
    var index = 0
    while (index < name.length) {
        if (!name[index].isLetter()) {
            result.append(name[index])
            index++
            continue
        }
        var end = index
        while (end < name.length && name[end].isLetter()) end++
        val word = name.substring(index, end)
        if (word.length <= 2) {
            result.append(word)
        } else {
            result.append(word.first().uppercaseChar())
            result.append(word.substring(1).lowercase())
        }
        index = end
    }
    return result.toString()
}
