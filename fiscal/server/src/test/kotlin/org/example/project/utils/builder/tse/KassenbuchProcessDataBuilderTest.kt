package org.example.project.utils.builder.tse

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the exact TSE ProcessData string produced by [KassenbuchProcessDataBuilder] for the
 * Kassenbuch "SonstigerVorgang"/Umsatz shape, so a future formatter/ordering change can't
 * silently alter the fiscal signature.
 *
 * The signed ProcessData grammar is caret/underscore delimited plain-text with dot decimals —
 * it must never contain a raw control character (e.g. `\n`) and must never vary with the JVM
 * default locale (spec 2026-07-11 H6).
 */
class KassenbuchProcessDataBuilderTest {
    /** Runs [block] with a forced JVM default locale, restoring the previous one afterwards. */
    private fun <T> withLocale(
        locale: Locale,
        block: () -> T,
    ): T {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    private fun line(
        amount: String,
        rate: String?,
        kind: KassenbuchLineKind,
    ) = KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = rate, kind = kind)

    /**
     * A single Kassenbuch line. [ustSatz] is the real DTO unit — a PERCENT string ("19", "7")
     * or null for a non-taxable movement (the 0 % bucket). [kind] defaults to STANDARD;
     * NON_CASH_PAYMENT lines are tender, not revenue, and must be excluded from the buckets.
     */
    private fun lineItem(
        vorzBetrag: String,
        ustSatz: String?,
        kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
    ) = KassenbuchDto.LineItemData(vorzBetrag = vorzBetrag, ustSatz = ustSatz, kind = kind)

    private fun buildNoPfandDay(): String =
        KassenbuchProcessDataBuilder.buildProcessData(
            entryType = KassenbuchEntryType.CASH_SALE,
            description = "KB-1-ZBon Nr. 1",
            totalNonCashWithdrawals = BigDecimal("20.00"),
            netCashAmount = BigDecimal("99.00"),
            lineItems =
                listOf(
                    line("100.00", "19", KassenbuchLineKind.STANDARD),
                    line("19.00", "7", KassenbuchLineKind.STANDARD),
                    line("-20.00", null, KassenbuchLineKind.NON_CASH_PAYMENT),
                ),
        )

    private fun buildWithPfandDay(): String =
        KassenbuchProcessDataBuilder.buildProcessData(
            entryType = KassenbuchEntryType.CASH_SALE,
            description = "KB-2-ZBon Nr. 2",
            totalNonCashWithdrawals = null,
            netCashAmount = BigDecimal("2.00"),
            lineItems =
                listOf(
                    line("1.75", "19", KassenbuchLineKind.STANDARD),
                    line("0.50", "19", KassenbuchLineKind.PFAND),
                    line("-0.25", "19", KassenbuchLineKind.PFAND),
                ),
        )

    @Test
    fun `no-pfand day pins the corrected shape - no newline, dot decimals`() {
        val pd = withLocale(Locale.ROOT) { buildNoPfandDay() }
        // VAT-bearing lines sorted by rate ("7" then "19"), joined by "_"; then Bar/Unbar.
        // No embedded newline between the VAT block and the "^" separator.
        assertEquals("Umsatz^19.00_100.00^99.00:Bar_20.00:Unbar^KB-1-ZBon Nr. 1", pd)
        assertFalse(pd.contains("\n"), "signed ProcessData must not contain a control character")
    }

    @Test
    fun `with-pfand day keeps the fixed STANDARD-deposit-return order at a rate`() {
        val pd = withLocale(Locale.ROOT) { buildWithPfandDay() }
        // Same VAT rate ("19") for all three lines -> groupBy/sortedBy is stable, so construction
        // order (STANDARD, deposit +, return -) survives unchanged into the joined string.
        assertEquals("Umsatz^1.75_0.50_-0.25^2.00:Bar^KB-2-ZBon Nr. 2", pd)
        assertFalse(pd.contains("\n"), "signed ProcessData must not contain a control character")
    }

    // --- Round-3 TSER-2: cash-moving entries sign as Kassenbeleg-V1 (Beleg^<buckets>^<payments>),
    // pinning the Anhang I p. 119/120 examples character-exactly. ---

