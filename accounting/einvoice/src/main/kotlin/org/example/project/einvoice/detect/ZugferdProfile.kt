package org.example.project.einvoice.detect

/**
 * ZUGFeRD / Factur-X conformance levels.
 *
 * [MINIMUM] and [BASIC_WL] are deliberately **not** EN 16931 invoices — they carry only booking
 * data and are published as accounting aids. Treating them as received e-invoices would misreport
 * mandate compliance.
 *
 * ## Where these URN strings come from
 *
 * All six are the ZUGFeRD 2.1+ / Factur-X 1.0.x spelling, cross-checked byte-for-byte (2026-08-09)
 * against three independent implementations that agree exactly:
 *  - Mustangproject (Java, Apache-2.0) — `library/src/main/java/org/mustangproject/ZUGFeRD/Profiles.java`,
 *    <https://github.com/ZUGFeRD/mustangproject>
 *  - ZUGFeRD-csharp (C#, different author) — `ZUGFeRD/Profile.cs`,
 *    <https://github.com/stephanstapel/ZUGFeRD-csharp>
 *  - FacturX API's URN reference, <https://facturxapi.com/docs/urns-schemas-factur-x>
 *
 * Two spellings that are easy to get wrong and are therefore pinned by fixtures:
 *  - [BASIC] is `#compliant#` (a CIUS — a *restriction* of EN 16931); [EXTENDED] is `#conformant#`
 *    (an *extension*). The words are not interchangeable.
 *  - ZUGFeRD 2.0 used a `urn:zugferd.de:2p0:*` infix (e.g. `urn:zugferd.de:2p0:basicwl`). ZUGFeRD 2.1
 *    (March 2020) replaced it with `urn:factur-x.eu:1p0:*` and every later release kept that, so the
 *    2.0 forms are **not** declared here. Add them only if a real 2.0 file has to be ingested.
 */
enum class ZugferdProfile(
    val urn: String,
    val isEn16931Conformant: Boolean,
) {
    MINIMUM("urn:factur-x.eu:1p0:minimum", false),
    BASIC_WL("urn:factur-x.eu:1p0:basicwl", false),
    BASIC("urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basic", true),
    EN16931("urn:cen.eu:en16931:2017", true),
    EXTENDED("urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended", true),
    XRECHNUNG("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0", true),
    ;

    companion object {
        /** Matches a trailing `_<major>.<minor>` version suffix, e.g. the `_3.0` in `xrechnung_3.0`. */
        private val TRAILING_VERSION = Regex("_\\d+\\.\\d+$")

        /**
         * Real XRechnung files declare the EN 16931 URN with a national-extension suffix that moves
         * between releases. So an exact match is tried first, then the **longest** matching prefix.
         *
         * Longest-first matters: `EN16931`'s URN is itself a prefix of `XRECHNUNG`'s, so a
         * declaration-order scan would classify every XRechnung as plain EN16931.
         *
         * The prefix fallback compares against a *version-stripped* form of each candidate (trailing
         * `_3.0`-style suffix removed), not the literal stored URN. A literal-string `startsWith`
         * would only ever match the exact version baked into the constant — a `_3.1` upgrade diverges
         * at the very last character and would fall through to the `EN16931` prefix instead of
         * `XRECHNUNG`. Stripping the version keeps that from happening while leaving the exact-match
         * branch (and therefore the fixture-verified constants) untouched.
         */
        fun fromUrn(urn: String): ZugferdProfile? =
            entries.firstOrNull { it.urn.equals(urn, ignoreCase = true) }
                ?: entries
                    .filter { urn.startsWith(it.urn.replace(TRAILING_VERSION, "_"), ignoreCase = true) }
                    .maxByOrNull { it.urn.length }
    }
}
