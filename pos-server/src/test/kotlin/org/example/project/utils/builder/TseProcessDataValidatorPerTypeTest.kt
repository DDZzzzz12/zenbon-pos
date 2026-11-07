package org.example.project.utils.builder

import org.example.project.tse.utils.ProcessDataValidator
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 15 (a)+(b), driver side: `ProcessDataValidator` judges a payload by the grammar for ITS
 * processType, and no longer by the Kassenbeleg-V1 grammar unconditionally.
 *
 * Why this mattered: the validator runs inside `FinishTransaction`, i.e. AFTER `transaction_start`
 * has burned a transaction number. Rejecting a valid `Bestellung-V1` there left an OPEN transaction
 * on the WORM, which cannot be corrected — the same failure shape that TSER-4 already caused once
 * with negative amounts.
 *
 * Lives in pos-server because pos-server depends on pos-tse and has a working JUnit 5 setup
 * (pos-tse's own suite does not compile — see known-decisions.md). That is also what makes this
 * path testable at all: it otherwise executes only in `TseMode.NORMAL`, on hardware.
 */
@DisplayName("pos-tse ProcessDataValidator validates per processType (Task 15a/b)")
class TseProcessDataValidatorPerTypeTest {
    @Test
    fun `a valid Bestellung-V1 is accepted instead of being judged by the receipt grammar`() {
        // The Anhang I p. 116 example. Zero '^' — the old code returned
        // "Invalid format ... (got 1 parts, expected 3)" and the FinishTransaction threw.
        val r =
            ProcessDataValidator.validate(
                "2;\"Eisbecher \"\"Himbeere\"\"\";3.99\r1;\"Eiskaffee\";2.99",
                "Bestellung-V1",
            )
        assertTrue(r.isValid, "a spec-valid Bestellung must not be rejected: ${r.errors}")
    }

    @Test
    fun `a SonstigerVorgang free-text payload is accepted`() {
        val r = ProcessDataValidator.validate("Kassensturz 2026-07-23", "SonstigerVorgang")
        assertTrue(r.isValid, "SonstigerVorgang is free text by design: ${r.errors}")
    }

    @Test
    fun `a malformed Bestellung is still rejected`() {
        // The fix must not degrade into "accept anything that is not a receipt".
        val r = ProcessDataValidator.validate("1;Cola;3.00", "Bestellung-V1")
        assertFalse(r.isValid, "an unquoted Bezeichnung must still fail")
    }

    @Test
    fun `the Kassenbeleg grammar still applies to a Kassenbeleg`() {
        assertTrue(
            ProcessDataValidator
                .validate(
                    "Beleg^60.00_0.00_0.00_0.00_0.00^60.00:Unbar",
                    "Kassenbeleg-V1",
                ).isValid,
        )
        assertFalse(
            ProcessDataValidator
                .validate(
                    "Beleg^60.00_0.00_0.00_0.00^60.00:Unbar",
                    "Kassenbeleg-V1",
                ).isValid,
            "four USt buckets must still be rejected",
        )
    }

    @Test
    fun `a split tender no longer produces a spurious warning`() {
        // (b): `split(":", limit = 2)` treated only the FIRST tender as the total, so every
        // legitimate split bill compared 100.00 against 60.00 and warned. A check that cries wolf
        // on every split trains everyone to ignore TSE warnings.
        val r =
            ProcessDataValidator.validate(
                "Beleg^100.00_0.00_0.00_0.00_0.00^60.00:Bar_40.00:Unbar",
                "Kassenbeleg-V1",
            )
        assertTrue(r.isValid, "${r.errors}")
        assertTrue(r.warnings.isEmpty(), "a balanced split tender must not warn; got ${r.warnings}")
    }

    @Test
    fun `a foreign-currency payload does not warn either`() {
        // Anhang I p. 115: „Lediglich bei der Verwendung von Fremdwährungen weichen die Summen
        // voneinander ab." — so the sums legitimately differ and must not be flagged.
        val r =
            ProcessDataValidator.validate(
                "Beleg^75.33_7.99_0.00_0.00_0.00^10.00:Bar_5.00:Bar:CHF_5.00:Bar:USD_64.30:Unbar",
                "Kassenbeleg-V1",
            )
        assertTrue(r.isValid && r.warnings.isEmpty(), "errors=${r.errors} warnings=${r.warnings}")
    }

    @Test
    fun `strict mode still promotes an imbalance to an error`() {
        // The original contract: callers asking for strict get the mismatch as a hard failure.
        val data = "Beleg^100.00_0.00_0.00_0.00_0.00^60.00:Bar"
        assertTrue(ProcessDataValidator.validate(data, "Kassenbeleg-V1").isValid)
        assertFalse(ProcessDataValidator.validate(data, "Kassenbeleg-V1", strict = true).isValid)
    }

    @Test
    fun `an unknown processType is rejected, never silently given the receipt grammar`() {
        val r = ProcessDataValidator.validate("Beleg^0.00_0.00_0.00_0.00_0.00^0.00:Bar", "Quatsch-V9")
        assertFalse(r.isValid, "applying a guessed grammar is how (a) happened in the first place")
    }
}
