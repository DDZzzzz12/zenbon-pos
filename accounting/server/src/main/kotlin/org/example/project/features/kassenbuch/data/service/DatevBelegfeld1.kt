package org.example.project.features.kassenbuch.data.service

import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader

/**
 * The document number a Kassenbuch entry carries in a DATEV export (Buchungsstapel Belegfeld 1,
 * and the Kassenbuch XML's last-resort `invoiceId`).
 *
 * Every cash book numbers its entries on its OWN sequence, so the Tresor and the Ladenkasse both
 * hold a `2026-05-0001`, and one Buchungsstapel covering both books carried the same Belegfeld 1
 * for two unrelated bookings. Entries of a **non-fiscal** book are therefore prefixed with a short
 * per-book tag — the first letter of the book's name plus its id, e.g. `T2-2026-05-0001` — which is
 * unique because the id is. The **fiscal** Ladenkasse keeps its plain number, so every existing
 * export of it, and the number in its TSE process data and DSFinV-K, are unchanged.
 *
 * Export-only: the stored `belegNr`, the signed process data and DSFinV-K never see the prefix.
 *
 * DATEV allows Belegfeld 1 at most 36 characters, digits, letters and `$ & % * + - /`
 * (Format_Buchungsstapel.xml field 11: Length 36, Text). The prefix is built from `A`–`Z`,
 * digits and `-` only. Should prefix + number ever exceed 36, the number's head is dropped, not
 * its tail: the running counter at the end is what makes the value unique.
 */
object DatevBelegfeld1 {
    const val MAX_LENGTH = 36

    /** Used when a book's name contains no letter `A`–`Z` at all (umlauts are skipped, not transliterated). */
    private const val FALLBACK_LETTER = 'K'

    fun of(header: KassenbuchHeader): String {
        val book = header.cashBook
        return of(belegNr = header.belegNr, book = Book(id = book.id.value, name = book.name, isFiscal = book.isFiscal))
    }

    /** The parts of a cash book the prefix is derived from — kept apart so the rule is testable without a DB. */
    data class Book(
        val id: Long,
        val name: String,
        val isFiscal: Boolean,
    )

    fun of(
        belegNr: String,
        book: Book,
    ): String {
        if (book.isFiscal) return belegNr
        val prefix = prefixOf(book)
        return prefix + belegNr.takeLast(MAX_LENGTH - prefix.length)
    }

    private fun prefixOf(book: Book): String {
        val letter = book.name.uppercase().firstOrNull { it in 'A'..'Z' } ?: FALLBACK_LETTER
        return "$letter${book.id}-"
    }
}
