package org.example.project.export.dsfinvk.builder

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * m2: `formatDecimal(String?)` was a bare `value.replace(".", ",")`.
 *
 * Every current caller feeds it a value already produced by `toDecimalString()` from a BigDecimal,
 * so the string is well-formed — but nothing enforced that. A future caller passing a grouped
 * (`1.234.56`) or exponent (`1.0E+10`) string would have it written VERBATIM into a column
 * `index.xml` declares `<Numeric>`: `1.234.56` → `1,234,56` (two commas, unparseable as a decimal),
 * `1.0E+10` → `1,0E+10` (a letter in a numeric field). Both silently corrupt the export.
 *
 * A `require` on the accepted shape keeps the guarantee the callers already satisfy, and turns a
 * future mistake into a loud failure at the point of the bad value rather than a malformed cell an
 * importer chokes on.
 */
@DisplayName("formatDecimal validates its input shape (m2)")
class DsfinvkCsvUtilsFormatDecimalTest {
    @Test
    fun `well-formed decimals convert to German comma form`() {
        assertEquals("13,43", DsfinvkCsvUtils.formatDecimal("13.43"))
        assertEquals("-2,00", DsfinvkCsvUtils.formatDecimal("-2.00"))
        assertEquals("1000", DsfinvkCsvUtils.formatDecimal("1000"))
        assertEquals("0,000", DsfinvkCsvUtils.formatDecimal("0.000"))
    }

    @Test
    fun `null and blank stay empty`() {
        assertEquals("", DsfinvkCsvUtils.formatDecimal(null))
        assertEquals("", DsfinvkCsvUtils.formatDecimal(""))
    }

    @Test
    fun `a grouped thousands string is rejected, not silently double-comma'd`() {
        // "1.234.56" would become "1,234,56" — two decimal commas, unparseable.
        assertFailsWith<IllegalArgumentException> { DsfinvkCsvUtils.formatDecimal("1.234.56") }
    }

    @Test
    fun `an exponent string is rejected before a letter reaches a Numeric column`() {
        assertFailsWith<IllegalArgumentException> { DsfinvkCsvUtils.formatDecimal("1.0E+10") }
    }

    @Test
    fun `a comma-bearing input is rejected — the caller must pass dot-decimal`() {
        // If a value already carries a comma, replace(".",",") leaves the comma untouched and the
        // real error (wrong upstream formatter) hides. Reject it.
        assertFailsWith<IllegalArgumentException> { DsfinvkCsvUtils.formatDecimal("13,43") }
    }
}
