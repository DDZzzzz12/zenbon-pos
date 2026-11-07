package org.example.project.core.data.local

import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecimalColumnAdapterTest {
    @Test
    fun encodesAsAnExactPlainString() {
        assertEquals("16.90", DecimalColumnAdapter.encode(Decimal.parse("16.90")))
        assertEquals("16.9000", DecimalColumnAdapter.encode(Decimal.parse("16.9000")))
        assertEquals("0", DecimalColumnAdapter.encode(Decimal.ZERO))
    }

    @Test
    fun decodeIsTheInverseOfEncode() {
        for (text in listOf("16.90", "16.9000", "0", "0.00", "-3.50", "999999.9999")) {
            val decoded = DecimalColumnAdapter.decode(DecimalColumnAdapter.encode(Decimal.parse(text)))
            assertEquals(text, decoded.toPlainString(), "round trip changed $text")
        }
    }

    @Test
    fun scaleSurvivesTheRoundTrip() {
        // The DB column must not silently collapse DECIMAL(19,4) precision to 2 places.
        assertEquals(4, DecimalColumnAdapter.decode("16.9000").scale)
        assertEquals(2, DecimalColumnAdapter.decode("16.90").scale)
    }

    @Test
    fun aCorruptStoredValueFailsLoudly() {
        // Unlike a display, persistence must not silently substitute a placeholder —
        // a bad row is a data problem and should surface, not be papered over.
        assertFailsWith<org.example.project.money.MoneyException> {
            DecimalColumnAdapter.decode("not-a-number")
        }
        Unit
    }
}
