package org.example.project.core.utils

/**
 * Format guard for the EU VAT identification number (USt-IdNr) exported as DSFinV-K `USTID`.
 *
 * This exists because audit finding STAM-8/KABS-9 was exactly the mistake this rejects: a German
 * Steuernummer entered where a § 27a UStG USt-IdNr belongs. The two are alternatives in the export
 * (STNR or USTID), so a Steuernummer sitting in USTID satisfies a gate that should have demanded a
 * real STNR — which is why a wrong value here is worse than an absent one.
 *
 * Deliberately shape-only, not a registry check: it rejects the confusable mistake without
 * pretending to confirm the number is issued. A general EU VAT ID is a 2-letter country code
 * followed by 2-13 alphanumerics; the DE form is additionally pinned to exactly 9 digits.
 */
object VatIdFormat {
    /** General EU shape: ISO country prefix + alphanumeric body, within the column's 15 chars. */
    private val GENERAL = Regex("^[A-Za-z]{2}[A-Za-z0-9]{2,13}$")

    /** § 27a UStG: `DE` + exactly 9 digits. A Steuernummer (10-13 digits, often slashed) fails this. */
    private val GERMAN = Regex("^[Dd][Ee][0-9]{9}$")

    /**
     * The **comparison key** for a VAT id: all whitespace removed, upper-cased.
     *
     * `business_partner.vat_id` is matched with an exact `eq` — it is the resolver's first and most
     * trusted rung — so the value every writer stores has to be computed here and nowhere else.
     * Both `de 123 456 789`, as a human types it off a letterhead, and `DE123456789`, as a
     * structured e-invoice carries it, are one identifier; storing them apart duplicates the
     * company, which is the precise failure the business-partner model exists to prevent. That
     * defect was live once already on `normalized_name`, for the same reason: two paths each
     * keeping their own copy of the rule.
     *
     * The lookup side canonicalizes too. Half a fix is no fix — a stored canonical key is still
     * unreachable from a raw search string.
     *
     * `uppercase()` without a locale is already the locale-independent form in Kotlin (the
     * locale-sensitive variant is the explicit `uppercase(Locale)` overload), so a Turkish default
     * locale cannot turn `i` into `ı` here.
     */
    fun canonical(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()

    /**
     * Rejects [value] when it is present but not a plausible USt-IdNr. Null and blank pass — the
     * field is optional, and absent is the honest state for a shop that has none.
     *
     * @throws IllegalArgumentException which the shop-info service converts to a 400 ProblemDetail.
     */
    fun requireValid(
        value: String?,
        fieldName: String = "VAT ID (USTID)",
    ) {
        val v = value?.trim() ?: return
        if (v.isEmpty()) return
        require(GENERAL.matches(v)) {
            "$fieldName must be a VAT identification number: a 2-letter country code followed by " +
                "2-13 alphanumeric characters, e.g. DE123456789. Was: \"$v\""
        }
        if (v.take(2).uppercase() == "DE") {
            require(GERMAN.matches(v)) {
                "$fieldName starting with DE must be a § 27a UStG USt-IdNr — DE followed by exactly " +
                    "9 digits. A Steuernummer is not a USt-IdNr; leave this empty and set the tax " +
                    "number instead. Was: \"$v\""
            }
        }
    }
}
