package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType

/**
 * The Belegtext of a Kassensturz difference entry (FO-23).
 *
 * `kassenbuch_header.belegtext` is `varchar(60)`, and 60 is not arbitrary: the Belegtext is the
 * DATEV Buchungstext (XSD type p10060, see `DatevXmlFieldValidator`), so it cannot be widened.
 * The old text was `"Kassensturz <book>: <type>".take(60)`, which on a long book name cut the
 * entry type off entirely ("…Veranstaltungen S") — the one part of the text that says whether the
 * difference was a surplus or a shortage.
 *
 * Now the **book name** is what gets shortened, visibly, with a trailing ellipsis, so the prefix
 * and the entry type always survive. The full book name is still on the entry (`cash_book_id`) and
 * in the Eigenbeleg's description.
 */
internal object KassensturzBelegtext {
    const val MAX_LENGTH = 60
    private const val PREFIX = "Kassensturz "
    private const val SEPARATOR = ": "
    private const val ELLIPSIS = "…"

    fun of(
        bookName: String,
        entryType: KassenbuchEntryType,
    ): String {
        val name = bookName.trim()
        val fixedLength = PREFIX.length + SEPARATOR.length + entryType.displayName.length
        val roomForName = MAX_LENGTH - fixedLength
        val shownName =
            when {
                name.length <= roomForName -> name
                roomForName <= ELLIPSIS.length -> ELLIPSIS
                else -> name.take(roomForName - ELLIPSIS.length).trimEnd() + ELLIPSIS
            }
        return "$PREFIX$shownName$SEPARATOR${entryType.displayName}".take(MAX_LENGTH)
    }
}
