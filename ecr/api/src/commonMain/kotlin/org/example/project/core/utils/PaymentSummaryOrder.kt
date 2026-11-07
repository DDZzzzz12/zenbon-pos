package org.example.project.core.utils

import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.setup.Language

/**
 * A payment-type name folded for dictionary order: case-insensitive, and German/Latin letters with
 * a diacritic sort with their base letter (DIN 5007-1: `Ä` with `A`, `ß` as `ss`), so "Überweisung"
 * sits among the U's rather than after "Z". Characters outside the table (Chinese names among them)
 * keep their code-point order; there is no collator in common Kotlin.
 */
fun paymentNameSortKey(name: String): String =
    buildString(name.length) {
        name.trim().lowercase().forEach { c -> append(FOLDED[c] ?: c.toString()) }
    }

/**
 * The payments of a summary strip in a stable order: by the name shown in [language] (see
 * [getLocalizedName]), dictionary-folded via [paymentNameSortKey], ties by the shown name itself and
 * then by the German master name. The server's grouping order is not stable between loads, and the
 * strip colours its dots by position, so without this the same payment type changed colour on reload.
 */
fun List<PaymentDto.PaymentSummary>.sortedForDisplay(language: Language): List<PaymentDto.PaymentSummary> =
    sortedWith(
        compareBy<PaymentDto.PaymentSummary> { paymentNameSortKey(it.getLocalizedName(language)) }
            .thenBy { it.getLocalizedName(language) }
            .thenBy { it.paymentTypeDE },
    )

private val FOLDED: Map<Char, String> =
    buildMap {
        "àáâãäåā".forEach { put(it, "a") }
        "çćč".forEach { put(it, "c") }
        "èéêëēė".forEach { put(it, "e") }
        "ìíîïī".forEach { put(it, "i") }
        "ñń".forEach { put(it, "n") }
        "òóôõöøō".forEach { put(it, "o") }
        "ùúûüū".forEach { put(it, "u") }
        "ýÿ".forEach { put(it, "y") }
        "šś".forEach { put(it, "s") }
        "žźż".forEach { put(it, "z") }
        put('ß', "ss")
        put('æ', "ae")
        put('œ', "oe")
    }
