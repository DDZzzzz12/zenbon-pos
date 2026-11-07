package org.example.project.core.utils

/**
 * Maps a free-text country as operators type it — `Germany`, `Deutschland`, `de`, `DEU` — onto its
 * ISO 3166-1 alpha-2 code (`DE`).
 *
 * `shop_info.shop_country` and the invoice buyer's country are free text, and the setup wizard's
 * default is the English word "Germany". A human reads that fine; an EN 16931 e-invoice does not —
 * `CountryID` (BT-40/BT-55) must be the alpha-2 code, and a validator rejects anything else. This is
 * the one place that translation happens, so the e-invoice writer, and any later caller, agree.
 *
 * Deliberately conservative: an input it does not recognise returns `null` rather than a guess, so
 * the caller can refuse with a sentence instead of emitting a wrong code. A two-letter input is
 * accepted only when it is an assigned alpha-2 code, so a typo such as `DX` is not waved through.
 */
object CountryCodeNormalizer {
    /** @return the upper-case alpha-2 code, or null when [raw] is blank or not recognised. */
    fun toIso2(raw: String?): String? {
        val key = raw?.let(::normalizeKey)?.takeIf { it.isNotEmpty() } ?: return null
        val upper = key.uppercase()
        if (upper.length == 2 && upper in ALPHA_2) return upper
        return NAMES[key]
    }

    /** Lower-case, trimmed, inner whitespace collapsed, dots dropped (`U.S.A.` → `usa`). */
    private fun normalizeKey(raw: String): String =
        raw
            .trim()
            .lowercase()
            .replace(".", "")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Every assigned ISO 3166-1 alpha-2 code. */
    private val ALPHA_2: Set<String> =
        (
            "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR " +
                "BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO " +
                "DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT " +
                "GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP " +
                "KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR " +
                "MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN " +
                "PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX " +
                "SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN " +
                "VU WF WS YE YT ZA ZM ZW"
        ).split(' ').toSet()

    /**
     * German and English names (with and without umlauts) and alpha-3 codes of the countries a
     * German restaurant's customers realistically come from: the EU, the EEA, Switzerland, the UK
     * and the US. Keys are in [normalizeKey] form.
     */
    private val NAMES: Map<String, String> =
        buildMap {
            fun add(
                code: String,
                vararg names: String,
            ) = names.forEach { put(it, code) }

            add("DE", "deutschland", "germany", "bundesrepublik deutschland", "brd", "deu", "ger")
            add("AT", "österreich", "oesterreich", "osterreich", "austria", "aut")
            add("CH", "schweiz", "switzerland", "suisse", "svizzera", "che")
            add("LI", "liechtenstein", "lie")
            add("LU", "luxemburg", "luxembourg", "lux")
            add("BE", "belgien", "belgium", "belgique", "bel")
            add("NL", "niederlande", "netherlands", "the netherlands", "holland", "nederland", "nld")
            add("FR", "frankreich", "france", "fra")
            add("IT", "italien", "italy", "italia", "ita")
            add("ES", "spanien", "spain", "españa", "espana", "esp")
            add("PT", "portugal", "prt")
            add("DK", "dänemark", "daenemark", "danemark", "denmark", "danmark", "dnk")
            add("SE", "schweden", "sweden", "sverige", "swe")
            add("FI", "finnland", "finland", "fin")
            add("NO", "norwegen", "norway", "norge", "nor")
            add("IS", "island", "iceland", "isl")
            add("IE", "irland", "ireland", "irl")
            add("PL", "polen", "poland", "polska", "pol")
            add("CZ", "tschechien", "tschechische republik", "czechia", "czech republic", "cze")
            add("SK", "slowakei", "slovakia", "svk")
            add("HU", "ungarn", "hungary", "hun")
            add("SI", "slowenien", "slovenia", "svn")
            add("HR", "kroatien", "croatia", "hrv")
            add("RO", "rumänien", "rumaenien", "romania", "rou")
            add("BG", "bulgarien", "bulgaria", "bgr")
            add("GR", "griechenland", "greece", "grc")
            add("CY", "zypern", "cyprus", "cyp")
            add("MT", "malta", "mlt")
            add("EE", "estland", "estonia", "est")
            add("LV", "lettland", "latvia", "lva")
            add("LT", "litauen", "lithuania", "ltu")
            add("GB", "vereinigtes königreich", "vereinigtes koenigreich", "großbritannien", "grossbritannien")
            add("GB", "united kingdom", "great britain", "uk", "gbr")
            add("US", "vereinigte staaten", "usa", "united states", "united states of america", "amerika")
            add("TR", "türkei", "tuerkei", "turkey", "türkiye", "turkiye", "tur")
        }
}