    @Test
    fun `Privateinlage 100 bar signs as Beleg with key-5 bucket`() {
        val pd =
            withLocale(Locale.ROOT) {
                KassenbuchProcessDataBuilder.buildKassenbelegV1(
                    vorgangstyp = "Beleg",
                    entryType = KassenbuchEntryType.CASH_DEPOSIT,
                    correctedEntryType = null,
                    lineItems = listOf(lineItem(vorzBetrag = "100.00", ustSatz = null)),
                    netCashAmount = BigDecimal("100.00"),
                    totalNonCashWithdrawals = null,
                )
            }
        assertEquals("Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar", pd)
    }

    @Test
    fun `Geldtransit -100 bar signs the negative Beleg`() {
        val pd =
            withLocale(Locale.ROOT) {
                KassenbuchProcessDataBuilder.buildKassenbelegV1(
                    "Beleg",
                    KassenbuchEntryType.CASH_TRANSFER_OUT,
                    null,
                    listOf(lineItem(vorzBetrag = "-100.00", ustSatz = null)),
                    BigDecimal("-100.00"),
                    null,
                )
            }
        assertEquals("Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar", pd)
    }

    @Test
    fun `Barausgabe 50 at 19 percent fills the general bucket`() {
        val pd =
            withLocale(Locale.ROOT) {
                KassenbuchProcessDataBuilder.buildKassenbelegV1(
                    "Beleg",
                    KassenbuchEntryType.CASH_EXPENSE,
                    null,
                    listOf(lineItem(vorzBetrag = "-50.00", ustSatz = "19")),
                    BigDecimal("-50.00"),
                    null,
                )
            }
        assertEquals("Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar", pd)
    }

    @Test
    fun `NON_CASH_PAYMENT tender lines are excluded from the revenue buckets - only Unbar`() {
        // Round-3 TSER-2/ZBon: the daily-close aggregate MIXES revenue lines (ustSatz set) with
        // NON_CASH_PAYMENT tender lines (ustSatz = null, negative vorzBetrag) and ALSO passes the
        // non-cash total separately as totalNonCashWithdrawals. A rate-only bucketer would drop the
        // −20.00 tender line into bucket 5 (0 %) AND double-count it as Unbar. It must be excluded.
        val pd =
            withLocale(Locale.ROOT) {
                KassenbuchProcessDataBuilder.buildKassenbelegV1(
                    vorgangstyp = "Beleg",
                    entryType = KassenbuchEntryType.CASH_SALE,
                    correctedEntryType = null,
                    lineItems =
                        listOf(
                            lineItem(vorzBetrag = "119.00", ustSatz = "19"),
                            lineItem(vorzBetrag = "-20.00", ustSatz = null, kind = KassenbuchLineKind.NON_CASH_PAYMENT),
                        ),
                    netCashAmount = BigDecimal("99.00"),
                    totalNonCashWithdrawals = BigDecimal("20.00"),
                )
            }
        // 119.00 @19% -> bucket 1; the −20.00 tender line is Unbar only, so bucket 5 stays 0.00.
        assertEquals("Beleg^119.00_0.00_0.00_0.00_0.00^99.00:Bar_20.00:Unbar", pd)
        val buckets = pd.substringAfter("Beleg^").substringBefore("^")
        assertFalse(buckets.contains("-20.00"), "NON_CASH_PAYMENT line must not land in any revenue bucket")
        assertFalse(buckets.contains("20.00"), "NON_CASH_PAYMENT line must not appear in the 0 % bucket")
    }

    @Test
    fun `Kassenbeleg-V1 is identical under Locale-GERMANY and Locale-ROOT`() {
        fun build() =
            KassenbuchProcessDataBuilder.buildKassenbelegV1(
                "Beleg",
                KassenbuchEntryType.CASH_EXPENSE,
                null,
                listOf(lineItem("-50.00", "19"), lineItem("-7.00", "7")),
                BigDecimal("-57.00"),
                BigDecimal("-10.00"),
            )
        val root = withLocale(Locale.ROOT) { build() }
        val germany = withLocale(Locale.GERMANY) { build() }
        assertEquals(root, germany, "Kassenbeleg-V1 ProcessData must not depend on the default locale")
        assertFalse(germany.contains(","), "no comma decimal separators under German locale")
        assertEquals("Beleg^-50.00_-7.00_0.00_0.00_0.00^-57.00:Bar_-10.00:Unbar", root)
    }

