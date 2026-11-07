package org.example.project.admin.core.presentation.theme

import org.example.project.admin.core.presentation.strings.translations.ChineseAdminStrings
import org.example.project.admin.core.presentation.strings.translations.EnglishAdminStrings
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.keys.AddPaymentKeys
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every string adminApp registers must be drawable by its font subset
 * (`pos-server/.../static/font/NotoSansSC-GB2312-v2.otf`, applied to the whole type scale by
 * `AdminTheme`). Compose does not fall back by glyph coverage, so a codepoint the subset lacks draws
 * as **nothing** — no tofu, no warning. That is how "€" vanished from every amount (2026-08-27) and
 * how the "−" (U+2212) in the Berichte reconciliation line vanished (QA admin19 NOTE-a).
 *
 * [COVERED] is the subset's cmap outside the CJK ideograph blocks, read from the shipped file; it is
 * exact, not the `--unicodes` ranges from the README, which over-claim (the source font has holes in
 * them). CJK ideographs are allowed wholesale — the subset holds the 6,763 GB2312 hanzi, so a rare
 * character outside GB2312 can still slip through; that is the README's documented trade-off.
 *
 * When this fails: prefer an ASCII (or otherwise covered) character, as the README says. Widening the
 * subset means regenerating it under a NEW filename and updating [COVERED] from its cmap.
 */
class FontSubsetCoverageTest {
    @Test
    fun everyRegisteredTranslation_isDrawableByTheFontSubset() {
        val base = defaultTranslations()
        val all =
            mapOf(
                Language.ENGLISH to base[Language.ENGLISH].orEmpty() + EnglishAdminStrings.strings,
                Language.GERMAN to base[Language.GERMAN].orEmpty() + GermanAdminStrings.strings,
                Language.CHINESE to base[Language.CHINESE].orEmpty() + ChineseAdminStrings.strings,
            )
        val offenders =
            all.flatMap { (language, strings) ->
                strings
                    .filterKeys { it !in TILL_ONLY_KEYS }
                    .mapNotNull { (key, text) ->
                        val missing = text.codepoints().filterNot(::isCovered).distinct()
                        if (missing.isEmpty()) {
                            null
                        } else {
                            "$language ${key.id}: " + missing.joinToString { "U+" + it.toString(16).uppercase().padStart(4, '0') }
                        }
                    }
            }
        assertTrue(offenders.isEmpty(), "glyphs missing from adminApp's font subset:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun minusSign_isNotCovered_soTheGuardWouldCatchIt() {
        // Negative control: the guard is only worth something if it refuses the glyph that broke.
        assertTrue(!isCovered(0x2212))
        assertTrue(isCovered(0x20AC))
        assertTrue(isCovered('-'.code))
    }

    private fun String.codepoints(): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                out += 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
                i += 2
            } else {
                out += c.code
                i += 1
            }
        }
        return out
    }

    private fun isCovered(cp: Int): Boolean =
        cp == '\n'.code ||
            cp in 0x4E00..0x9FFF ||
            COVERED.any { cp in it }

    private companion object {
        /**
         * Keys only the till (composeApp, system font) renders. They are registered in adminApp
         * because the translation maps are shared wholesale, but no adminApp screen shows them.
         */
        val TILL_ONLY_KEYS: Set<StringKey> =
            setOf(
                EmployeeHubKeys.hideDetails,
                AddPaymentKeys.voucherValidTitle,
                AddPaymentKeys.autoTipLabel,
            )

        /** `NotoSansSC-GB2312-v2.otf` cmap minus U+3400–4DBF / U+4E00–9FFF, as contiguous runs. */
        val COVERED: List<IntRange> =
            listOf(
                0x0020..0x007E,
                0x00A0..0x00FF,
                0x0101..0x0101,
                0x0113..0x0113,
                0x011B..0x011B,
                0x012B..0x012B,
                0x014D..0x014D,
                0x016B..0x016B,
                0x01CE..0x01CE,
                0x01D0..0x01D0,
                0x01D2..0x01D2,
                0x01D4..0x01D4,
                0x01D6..0x01D6,
                0x01D8..0x01D8,
                0x01DA..0x01DA,
                0x01DC..0x01DC,
                0x02C7..0x02C7,
                0x02C9..0x02C9,
                0x0391..0x03A1,
                0x03A3..0x03A9,
                0x03B1..0x03C1,
                0x03C3..0x03C9,
                0x0401..0x0401,
                0x0410..0x044F,
                0x0451..0x0451,
                0x2002..0x2003,
                0x2010..0x2016,
                0x2018..0x201A,
                0x201C..0x201E,
                0x2020..0x2022,
                0x2025..0x2027,
                0x2030..0x2030,
                0x2032..0x2033,
                0x2035..0x2035,
                0x2039..0x203C,
                0x2042..0x2042,
                0x2047..0x2049,
                0x2051..0x2051,
                0x20A9..0x20A9,
                0x20AB..0x20AC,
                0x2103..0x2103,
                0x2116..0x2116,
                0x2160..0x216B,
                0x2190..0x2193,
                0x2208..0x2208,
                0x220B..0x220B,
                0x220F..0x220F,
                0x2211..0x2211,
                0x221A..0x221A,
                0x221D..0x221E,
                0x2220..0x2220,
                0x2225..0x2225,
                0x2227..0x222B,
                0x222E..0x222E,
                0x2234..0x2237,
                0x223D..0x223D,
                0x2245..0x2245,
                0x2248..0x2248,
                0x224C..0x224C,
                0x2260..0x2261,
                0x2264..0x2265,
                0x226E..0x226F,
                0x2299..0x2299,
                0x22A5..0x22A5,
                0x2312..0x2312,
                0x2460..0x2469,
                0x2474..0x249B,
                0x2500..0x254B,
                0x25A0..0x25A1,
                0x25B2..0x25B3,
                0x25C6..0x25C7,
                0x25CB..0x25CB,
                0x25CE..0x25CF,
                0x2605..0x2606,
                0x2640..0x2640,
                0x2642..0x2642,
                0x3000..0x303F,
                0x3041..0x3093,
                0x30A1..0x30F6,
                0x30FB..0x30FB,
                0x3105..0x3129,
                0x3220..0x3229,
                0xFF01..0xFF9F,
                0xFFA1..0xFFBE,
                0xFFC2..0xFFC7,
                0xFFCA..0xFFCF,
                0xFFD2..0xFFD7,
                0xFFDA..0xFFDC,
                0xFFE0..0xFFE6,
                0xFFE8..0xFFEE,
            )
    }
}
