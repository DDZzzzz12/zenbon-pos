package org.example.project.features.supplier.data.service

/**
 * Legal-form tokens stripped before comparison. Lower-case, punctuation already removed.
 *
 * Deliberately conservative: only forms that carry no distinguishing information. "Cash", "Handel"
 * or "Nord" are NOT here — dropping them would merge different companies.
 */
private val LEGAL_FORMS =
    setOf(
        "gmbh",
        "ag",
        "kg",
        "ohg",
        "gbr",
        "ug",
        "se",
        "ek",
        "eg",
        "mbh",
        "co",
        "und",
        "and",
        "the",
        "ltd",
        "plc",
        "inc",
        "bv",
        "nv",
        "sa",
        "srl",
        "spa",
    )

/**
 * Punctuation that sits **inside** a word and is simply deleted, so `E.ON` becomes `eon` and
 * `O'Brien` becomes `obrien`.
 */
private val INTRA_WORD_PUNCTUATION = setOf('.', '\'', '’', '´', '`')

/**
 * Reduces a company name to a comparison key: lower-cased, punctuation resolved, whitespace
 * collapsed, legal-form tokens removed.
 *
 * Punctuation is handled in **two** ways, and the distinction matters:
 * - [INTRA_WORD_PUNCTUATION] is deleted, so `E.ON SE` and `EON` agree.
 * - Everything else non-alphanumeric becomes a **space**, so `Getränke-Müller` and
 *   `Getränke Müller`, or `Metro Cash & Carry` and `Metro Cash Carry`, agree.
 *
 * Deleting every punctuation mark uniformly would turn `Getränke-Müller` into `getränkemüller`
 * and split one creditor in two — which is exactly the duplicate this function exists to prevent,
 * and hyphenated names are common in German supplier lists.
 *
 * Umlauts are **preserved** — folding `ü` to `u` merges distinct companies more often than it
 * rescues an OCR variant. If every token is a legal form the input is returned lower-cased and
 * stripped instead of empty, because an empty key would match every other empty key.
 *
 * Pure: no I/O, no clock, no database. This is the last rung of the resolution ladder in
 * [SupplierResolver] and the value stored in `supplier.normalized_name`.
 */
fun normalizeSupplierName(raw: String): String {
    val cleaned =
        raw
            .lowercase()
            .filterNot { it in INTRA_WORD_PUNCTUATION }
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")

    val tokens = cleaned.split(' ').filter { it.isNotBlank() }
    val meaningful = tokens.filterNot { it in LEGAL_FORMS }

    return (if (meaningful.isEmpty()) tokens else meaningful).joinToString(" ")
}
