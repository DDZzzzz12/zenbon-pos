package org.example.project.utils.builder

import org.example.project.tse.utils.ProcessDataValidator
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TSER-4 — ProcessDataValidator must accept spec-valid negative amounts.
 *
 * DSFinV-K Anhang I explicitly documents negative Umsätze/Zahlungen
 * (Warenrücknahme p.115, Barausgabe p.120, Pfand-Rückgabe). Rejecting them
 * blocked FinishTransaction on the two-step path (quick sale) and leaked
 * open WORM transactions.
 *
 * Lives in pos-server (not pos-tse) because pos-server depends on pos-tse
 * and has a working JUnit 5 test setup.
 */
@DisplayName("ProcessDataValidator — negative amounts (TSER-4)")
class ProcessDataValidatorNegativeTest {
    @Test
    @DisplayName("Anhang I negative examples validate")
    fun `Anhang I negative examples validate`() {
        listOf(
            "Beleg^-20.00_0.00_0.00_0.00_0.00^-20.00:Bar", // Warenrücknahme p.115
            "Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar", // Barausgabe p.120
            "Beleg^0.00_0.00_0.00_0.00_-0.75^-0.75:Bar", // Pfand-Rückgabe quick sale
            "Beleg^-3.00_0.00_0.00_0.00_0.00^-3.00:Bar_5.00:Unbar", // negative first tender
        ).forEach { pd ->
            val r = ProcessDataValidator.validate(pd)
            assertTrue(r.isValid, "spec-valid processData rejected: $pd -> ${r.errors}")
        }
    }

    @Test
    @DisplayName("genuinely invalid processData is still rejected")
    fun `genuinely invalid processData is still rejected`() {
        // Non-numeric tax bucket
        val nonNumeric = ProcessDataValidator.validate("Beleg^abc_0.00_0.00_0.00_0.00^10.00:Bar")
        assertFalse(nonNumeric.isValid, "non-numeric tax bucket should be invalid")

        // Wrong bucket count (4 instead of 5)
        val wrongCount = ProcessDataValidator.validate("Beleg^10.00_0.00_0.00_0.00^10.00:Bar")
        assertFalse(wrongCount.isValid, "wrong tax bucket count should be invalid")

        // Non-numeric total amount
        val badTotal = ProcessDataValidator.validate("Beleg^10.00_0.00_0.00_0.00_0.00^xyz:Bar")
        assertFalse(badTotal.isValid, "non-numeric total should be invalid")

        // Missing ':' payment separator
        val noSeparator = ProcessDataValidator.validate("Beleg^10.00_0.00_0.00_0.00_0.00^10.00")
        assertFalse(noSeparator.isValid, "missing payment separator should be invalid")

        // Empty payment method
        val emptyPayment = ProcessDataValidator.validate("Beleg^10.00_0.00_0.00_0.00_0.00^10.00:")
        assertFalse(emptyPayment.isValid, "empty payment method should be invalid")
    }
}
