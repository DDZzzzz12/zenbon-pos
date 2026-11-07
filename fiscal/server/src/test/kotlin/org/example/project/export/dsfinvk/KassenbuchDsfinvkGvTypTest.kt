package org.example.project.export.dsfinvk

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseTypes
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class KassenbuchDsfinvkGvTypTest {
    private fun gv(
        t: KassenbuchEntryType,
        k: KassenbuchLineKind,
        amt: String,
    ) = KassenbuchDsfinvkMapping.getGvTyp(t, k, BigDecimal(amt))

    @Test
    fun `umsatz-family maps per-line kinds`() {
        assertEquals(BusinessCaseTypes.PFAND, gv(KassenbuchEntryType.CASH_SALE, KassenbuchLineKind.PFAND, "0.25"))
        assertEquals(BusinessCaseTypes.PFAND_RUECKZAHLUNG, gv(KassenbuchEntryType.CASH_SALE, KassenbuchLineKind.PFAND, "-0.25"))
        // A voucher redemption is not a discount — it must map to the voucher GV_TYP,
        // not Rabatt.
        assertEquals(
            BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG,
            gv(KassenbuchEntryType.CASH_SALE, KassenbuchLineKind.GUTSCHEIN, "-5.00"),
        )
        assertEquals(BusinessCaseTypes.UMSATZ, gv(KassenbuchEntryType.CASH_SALE, KassenbuchLineKind.STANDARD, "10.00"))
        // REFUND is Umsatz-family; a negative Leergut line → PfandRueckzahlung
        assertEquals(BusinessCaseTypes.PFAND_RUECKZAHLUNG, gv(KassenbuchEntryType.REFUND, KassenbuchLineKind.PFAND, "-0.25"))
        // NON_CASH_PAYMENT (defensive; unreachable in DSFinV-K) → header GV_TYP
        assertEquals(BusinessCaseTypes.UMSATZ, gv(KassenbuchEntryType.CASH_SALE, KassenbuchLineKind.NON_CASH_PAYMENT, "-20.00"))
    }

    @Test
    fun `non-umsatz headers keep header GV_TYP for every line`() {
        // a purchase-side Pfand/Gutschein is part of the Auszahlung, not a sales case
        assertEquals(BusinessCaseTypes.AUSZAHLUNG, gv(KassenbuchEntryType.CASH_EXPENSE, KassenbuchLineKind.PFAND, "0.25"))
        assertEquals(BusinessCaseTypes.AUSZAHLUNG, gv(KassenbuchEntryType.CASH_EXPENSE, KassenbuchLineKind.GUTSCHEIN, "-5.00"))
    }

    @Test
    fun `gv names cover the new business cases`() {
        assertEquals("Pfand", KassenbuchDsfinvkMapping.getGvName(BusinessCaseTypes.PFAND))
        assertEquals("Pfand-Rückzahlung", KassenbuchDsfinvkMapping.getGvName(BusinessCaseTypes.PFAND_RUECKZAHLUNG))
        assertEquals("Rabatt", KassenbuchDsfinvkMapping.getGvName(BusinessCaseTypes.RABATT))
        assertEquals(
            "Mehrzweckgutschein-Einlösung",
            KassenbuchDsfinvkMapping.getGvName(BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG),
        )
    }

    @Test
    fun `a generic deposit is Einzahlung, an owner deposit is Privateinlage (m6)`() {
        // Anhang C p. 57: Privateinlage is „die Einlage von Bargeld aus der PRIVATSPHÄRE DES
        // UNTERNEHMERS" and „dürfen den Gewinn nicht beeinflussen und sind daher im Rahmen der
        // Gewinnermittlung zu kürzen" — it is deducted from profit. A generic „Bargeldeinlage"
        // (cash into the register/safe) is NOT definitionally the owner's private capital, so it
        // must NOT claim that treatment.
        //
        // CASH_DEPOSIT (generic) → Einzahlung, the catch-all inflow whose tax qualification Anhang C
        // p. 58 leaves „bezogen auf den jeweiligen Sachverhalt zu prüfen". PRIVATE_DEPOSIT (owner)
        // → Privateinlage. This mirrors the outgoing side exactly, where CASH_WITHDRAWAL → Auszahlung
        // and PRIVATE_WITHDRAWAL → Privatentnahme already distinguish the two.
        assertEquals(BusinessCaseTypes.EINZAHLUNG, KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.CASH_DEPOSIT))
        assertEquals(BusinessCaseTypes.PRIVATEINLAGE, KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.PRIVATE_DEPOSIT))
    }

    @Test
    fun `both deposit kinds are Beleg and their GV names resolve`() {
        assertEquals("Beleg", KassenbuchDsfinvkMapping.getBonTyp(KassenbuchEntryType.CASH_DEPOSIT))
        assertEquals("Beleg", KassenbuchDsfinvkMapping.getBonTyp(KassenbuchEntryType.PRIVATE_DEPOSIT))
        assertEquals("Einzahlung", KassenbuchDsfinvkMapping.getGvName(BusinessCaseTypes.EINZAHLUNG))
        assertEquals("Privateinlage", KassenbuchDsfinvkMapping.getGvName(BusinessCaseTypes.PRIVATEINLAGE))
    }

    @Test
    fun `change fund maps to Geldtransit not Anfangsbestand`() {
        // Anhang C: „Das Auffüllen des Bargeldbestandes ist über den Geschäftsvorfalltyp
        // ‚Geldtransit' zu erfassen" — Anfangsbestand is the opening cash only (KABS-6).
        assertEquals(BusinessCaseTypes.GELDTRANSIT, KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.CHANGE_FUND))
        assertEquals(BusinessCaseTypes.ANFANGSBESTAND, KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.OPENING_BALANCE))
    }

    @Test
    fun `cash-moving entries and the genuine Anfangsbestand are Beleg, legacy closing snapshot stays AVSonstige`() {
        assertEquals("Beleg", KassenbuchDsfinvkMapping.getBonTyp(KassenbuchEntryType.CASH_TRANSFER_OUT))
        assertEquals("Beleg", KassenbuchDsfinvkMapping.getBonTyp(KassenbuchEntryType.PRIVATE_WITHDRAWAL))
        // Anhang C p. 56 (KABS-3): the genuine Anfangsbestand is „als BON_TYP «Beleg» zu erfassen".
        assertEquals("Beleg", KassenbuchDsfinvkMapping.getBonTyp(KassenbuchEntryType.OPENING_BALANCE))
        // CLOSING_BALANCE is rejected for new entries; legacy rows stay AVSonstige.
        assertEquals("AVSonstige", KassenbuchDsfinvkMapping.getBonTyp(KassenbuchEntryType.CLOSING_BALANCE))
    }
}
