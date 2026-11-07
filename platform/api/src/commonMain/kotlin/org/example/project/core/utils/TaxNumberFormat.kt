package org.example.project.core.utils

/**
 * The **comparison key** for a tax number (Steuernummer) stored on `business_partner.tax_number`.
 *
 * This exists for the reason `VatIdFormat.canonical` and `normalizeSupplierName` exist: the column
 * is matched with an exact `eq` on the supplier resolver's second rung, so every writer and every
 * reader has to compute the key the same way, and the only way to guarantee that is to compute it
 * in one place. The tax number was the rung that never got the treatment — the creditor paths
 * stored `takeIf { isNotBlank() }` while the debtor paths trimmed, so a creditor captured as
 * `" 12/345/67890 "` and a debtor captured from the identical keystrokes stored two different
 * strings and could not find each other. XML text nodes routinely carry surrounding whitespace, so
 * a parsed e-invoice reaches this with the padded form too.
 *
 * **Separators are stripped, not just whitespace.** A German Steuernummer is written both
 * `12/345/67890` and `1234567890`, and those are one number, not two — the slashes are the tax
 * office's grouping, exactly as spaces are on a VAT id. Stripping them cannot merge two genuinely
 * different tax numbers: for that they would have to agree digit for digit, which makes them the
 * same number. So the key keeps letters and digits — some non-German tax numbers are alphanumeric —
 * and drops everything else.
 *
 * `uppercase()` without a locale is already the locale-independent form in Kotlin (the
 * locale-sensitive variant is the explicit `uppercase(Locale)` overload), so a Turkish default
 * locale cannot turn `i` into `ı` here.
 */
object TaxNumberFormat {
    fun canonical(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()
}
