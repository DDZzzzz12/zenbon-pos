package org.example.project.utils.builder

import org.example.project.core.domain.enums.tse.ProcessType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates processData against the Anhang I grammar FOR ITS processType, on our side of the wire.
 *
 * Two gaps motivate this, and the second is the bigger one:
 *
 *  1. `pos-tse`'s `ProcessDataValidator` applies the Kassenbeleg-V1 grammar to every payload, so a
 *     `Bestellung-V1` (zero `^`) is rejected — and it validates INSIDE `FinishTransaction`, i.e.
 *     after `transaction_start` already burned a transaction number. A rejection there strands an
 *     open transaction on the WORM, uncorrectable. (That exact failure already happened once with
 *     negative amounts — round-3 TSER-4.)
 *  2. The path production actually uses — `quickSign` — performs **no validation at all**. So today
 *     nothing checks a Kassenbeleg-V1 payload before it is signed.
 *
 * Validating here, at build time, closes (2) and makes (1) unreachable: a malformed payload is
 * rejected before any TSE call exists to strand.
 *
 * The test vectors below are the spec's OWN worked examples (Anhang I p. 114-116), which is the
 * strongest available evidence that the grammar is read correctly.
 */
@DisplayName("processData is validated against the Anhang I grammar for its processType")
class ProcessDataSpecValidatorTest {
    private fun valid(
        type: ProcessType,
        data: String,
    ) {
        val r = ProcessDataSpecValidator.validate(type, data)
        assertTrue(r.isValid, "spec-valid payload rejected: $data -> ${r.errors}")
    }

    private fun invalid(
        type: ProcessType,
        data: String,
        because: String,
    ) {
        val r = ProcessDataSpecValidator.validate(type, data)
        assertTrue(!r.isValid, "$because — but it was accepted: $data")
    }

    // ---------------------------------------------------------------- Kassenbeleg-V1

    @Test
    fun `Anhang I worked examples validate`() {
        // p. 115 Warenrücknahme: 19% −20.00 and 16% 80.00 net to 60.00 unbar.
        valid(ProcessType.KASSENBELEG_V1, "Beleg^60.00_0.00_0.00_0.00_0.00^60.00:Unbar")
        // p. 115 foreign currency: four tenders in the mandated order.
        valid(
            ProcessType.KASSENBELEG_V1,
            "Beleg^75.33_7.99_0.00_0.00_0.00^10.00:Bar_5.00:Bar:CHF_5.00:Bar:USD_64.30:Unbar",
        )
        // p. 119 Mehrzweckgutschein-Einlösung: negative container 5.
        valid(ProcessType.KASSENBELEG_V1, "Beleg^60.00_60.00_0.00_0.00_-100.00^20.00:Bar")
        // TSER-4 negatives must keep validating.
        valid(ProcessType.KASSENBELEG_V1, "Beleg^-20.00_0.00_0.00_0.00_0.00^-20.00:Bar")
    }

    @Test
    fun `an AVBelegabbruch with no payments is legal`() {
        // Anhang B p. 46: the abort carries zero revenue and no tender section at all.
        valid(ProcessType.KASSENBELEG_V1, "AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^")
    }

    @Test
    fun `amounts must carry exactly two decimals in dot notation`() {
        // „Die Beträge im Bereich Brutto-Steuerumsätze werden stets mit zwei Dezimalstellen
        // ausgewiesen" (p. 115). A German-locale formatter emitting a comma is the exact bug that
        // makes a payload unparseable AFTER it has been signed.
        invalid(ProcessType.KASSENBELEG_V1, "Beleg^100,00_0.00_0.00_0.00_0.00^100.00:Bar", "comma decimal")
        invalid(ProcessType.KASSENBELEG_V1, "Beleg^100.0_0.00_0.00_0.00_0.00^100.00:Bar", "one decimal")
        invalid(ProcessType.KASSENBELEG_V1, "Beleg^100.000_0.00_0.00_0.00_0.00^100.00:Bar", "three decimals")
    }

    @Test
    fun `the five USt buckets are mandatory`() {
        invalid(ProcessType.KASSENBELEG_V1, "Beleg^100.00_0.00_0.00_0.00^100.00:Bar", "only four buckets")
        invalid(ProcessType.KASSENBELEG_V1, "Beleg^100.00_0.00_0.00_0.00_0.00_0.00^100.00:Bar", "six buckets")
    }

    @Test
    fun `Zahlungsart is only Bar or Unbar`() {
        // Anhang I defines exactly two; the richer Anhang D vocabulary (ECKarte, Kreditkarte…)
        // belongs to datapayment.csv ZAHLART_TYP, never to the signed payload.
        invalid(ProcessType.KASSENBELEG_V1, "Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Karte", "Karte is not Anhang I")
        invalid(ProcessType.KASSENBELEG_V1, "Beleg^100.00_0.00_0.00_0.00_0.00^100.00:EC", "EC is not Anhang I")
    }

    @Test
    fun `tender order follows the mandated sequence`() {
        // p. 115: Bar EUR, then Bar foreign alphabetically, then Unbar EUR, then Unbar foreign.
        invalid(
            ProcessType.KASSENBELEG_V1,
            "Beleg^75.33_7.99_0.00_0.00_0.00^64.30:Unbar_10.00:Bar_5.00:Bar:CHF_5.00:Bar:USD",
            "Unbar EUR placed before Bar EUR",
        )
        invalid(
            ProcessType.KASSENBELEG_V1,
            "Beleg^10.00_0.00_0.00_0.00_0.00^5.00:Bar:USD_5.00:Bar:CHF",
            "foreign Bar currencies out of alphabetical order",
        )
    }

