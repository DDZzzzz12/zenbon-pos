package org.example.project.features.kassenbuch

import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.kassenbuch.data.service.KassenbuchAccountResolver
import org.example.project.features.kassenbuch.data.service.KassenbuchAccountResolver.Resolution
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KassenbuchAccountResolverTest {
    private val mapping =
        DatevAccountMappingDefaults.seed(
            Kontenrahmen.SKR03,
            listOf(
                DatevAccountMappingDefaults.PaymentTypeSeed(id = 2L, isVoucher = false),
                DatevAccountMappingDefaults.PaymentTypeSeed(id = 5L, isVoucher = true),
            ),
        )

    private fun resolve(
        entryType: KassenbuchEntryType = KassenbuchEntryType.CASH_EXPENSE,
        headerGegenkonto: Int? = null,
        headerBu: String? = null,
        kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
        ustSatz: String? = "19",
        paymentTypeId: Long? = null,
        m: org.example.project.core.domain.dto.datev.DatevAccountMappingDto.MappingDto? = mapping,
    ) = KassenbuchAccountResolver.resolve(entryType, headerGegenkonto, headerBu, kind, ustSatz, paymentTypeId, m)

    @Test
    fun `expense STANDARD 19 maps to Wareneingang with Kasse konto`() {
        val r = resolve()
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 3400, bu = null), r)
    }

    @Test
    fun `income GUTSCHEIN 7 maps to Erloesschmaelerungen`() {
        val r = resolve(entryType = KassenbuchEntryType.CASH_SALE, kind = KassenbuchLineKind.GUTSCHEIN, ustSatz = "7")
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 8710, bu = null), r)
    }

    @Test
    fun `mapping side follows correction semantics not isIncoming`() {
        // CORRECTION_OUT reverses income entries -> income side (Erloese, not Wareneingang)
        val out = resolve(entryType = KassenbuchEntryType.CORRECTION_OUT, ustSatz = "19")
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 8400, bu = null), out)
        // CORRECTION_IN mirrors expense entries -> expense side
        val inn = resolve(entryType = KassenbuchEntryType.CORRECTION_IN, ustSatz = "19")
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 3400, bu = null), inn)
        // REFUND is negative revenue -> income side
        val refund = resolve(entryType = KassenbuchEntryType.REFUND, ustSatz = "7")
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 8300, bu = null), refund)
    }

    @Test
    fun `entry types outside the rate tables resolve through the special slots`() {
        // These used to resolve Legacy and drop to hardcoded per-entry-type constants — a fixed
        // SKR03/SKR04 mix that also inverted the booking for PRIVATE_WITHDRAWAL, CASH_SURPLUS and
        // CASH_SHORTAGE. They now come from mapping.special, so Kasse is always the Konto side.
        mapOf(
            KassenbuchEntryType.CASH_TRANSFER_IN to 1360,
            KassenbuchEntryType.CASH_TRANSFER_OUT to 1360,
            KassenbuchEntryType.CASH_DEPOSIT to 9000,
            KassenbuchEntryType.CASH_WITHDRAWAL to 9000,
            KassenbuchEntryType.PRIVATE_DEPOSIT to 1890, // Privateinlagen (SKR03, F60)
            KassenbuchEntryType.PRIVATE_WITHDRAWAL to 1800, // Privatentnahmen allgemein (SKR03, F60)
            KassenbuchEntryType.CASH_SURPLUS to 8603,
            KassenbuchEntryType.CASH_SHORTAGE to 4900,
            KassenbuchEntryType.OPENING_BALANCE to 9000,
            KassenbuchEntryType.CLOSING_BALANCE to 9000,
            KassenbuchEntryType.CHANGE_FUND to 9000,
        ).forEach { (type, expectedGegenkonto) ->
            val r = resolve(entryType = type, m = mapping)
            assertEquals(
                Resolution.Mapped(konto = 1000, gegenkonto = expectedGegenkonto, bu = null),
                r,
                "$type",
            )
        }
    }

    @Test
    fun `a special slot missing from the mapping resolves Legacy so the exporter can refuse`() {
        val withoutTransit = mapping.copy(special = mapping.special - KassenbuchSpecialBooking.GELDTRANSIT)
        assertIs<Resolution.Legacy>(
            resolve(entryType = KassenbuchEntryType.CASH_TRANSFER_IN, m = withoutTransit),
        )
    }

    @Test
    fun `NON_CASH_PAYMENT resolves via payment type and falls back legacy on null or unknown id`() {
        val card =
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                ustSatz = null,
                paymentTypeId = 2L,
            )
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 1360, bu = null), card)
        val voucher =
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                ustSatz = null,
                paymentTypeId = 5L,
            )
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 1700, bu = null), voucher)
        // backfilled rows: null id -> whole-line legacy (byte-identical to today)
        assertIs<Resolution.Legacy>(
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                ustSatz = null,
                paymentTypeId = null,
            ),
        )
        assertIs<Resolution.Legacy>(
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                ustSatz = null,
                paymentTypeId = 99L,
            ),
        )
    }

    @Test
    fun `whole-line legacy on missing cell, unknown rate, or no mapping`() {
        assertIs<Resolution.Legacy>(resolve(kind = KassenbuchLineKind.PFAND, ustSatz = "7")) // PFAND has only "19" seeded
        assertIs<Resolution.Legacy>(resolve(ustSatz = "16"))
        assertIs<Resolution.Legacy>(resolve(ustSatz = null))
        assertIs<Resolution.Legacy>(resolve(m = null))
    }

    @Test
    fun `historical comma rate strings still resolve`() {
        val r = resolve(ustSatz = "19,00")
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 3400, bu = null), r)
    }

    @Test
    fun `a special entry type ignores the payment-type lookup entirely`() {
        // PRIVATE_WITHDRAWAL has no mappingSide, so even a known paymentTypeId must not steer it to
        // a clearing account — the special slot wins and it books to Privatentnahme.
        assertEquals(
            Resolution.Mapped(konto = 1000, gegenkonto = 1800, bu = null),
            resolve(
                entryType = KassenbuchEntryType.PRIVATE_WITHDRAWAL,
                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                ustSatz = null,
                paymentTypeId = 2L,
                m = mapping,
            ),
        )
    }

    /**
     * A `RECEIVABLE_SETTLEMENT` is rate-less exactly like a `NON_CASH_PAYMENT`, so it must resolve
     * on the payment-type axis too. Before the fix the resolver exempted only `NON_CASH_PAYMENT`,
     * so this line hit `VatRateNormalizer.normalize(null)` -> null -> `Legacy`, and
     * `KassenbuchBuchungsstapelRowMapper` turned that into a 409 that failed the whole period's
     * export — naming a mapping cell the operator cannot create.
     *
     * The id under test is the tender that OPENED the receivable, which is why the expected account
     * is the very same 1360 cell the opening `NON_CASH_PAYMENT` leg resolves to above.
     */
    @Test
    fun `RECEIVABLE_SETTLEMENT resolves via the receivable tender, not the rate table`() {
        assertEquals(
            Resolution.Mapped(konto = 1000, gegenkonto = 1360, bu = null),
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.RECEIVABLE_SETTLEMENT,
                ustSatz = null,
                paymentTypeId = 2L,
            ),
            "a cash Forderungsausgleich clears the account its opening leg debited",
        )
        // An unresolvable tender stays Legacy so the exporter refuses rather than inventing a
        // contra account — the deliberate degradation for a clearing whose opening tender is
        // ambiguous or missing.
        assertIs<Resolution.Legacy>(
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.RECEIVABLE_SETTLEMENT,
                ustSatz = null,
                paymentTypeId = null,
            ),
        )
        assertIs<Resolution.Legacy>(
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.RECEIVABLE_SETTLEMENT,
                ustSatz = null,
                paymentTypeId = 99L,
            ),
        )
    }

    @Test
    fun `header gegenkonto and bu override the cell`() {
        val r = resolve(headerGegenkonto = 4444, headerBu = "9")
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 4444, bu = "9"), r)
    }

    // --- F51 (2026-10): EMPLOYEE_TIP resolves through its own special slot -----------------------

    @Test
    fun `EMPLOYEE_TIP books on the employee-tip slot in the Z-Bon aggregate and in the payout`() {
        // The aggregate's tip line (CASH_SALE, rate "0") and the payout's line (CASH_WITHDRAWAL, no
        // rate) must meet on ONE account, so the receipt and the payout net to zero there.
        val receipt = resolve(entryType = KassenbuchEntryType.CASH_SALE, kind = KassenbuchLineKind.EMPLOYEE_TIP, ustSatz = "0")
        val payout = resolve(entryType = KassenbuchEntryType.CASH_WITHDRAWAL, kind = KassenbuchLineKind.EMPLOYEE_TIP, ustSatz = null)
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 1590, bu = null), receipt)
        assertEquals(receipt, payout)
    }

    @Test
    fun `EMPLOYEE_TIP ignores the rate axis whatever rate the line declares`() {
        listOf(null, "0", "7", "19", "abc").forEach { rate ->
            assertEquals(
                Resolution.Mapped(konto = 1000, gegenkonto = 1590, bu = null),
                resolve(entryType = KassenbuchEntryType.CASH_SALE, kind = KassenbuchLineKind.EMPLOYEE_TIP, ustSatz = rate),
                "rate=$rate",
            )
        }
    }

    @Test
    fun `SKR04 books employee tips on 1370`() {
        val skr04 = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, emptyList())
        assertEquals(
            Resolution.Mapped(konto = 1600, gegenkonto = 1370, bu = null),
            resolve(entryType = KassenbuchEntryType.CASH_SALE, kind = KassenbuchLineKind.EMPLOYEE_TIP, ustSatz = "0", m = skr04),
        )
    }

    /** Negative control: the pre-F51 shape of the same line still books on the 0 % revenue account. */
    @Test
    fun `negative control - a STANDARD tip line at rate 0 still lands on revenue 8100`() {
        assertEquals(
            Resolution.Mapped(konto = 1000, gegenkonto = 8100, bu = null),
            resolve(entryType = KassenbuchEntryType.CASH_SALE, kind = KassenbuchLineKind.STANDARD, ustSatz = "0"),
        )
    }

    @Test
    fun `a mapping without the employee-tip slot resolves Legacy, and the backfill restores it`() {
        val stale = mapping.copy(special = mapping.special - KassenbuchSpecialBooking.EMPLOYEE_TIP)
        assertIs<Resolution.Legacy>(
            resolve(entryType = KassenbuchEntryType.CASH_SALE, kind = KassenbuchLineKind.EMPLOYEE_TIP, ustSatz = "0", m = stale),
        )
        assertEquals(
            Resolution.Mapped(konto = 1000, gegenkonto = 1590, bu = null),
            resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                kind = KassenbuchLineKind.EMPLOYEE_TIP,
                ustSatz = "0",
                m = DatevAccountMappingDefaults.withBackfilledDefaults(stale),
            ),
        )
    }
}
