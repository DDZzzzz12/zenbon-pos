package org.example.project.features.matching.data.service

/**
 * Document-kind tokens a German payer writes in front of the number itself: `RE 2026-0001`,
 * `RG-2026-0001`, `Nr. 2026-0001`, `Rechnung 2026-0001`.
 *
 * Deliberately short. Each of these says only "this is an invoice number" and carries no
 * identifying information, so removing one cannot merge two different invoices. Anything that
 * *might* be part of the number — a supplier's own `KD`, `AB`, `LS` or branch prefix — is left
 * alone, because stripping it would collide two invoices and produce a *wrong* suggestion.
 *
 * Longest first: the regex alternation is ordered, and `re` would otherwise swallow the start of
 * `rechnung` and leave a stray `chnung`.
 */
private val REFERENCE_PREFIXES = listOf("rechnung", "nr", "rg", "re")

/**
 * A leading document-kind token, but only when it is not glued to further letters.
 *
 * The negative lookahead is the conservative half: `RE-2026-0001` and `RE2026` shed their prefix,
 * while `REF123` keeps it, because `ref` is far more likely to be the number's own prefix than a
 * German abbreviation for *Rechnung*. Over-stripping here would map `REF123` and `F123` onto the
 * same key — two different invoices, one confident wrong answer.
 */
private val PREFIX_REGEX = Regex("^(${REFERENCE_PREFIXES.joinToString("|")})(?![a-z])")

/**
 * Reduces an invoice reference — from an invoice number, a `payment_reference`, or whatever a payer
 * typed into a transfer — to the key signals 1 and 2 compare on.
 *
 * Four transformations, in an order that matters:
 * 1. **Lower-case and trim.** `re-2026-0001` and `  RE-2026-0001 ` are the same reference.
 * 2. **Strip a leading document-kind token** ([REFERENCE_PREFIXES]). This runs *before* separators
 *    are deleted: once `RE-4711` has become `re4711` the prefix is no longer at a token boundary
 *    and cannot be recognised at all.
 * 3. **Delete every non-alphanumeric character.** Payers insert spaces, hyphens, slashes and dots
 *    for readability; none of them distinguish one invoice from another.
 * 4. **Drop leading zeros** — *after* step 3, so that `2026-0001` and `20260001` agree. Doing it
 *    before would leave `2026` and `0001` as separate runs and the two would not meet.
 *
 * What is deliberately **kept**: a trailing letter (`RE-2026-0001A` is a corrected invoice, a
 * different document), internal digits in full, and umlauts. Anything that could distinguish two
 * invoices survives.
 *
 * Returns `null` when nothing identifying is left — an empty string, punctuation only, or a bare
 * `RE` with no number after it. A null key must never be compared as equal to another null key.
 *
 * Pure: no I/O, no clock, no database.
 */
fun normalizeInvoiceReference(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase() ?: return null
    if (trimmed.isEmpty()) return null

    val withoutPrefix = PREFIX_REGEX.replace(trimmed, "")
    val alphanumeric = withoutPrefix.filter { it.isLetterOrDigit() }
    if (alphanumeric.isEmpty()) return null

    // An all-zero reference collapses to "0" rather than to nothing: it is a poor invoice number,
    // but it is still a value the payer and the invoice can agree on.
    val withoutLeadingZeros = alphanumeric.dropWhile { it == '0' }
    return withoutLeadingZeros.ifEmpty { "0" }
}
