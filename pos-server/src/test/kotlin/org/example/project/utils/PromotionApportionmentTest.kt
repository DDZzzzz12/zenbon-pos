package org.example.project.utils

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Locks in the penny-exact largest-remainder apportionment used to split a bill-wide promotion
 * across VAT-rate groups (RA-1). The invariant that matters everywhere: every share is scale 2 and
 * Σ shares == total EXACTLY, deterministically (tie-break on the VAT rate ascending) so
 * BillServiceImpl and DsfinvkExportServiceImpl derive identical shares.
 */
class PromotionApportionmentTest {
    private fun sum(m: Map<BigDecimal, BigDecimal>): BigDecimal = m.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }

    @Test
    fun `equal weights split the leftover cent to the lower rate`() {
        val shares =
            PromotionApportionment.apportion(
                BigDecimal("0.05"),
                mapOf(BigDecimal("0.19") to BigDecimal("5.00"), BigDecimal("0.07") to BigDecimal("5.00")),
            )
        // floors 0.02 each; the single leftover cent breaks the remainder tie on the lower rate (0.07).
        assertEquals(0, shares.getValue(BigDecimal("0.19")).compareTo(BigDecimal("0.02")))
        assertEquals(0, shares.getValue(BigDecimal("0.07")).compareTo(BigDecimal("0.03")))
        assertEquals(0, sum(shares).compareTo(BigDecimal("0.05")))
    }

    @Test
    fun `shares always sum to total exactly at scale 2`() {
        val cases =
            listOf(
                BigDecimal("0.05") to mapOf(BigDecimal("0.19") to BigDecimal("7.30"), BigDecimal("0.07") to BigDecimal("2.70")),
                BigDecimal("1.99") to
                    mapOf(
                        BigDecimal("0.19") to BigDecimal("33.33"),
                        BigDecimal("0.07") to BigDecimal("11.11"),
                        BigDecimal("0.00") to BigDecimal("5.55"),
                    ),
                BigDecimal("0.03") to
                    mapOf(
                        BigDecimal("0.19") to BigDecimal("1.00"),
                        BigDecimal("0.07") to BigDecimal("1.00"),
                        BigDecimal("0.105") to BigDecimal("1.00"),
                    ),
            )
        cases.forEach { (total, weights) ->
            val shares = PromotionApportionment.apportion(total, weights)
            assertEquals(0, sum(shares).compareTo(total), "Σ shares must equal $total for weights $weights; got $shares")
            shares.values.forEach { assertEquals(2, it.scale(), "share $it must be scale 2") }
        }
    }

    @Test
    fun `zero total or zero weight yields all-zero shares`() {
        val zeroTotal =
            PromotionApportionment.apportion(
                BigDecimal.ZERO,
                mapOf(BigDecimal("0.19") to BigDecimal("5.00")),
            )
        assertEquals(0, sum(zeroTotal).compareTo(BigDecimal.ZERO))

        val zeroWeight =
            PromotionApportionment.apportion(
                BigDecimal("0.05"),
                mapOf(BigDecimal("0.19") to BigDecimal.ZERO),
            )
        assertEquals(0, sum(zeroWeight).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `single rate receives the whole deduction`() {
        val shares =
            PromotionApportionment.apportion(
                BigDecimal("0.17"),
                mapOf(BigDecimal("0.19") to BigDecimal("42.00")),
            )
        assertEquals(0, shares.getValue(BigDecimal("0.19")).compareTo(BigDecimal("0.17")))
    }

    // ============================================
    // A3-2 — sign-symmetry for NEGATIVE totals (storno owner-tip / reverse bill)
    // ============================================
    //
    // A reverse (storno) bill carries a negated tip/deduction. The largest-remainder split must be
    // sign-symmetric so the negative shares sum EXACTLY to the negative total (the previous code
    // floored toward zero and `take(coerceAtLeast(0)) = take(0)` handed out no leftover cent for a
    // negative total, leaving Σ shares == total + 0.01 and drifting the exported gross).

    @Test
    fun `negative single-rate total distributes the whole negative deduction`() {
        val shares =
            PromotionApportionment.apportion(
                BigDecimal("-0.17"),
                mapOf(BigDecimal("0.19") to BigDecimal("42.00")),
            )
        assertEquals(0, shares.getValue(BigDecimal("0.19")).compareTo(BigDecimal("-0.17")))
        assertEquals(0, sum(shares).compareTo(BigDecimal("-0.17")))
    }

    @Test
    fun `negative multi-rate total sums to exactly the negative total`() {
        val weights = mapOf(BigDecimal("0.19") to BigDecimal("5.00"), BigDecimal("0.07") to BigDecimal("4.00"))
        val shares = PromotionApportionment.apportion(BigDecimal("-3.00"), weights)
        assertEquals(0, sum(shares).compareTo(BigDecimal("-3.00")), "Σ negative shares must equal -3.00 exactly; got $shares")
        shares.values.forEach { assertEquals(2, it.scale(), "share $it must be scale 2") }
    }

    @Test
    fun `apportion is sign-symmetric - negating the total negates every share exactly`() {
        val cases =
            listOf(
                BigDecimal("3.00") to mapOf(BigDecimal("0.19") to BigDecimal("5.00"), BigDecimal("0.07") to BigDecimal("4.00")),
                BigDecimal("0.05") to mapOf(BigDecimal("0.19") to BigDecimal("5.00"), BigDecimal("0.07") to BigDecimal("5.00")),
                BigDecimal("1.99") to
                    mapOf(
                        BigDecimal("0.19") to BigDecimal("33.33"),
                        BigDecimal("0.07") to BigDecimal("11.11"),
                        BigDecimal("0.00") to BigDecimal("5.55"),
                    ),
            )
        cases.forEach { (total, weights) ->
            val positive = PromotionApportionment.apportion(total, weights)
            val negative = PromotionApportionment.apportion(total.negate(), weights)
            positive.forEach { (rate, share) ->
                assertEquals(
                    0,
                    negative.getValue(rate).compareTo(share.negate()),
                    "apportion(-$total)[$rate] must equal -apportion($total)[$rate]",
                )
            }
            assertEquals(0, sum(negative).compareTo(total.negate()), "Σ negative shares must equal ${total.negate()}")
        }
    }

    /**
     * Largest-remainder apportionment does NOT compose — the reason bill-wide promotions are summed
     * into ONE deduction and apportioned ONCE, rather than getting a Bonpos line each.
     *
     * Two €0.05 promotions over two equally-weighted rates: apportioning the €0.10 combined total
     * gives 19%→0.05 / 7%→0.05, but apportioning €0.05 twice gives 19%→0.04 / 7%→0.06 — each pass
     * floors to 0.02 and hands its leftover cent to the SAME deterministic tie-break winner (the
     * LOWER rate, per `equal weights split the leftover cent to the lower rate` above), twice.
     *
     * (The remediation plan's worked example had this drift the other way round, 0.06/0.04. The
     * direction follows the ascending-rate tie-break; the magnitude — one cent — is what matters.)
     *
     * That divergence is exactly the H11 regression (lines.csv disagreeing with lines_vat in both
     * count and amount), and it is why one-line-per-promotion was rejected rather than implemented:
     * DSFinV-K §4.2.4 asks for bill-wide discounts „getrennt nach Steuersätzen", with „Die
     * Aufteilung der Entgeltminderung erfolgt in der Datei Bonpos_USt" — the organising dimension is
     * the TAX RATE, not the promotion, so per-promotion lines buy nothing the spec wants while
     * reopening a proven arithmetic hazard.
     *
     * If this ever IS implemented, it must be a TWO-PASS split (apportion the combined total across
     * rates first — authoritative — then sub-apportion each rate's share across the promotions), and
     * this test documents why the naive one-pass-per-promotion version is wrong.
     */
    @Test
    fun `apportioning each promotion separately does NOT equal apportioning their sum`() {
        val weights = mapOf(BigDecimal("0.19") to BigDecimal("5.00"), BigDecimal("0.07") to BigDecimal("5.00"))

        val combined = PromotionApportionment.apportion(BigDecimal("0.10"), weights)

        val separate =
            listOf(BigDecimal("0.05"), BigDecimal("0.05"))
                .map { PromotionApportionment.apportion(it, weights) }
                .fold(mutableMapOf<BigDecimal, BigDecimal>()) { acc, shares ->
                    shares.forEach { (rate, share) -> acc[rate] = (acc[rate] ?: BigDecimal.ZERO) + share }
                    acc
                }

        // Both still total 0.10 — the split between rates is what differs.
        assertEquals(0, sum(combined).compareTo(BigDecimal("0.10")))
        assertEquals(0, sum(separate).compareTo(BigDecimal("0.10")))

        val rate19 = BigDecimal("0.19")
        assertEquals(
            0,
            combined.getValue(rate19).compareTo(BigDecimal("0.05")),
            "the single combined apportionment is the authoritative split lines_vat is built from",
        )
        assertEquals(
            0,
            separate.getValue(rate19).compareTo(BigDecimal("0.04")),
            "summing per-promotion apportionments drifts a cent — if this ever equals 0.05, " +
                "largest-remainder has become additive and the aggregation decision can be revisited",
        )
    }
}