    // --- Round-7 D4: <Vorgangstyp> is a PARAMETER, and only „Beleg" is a Kassenbeleg-V1. ---

    @Test
    fun `a non-Beleg Vorgangstyp is rejected instead of being silently signed as Beleg`() {
        // The prefix used to be the hard-coded literal "Beleg^" while getBonTyp could return
        // AVSonstige for the same entry — the signed Vorgangstyp could disagree with the BON_TYP
        // the same Vorgang exports. Anhang I p. 113 enumerates the valid <Vorgangstyp> values;
        // Anhang B p. 43: „Bei allen Vorgangstypen, die mit AV beginnen (Ausnahme: AVTraining),
        // ist nur die Zahlart „Keine" möglich." — so an AV* Vorgang is not this format at all.
        val e =
            assertFailsWith<IllegalArgumentException> {
                KassenbuchProcessDataBuilder.buildKassenbelegV1(
                    "AVSonstige",
                    KassenbuchEntryType.CASH_DEPOSIT,
                    null,
                    listOf(lineItem("100.00", null)),
                    BigDecimal("100.00"),
                    null,
                )
            }
        assertTrue(e.message!!.contains("AVSonstige"))
    }

    @Test
    fun `CLOSING_BALANCE has no admissible container and must not sign as Kassenbeleg-V1 (G1)`() {
        // DSFinV-K has NO Endbestand GV_TYP (Tz. 4.1.3 p. 34: „Andere als die hier aufgeführten
        // Typen dürfen nicht verwendet werden."). Freeze, do not invent a container — a guessed
        // one would be unfixable once signed. New CLOSING_BALANCE entries are already rejected at
        // creation (KABS-3); this is the defensive guard for the legacy shape.
        assertFailsWith<IllegalArgumentException> {
            KassenbuchProcessDataBuilder.buildKassenbelegV1(
                "Beleg",
                KassenbuchEntryType.CLOSING_BALANCE,
                null,
                listOf(lineItem("-100.00", null)),
                BigDecimal("-100.00"),
                null,
            )
        }
        Unit
    }

    @Test
    fun `ProcessData is identical under Locale-GERMANY and Locale-ROOT`() {
        val rootNoPfand = withLocale(Locale.ROOT) { buildNoPfandDay() }
        val germanyNoPfand = withLocale(Locale.GERMANY) { buildNoPfandDay() }
        assertEquals(rootNoPfand, germanyNoPfand, "no-pfand ProcessData must not depend on the default locale")
        assertFalse(germanyNoPfand.contains(","), "no comma decimal separators under German locale")

        val rootWithPfand = withLocale(Locale.ROOT) { buildWithPfandDay() }
        val germanyWithPfand = withLocale(Locale.GERMANY) { buildWithPfandDay() }
        assertEquals(rootWithPfand, germanyWithPfand, "with-pfand ProcessData must not depend on the default locale")
        assertFalse(germanyWithPfand.contains(","), "no comma decimal separators under German locale")
    }

    // --- F51 (2026-10): the employee-tip line kind -------------------------------------------------

    /** The Z-Bon aggregate of a tipped day, with its tip line as [tipKind] at [tipRate]. */
    private fun tippedAggregate(
        tipKind: KassenbuchLineKind,
        tipRate: String?,
    ): String =
        KassenbuchProcessDataBuilder.buildProcessData(
            entryType = KassenbuchEntryType.CASH_SALE,
            description = "KB-2026-10-0001-ZBon Nr. 7",
            totalNonCashWithdrawals = BigDecimal("12.00"),
            netCashAmount = BigDecimal("107.00"),
            lineItems =
                listOf(
                    line("100.00", "19", KassenbuchLineKind.STANDARD),
                    line("19.00", "7", KassenbuchLineKind.STANDARD),
                    line("2.00", tipRate, tipKind),
                    line("-12.00", null, KassenbuchLineKind.NON_CASH_PAYMENT),
                ),
        )