    @Test
    fun `revenue and payments must reconcile in EUR, but not across currencies`() {
        // „Grundsätzlich müssen die Summen der Umsätze mit denen der Zahlungen übereinstimmen.
        // Lediglich bei der Verwendung von Fremdwährungen weichen die Summen voneinander ab."
        // Reported as a WARNING, not an error: the payload is well-formed, and refusing to sign it
        // would leave the Vorgang unsigned — the worse §146a outcome. (It is also how a bill with
        // payments but no BillVatDetail rows shows up.)
        val mismatch =
            ProcessDataSpecValidator.validate(
                ProcessType.KASSENBELEG_V1,
                "Beleg^100.00_0.00_0.00_0.00_0.00^60.00:Bar",
            )
        assertTrue(mismatch.isValid, "a well-formed but unbalanced Beleg must still be signable")
        assertTrue(
            mismatch.warnings.any { it.contains("Umsätze") },
            "...but the imbalance must be reported; got ${mismatch.warnings}",
        )
        // A split tender that DOES reconcile must produce NO warning — the pos-tse validator's
        // `split(":", limit = 2)` bug warns on every one of these.
        val split =
            ProcessDataSpecValidator.validate(
                ProcessType.KASSENBELEG_V1,
                "Beleg^100.00_0.00_0.00_0.00_0.00^60.00:Bar_40.00:Unbar",
            )
        assertTrue(split.isValid && split.warnings.isEmpty(), "clean split tender: ${split.warnings}")
        // With a foreign tender present the sums legitimately differ — must NOT be flagged.
        valid(
            ProcessType.KASSENBELEG_V1,
            "Beleg^75.33_7.99_0.00_0.00_0.00^10.00:Bar_5.00:Bar:CHF_5.00:Bar:USD_64.30:Unbar",
        )
    }

    @Test
    fun `the Vorgangstyp must be a known BON_TYP`() {
        invalid(ProcessType.KASSENBELEG_V1, "Quatsch^0.00_0.00_0.00_0.00_0.00^0.00:Bar", "unknown BON_TYP")
        valid(ProcessType.KASSENBELEG_V1, "AVTraining^10.00_0.00_0.00_0.00_0.00^10.00:Bar")
    }

    // ---------------------------------------------------------------- Bestellung-V1

    @Test
    fun `the Anhang I Bestellung example validates`() {
        // p. 116: 2 x Eisbecher „Himbeere" at 3.99 and 1 x Eiskaffee at 2.99, rows separated by \r,
        // Bezeichnung always quoted with internal quotes doubled.
        valid(ProcessType.BESTELLUNG_V1, "2;\"Eisbecher \"\"Himbeere\"\"\";3.99\r1;\"Eiskaffee\";2.99")
        // Fractional Menge is explicitly allowed („0.5", „0.451").
        valid(ProcessType.BESTELLUNG_V1, "0.451;\"Lachs\";12.50")
    }

    @Test
    fun `a Bestellung payload is NOT judged by the Kassenbeleg grammar`() {
        // This is finding (a): pos-tse rejects this because it has zero '^'. It is perfectly valid.
        val r = ProcessDataSpecValidator.validate(ProcessType.BESTELLUNG_V1, "1;\"Cola\";3.00")
        assertTrue(r.isValid, "a valid Bestellung must not be judged by the receipt grammar: ${r.errors}")
    }

    @Test
    fun `a malformed Bestellung row is rejected`() {
        invalid(ProcessType.BESTELLUNG_V1, "1;Cola;3.00", "Bezeichnung not quoted")
        invalid(ProcessType.BESTELLUNG_V1, "1;\"Cola\";3.000", "Preis with three decimals")
        invalid(ProcessType.BESTELLUNG_V1, "1;\"Cola\"", "missing the Preis column")
        invalid(ProcessType.BESTELLUNG_V1, "x;\"Cola\";3.00", "non-numeric Menge")
        invalid(ProcessType.BESTELLUNG_V1, "", "empty payload")
    }

    @Test
    fun `a semicolon inside the Bezeichnung does not break the column count`() {
        // Quote-aware parsing: the separator only counts outside quotes.
        valid(ProcessType.BESTELLUNG_V1, "1;\"Cola; gross\";3.00")
    }

    @Test
    fun `an embedded newline in the Bezeichnung is rejected`() {
        // m20: a CR would forge a second Bestellzeile. The builder already collapses it; this is
        // the belt-and-braces check at the fiscal boundary.
        invalid(ProcessType.BESTELLUNG_V1, "1;\"Cola\rPepsi\";3.00", "CR inside the Bezeichnung")
    }

    // ---------------------------------------------------------------- SonstigerVorgang

    @Test
    fun `SonstigerVorgang is free text and only has to be non-empty`() {
        valid(ProcessType.SONSTIGER_VORGANG, "Kassensturz 2026-07-23")
        valid(ProcessType.SONSTIGER_VORGANG, "Beleg^0.00_0.00_0.00_0.00_0.00^0.00:Bar")
        invalid(ProcessType.SONSTIGER_VORGANG, "   ", "blank payload carries no information")
    }

    // ---------------------------------------------------------------- reporting

    @Test
    fun `errors name the offending component`() {
        val r =
            ProcessDataSpecValidator.validate(
                ProcessType.KASSENBELEG_V1,
                "Beleg^100,00_0.00_0.00_0.00_0.00^100.00:Bar",
            )
        assertEquals(false, r.isValid)
        assertTrue(
            r.errors.any { it.contains("100,00") },
            "the message must quote the bad value so it is actionable; got ${r.errors}",
        )
    }
}
