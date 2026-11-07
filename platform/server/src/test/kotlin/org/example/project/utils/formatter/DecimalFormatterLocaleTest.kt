package org.example.project.utils.formatter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals

/**
 * Locale-independence regression test for the [Double] overload of [formatDecimal] /
 * [toDecimalString].
 *
 * The `Double` overload builds its output with a [java.text.DecimalFormat]. Before the fix it used
 * the JVM default-locale symbols, so under a German default locale ([Locale.GERMANY]) it emitted a
 * COMMA decimal separator ("13,43" instead of "13.43"). Any such value that reaches a
 * fiscal/exported/TSE-signed string — DSFinV-K CSVs, DATEV exports, TSE ProcessData, receipts, QR
 * codes — would then be corrupted or signed with the wrong separator depending on where the server
 * happened to run. The overload now pins [Locale.ROOT] symbols; this test locks that in by running
 * the same inputs under [Locale.GERMANY] and [Locale.ROOT] and asserting byte-identical output.
 *
 * The [java.math.BigDecimal] overload has always been locale-independent (it uses
 * `setScale(...).toString()`), so it is not exercised here.
 */
@DisplayName("formatDecimal(Double) — locale independence")
class DecimalFormatterLocaleTest {
    private lateinit var originalLocale: Locale

    @BeforeEach
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /** Representative fiscal amounts, including rounding-boundary and zero cases. */
    private val samples = listOf(3.4, 13.4255, 0.0, 99.999, 1234.56, 16.5, -75.25)

    @Test
    fun `formatDecimal(Double) always uses a dot under a German default locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("13.43", formatDecimal(13.4255))
        assertEquals("3.40", formatDecimal(3.4))
        assertEquals("0.00", formatDecimal(0.0))
        assertEquals("100.00", formatDecimal(99.999))
        assertEquals("-75.25", formatDecimal(-75.25))
        // extension form routes through the same function
        assertEquals("1234.56", 1234.56.toDecimalString())
    }

    @Test
    fun `formatDecimal(Double) output is identical under Locale_GERMANY and Locale_ROOT`() {
        val underGermany =
            samples.map {
                Locale.setDefault(Locale.GERMANY)
                formatDecimal(it)
            }
        val underRoot =
            samples.map {
                Locale.setDefault(Locale.ROOT)
                formatDecimal(it)
            }
        assertEquals(underRoot, underGermany)
        // and neither variant may contain a comma
        underGermany.forEach { assertEquals(false, it.contains(','), "unexpected comma in \"$it\"") }
    }
}