    @Test
    fun `the aggregate signs byte-identically whether its tip line is STANDARD or EMPLOYEE_TIP`() {
        val before = withLocale(Locale.ROOT) { tippedAggregate(KassenbuchLineKind.STANDARD, "0") }
        val after = withLocale(Locale.ROOT) { tippedAggregate(KassenbuchLineKind.EMPLOYEE_TIP, "0") }

        assertEquals("Umsatz^2.00_19.00_100.00^107.00:Bar_12.00:Unbar^KB-2026-10-0001-ZBon Nr. 7", before)
        assertEquals(before, after, "the kind is not part of the signed SonstigerVorgang payload")
    }

    /**
     * Negative control: the comparison above is not vacuous. The ONE thing about the tip line the
     * payload does read is its rate — a rate-less tip drops out of the Umsätze segment.
     */
    @Test
    fun `negative control - a rate-less tip line would change the signed bytes`() {
        val withRate = withLocale(Locale.ROOT) { tippedAggregate(KassenbuchLineKind.EMPLOYEE_TIP, "0") }
        val rateless = withLocale(Locale.ROOT) { tippedAggregate(KassenbuchLineKind.EMPLOYEE_TIP, null) }

        assertEquals("Umsatz^19.00_100.00^107.00:Bar_12.00:Unbar^KB-2026-10-0001-ZBon Nr. 7", rateless)
        assertTrue(withRate != rateless, "the rate is what the aggregate's signed bytes depend on")
    }

    /**
     * A tip payout: Anhang C p. 52 lets `TrinkgeldAN` carry the Auszahlung to the employee, and the
     * classifier pins it to the 0 %/key-5 container — so it signs exactly like the spec's
     * Privatentnahme example (p. 119), negative in container 5 and in the Bar leg, whatever entry type
     * (Barentnahme) and rate the line carries.
     */
    @Test
    fun `a tip payout signs as a negative key-5 Kassenbeleg`() {
        listOf(null, "0", "19").forEach { rate ->
            val pd =
                withLocale(Locale.ROOT) {
                    KassenbuchProcessDataBuilder.buildKassenbelegV1(
                        "Beleg",
                        KassenbuchEntryType.CASH_WITHDRAWAL,
                        null,
                        listOf(lineItem(vorzBetrag = "-12.50", ustSatz = rate, kind = KassenbuchLineKind.EMPLOYEE_TIP)),
                        BigDecimal("-12.50"),
                        null,
                    )
                }
            assertEquals("Beleg^0.00_0.00_0.00_0.00_-12.50^-12.50:Bar", pd, "rate=$rate")
        }
    }

    /** Negative control for the payout pin: the same withdrawal as a STANDARD line at 19 % is an Auszahlung in bucket 1. */
    @Test
    fun `negative control - the same withdrawal as a STANDARD 19 percent line lands in bucket 1`() {
        val pd =
            withLocale(Locale.ROOT) {
                KassenbuchProcessDataBuilder.buildKassenbelegV1(
                    "Beleg",
                    KassenbuchEntryType.CASH_WITHDRAWAL,
                    null,
                    listOf(lineItem(vorzBetrag = "-12.50", ustSatz = "19")),
                    BigDecimal("-12.50"),
                    null,
                )
            }
        assertEquals("Beleg^-12.50_0.00_0.00_0.00_0.00^-12.50:Bar", pd)
    }

    /** The storno of a tip payout signs the mirror image: TrinkgeldAN +12.50 in container 5, cash in. */
    @Test
    fun `a tip payout storno signs as a positive key-5 Kassenbeleg`() {
        val pd =
            withLocale(Locale.ROOT) {
                KassenbuchProcessDataBuilder.buildKassenbelegV1(
                    "Beleg",
                    KassenbuchEntryType.CORRECTION_IN,
                    KassenbuchEntryType.CASH_WITHDRAWAL,
                    listOf(lineItem(vorzBetrag = "12.50", ustSatz = null, kind = KassenbuchLineKind.EMPLOYEE_TIP)),
                    BigDecimal("12.50"),
                    null,
                )
            }
        assertEquals("Beleg^0.00_0.00_0.00_0.00_12.50^12.50:Bar", pd)
    }
}
