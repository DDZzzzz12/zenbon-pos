package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import java.math.BigDecimal

/**
 * THE single authority for which USt container / UST_SCHLUESSEL a Kassenbuch line is admitted to.
 *
 * **The rule (round-7 C1/C2).** Container placement is a pure function of
 * `(GV_TYP, declared rate)` — **not** of GV_TYP alone and **not** of the declared rate alone.
 *
 * *Not GV_TYP alone.* Anhang I p. 118, verbatim:
 * > 50€ Umsatz 19%, 50€ Umsatz 7%, Bezahlt mit Visa
 * > `Beleg^50.00_50.00_0.00_0.00_0.00^100.00:Unbar`
 * > BON_TYP: "Beleg"
 * > GV_TYP: "Umsatz"
 *
 * One GV_TYP, two containers, one Vorgang. A GV_TYP→container table cannot express that, and the
 * same holds for `Auszahlung` (Anhang C p. 59), `Pfand`/`PfandRueckzahlung` (Anhang C p. 49/50),
 * `TrinkgeldAG` (p. 120) and `EinzweckgutscheinEinloesung` (p. 118).
 *
 * *Not the declared rate alone.* p. 119 `Privateinlage` and p. 120 `Geldtransit` both put a
 * rate-defaulted amount in container 5. That is exactly the C1 defect: the TSE builder bucketed on
 * the raw declared `ustSatz`, and `EntryLineState.vatRate = "19"` is the CLIENT DEFAULT, so a
 * Privateinlage booked without touching the VAT dropdown signed
 * `Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Bar` — a false declaration of 100 € §12 Abs. 1 Umsatz —
 * into an IMMUTABLE WORM record, where p. 119 mandates `Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar`.
 *
 * *Why the pair is sufficient.* A CLOSED set of nine GV_TYPs is pinned by the spec to the
 * 0 %/nicht-steuerbar class, so for those the rate input is DISCARDED ([SPEC_NICHT_STEUERBAR]).
 * For every other GV_TYP the declared rate governs. This was checked against every Anhang I
 * `Kassenbeleg-V1` example (Umsatz ×2, EZG Kauf + Einlösung, MZG Kauf + Einlösung ×2, Guthabenkarte
 * ×3, Privateinlage, Privatentnahme, TrinkgeldAN, TrinkgeldAG, Forderungsentstehung,
 * Forderungsauflösung, Geldtransit, Barausgabe, Anfangsbestand ×2, p. 115 Warenrücknahme and the
 * p. 121 AV* set) with no exception. The elegant confirmation is p. 122: the two Anfangsbestand
 * examples produce BYTE-IDENTICAL processData under DIFFERENT GV_TYPs (`Anfangsbestand` vs
 * `Geldtransit`) — because both normalize to key 5.
 *
 * **Why this object exists at all.** Before round 7 the signed TSE payload and the DSFinV-K CSV
 * package each carried their own, independently-written classifier, and the two were almost exact
 * COMPLEMENTS: the builder was wrong precisely where the exporter was right (the nine key-5
 * GV_TYPs) and the exporter was wrong precisely where the builder was right (`Auszahlung` at a
 * declared rate — `hasVatImplication` returned `false` for `CASH_WITHDRAWAL`, whose GV_TYP is the
 * SAME `Auszahlung` as `CASH_EXPENSE`, so the exporter booked two identical Geschäftsvorfälle at
 * two different USt keys). Fixing only one side leaves the same number of disagreeing cells, just
 * different ones. **Both the TSE `Kassenbeleg-V1` builder and all three CSV VAT producers MUST
 * resolve through this function.** Two parallel rules over one fiscal fact is the failure mode the
 * whole audit series keeps finding.
 *
 * **This function is TOTAL by design.** Leaving a cell undefined on a SIGNING path is strictly
 * worse than a determinate one — an unhandled GV_TYP would either throw at signing time or fall
 * into a silent default. Where the spec genuinely does not decide (see [HOUSE_NICHT_STEUERBAR]),
 * the choice is recorded as a house decision, explicitly not as compliance.
 *
 * **Not covered — model gap, filed separately (D5/E4).** `KassenbuchLineKind` has no
 * Einzweck/Mehrzweck discriminator, so a `GUTSCHEIN` line hard-maps to
 * `MehrzweckgutscheinEinloesung` (container 5). Anhang I p. 118 and official workbook V_2_3_03
 * Bsp. 3.2 require `EinzweckgutscheinEinloesung` at the DECLARED rate (container 1) — a shop
 * redeeming Einzweckgutscheine therefore under-declares VAT. That needs a new enum value plus a
 * migration; it is NOT fixable inside this classifier, which already routes
 * `EinzweckgutscheinEinloesung` to the declared rate correctly the moment the model can emit it.
 */
object KassenbuchTaxClassifier {
    /**
     * The NINE GV_TYPs the spec pins to the 0 %/nicht-steuerbar class (Anlage-2 ID 5). For these
     * the declared rate is DISCARDED — it is not a property of the transaction, and on the default
     * UI path it is merely the dropdown's initial value.
     *
     * - `Privateinlage` — Anhang C p. 58, the most explicit sentence in the corpus: „Aus
     *   umsatzsteuerlicher Sicht handelt es sich bei der Privateinlage von Barmitteln um
     *   Geschäftsvorfälle ohne USt-Bezug (**ID 5**)." (p. 119 / workbook V_2_3_05 Bsp. 5.1)
     * - `Privatentnahme` — Anhang C p. 57 „…ohne USt-Bezug" (p. 119 / V_2_3_05 Bsp. 5.2). The
     *   following sentence about `Sachentnahmen` concerns entnommene GEGENSTÄNDE, not the cash
     *   entry we model — it does NOT license a rate container.
     * - `Geldtransit` — Anhang C p. 58 „…hat aus umsatzsteuerlicher Sicht keine Relevanz."
     *   (p. 120 / V_2_3_06)
     * - `Anfangsbestand` — Anhang I p. 122, „kein Geschäftsvorfall, da keine Änderung der
     *   Vermögenszusammensetzung"; Anhang C p. 56.
     * - `MehrzweckgutscheinKauf` / `MehrzweckgutscheinEinloesung` — Anhang C p. 54 (nicht
     *   steuerbar); Anhang I p. 118 books the redemption as `(0%) -100€` in container 5 while the
     *   Umsatz lines keep their 19 %/7 % gross (V_2_3_02 Bsp. 2.2: key 5, POS_BRUTTO −50).
     * - `TrinkgeldAN` — Anhang I p. 120: the SAME 50,00 € Bar tender splits across different
     *   containers depending solely on who keeps the tip (`TrinkgeldAG` is rate-governed).
     * - `Forderungsentstehung` / `Forderungsaufloesung` — Anhang I p. 120; Tz. 3.2.6 p. 27: „Die
     *   ID 7 dient der Kennzeichnung von Forderungsauflösungen, deren umsatzsteuerliche Zuordnung
     *   von der Kasse nicht dargestellt werden kann."
     */
    private val SPEC_NICHT_STEUERBAR: Set<String> =
        setOf(
            DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_KAUF,
            DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG,
            DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN,
            DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSENTSTEHUNG,
            DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSAUFLOESUNG,
            DsfinvkExportDto.BusinessCaseTypes.PRIVATEINLAGE,
            DsfinvkExportDto.BusinessCaseTypes.PRIVATENTNAHME,
            DsfinvkExportDto.BusinessCaseTypes.GELDTRANSIT,
            DsfinvkExportDto.BusinessCaseTypes.ANFANGSBESTAND,
        )

    /**
     * **HOUSE DECISION — not compliance (round-7 gap G2).**
     *
     * `DifferenzSollIst` (our `CASH_SURPLUS`/`CASH_SHORTAGE`) has **no Anhang I processData
     * example anywhere**, and Anhang C p. 59 explicitly DECLINES to qualify it: „Die ertrag- und
     * umsatzsteuerliche Qualifikation der Differenzen ist bezogen auf den jeweiligen Sachverhalt
     * zu prüfen…". The spec therefore does not decide this cell.
     *
     * We choose container 5 rather than failing loud, for three reasons:
     * 1. It is the only DEFENSIBLE default with an official source: workbook **V_2_3_07** books
     *    exactly this Vorgang at `UST_SCHLUESSEL 5 / BON_BRUTTO -87 / BON_UST 0 / ZAHLART_TYP Bar
     *    -87`.
     * 2. A Kassensturz difference is by construction a CASH-COUNT delta, not a supply — there is
     *    no Leistung to attach a Steuersatz to, so any rate container would be a positive false
     *    declaration.
     * 3. Failing loud here would reject a Vorgang the system legitimately allows creating (unlike
     *    `CLOSING_BALANCE`, which is already rejected at creation) and would break the CSV path,
     *    which has always booked key 5 — turning a documented ambiguity into an outage.
     *
     * If the operator's Steuerberater rules otherwise for a given Sachverhalt, this is the ONE
     * line to change, and the change lands on both producers at once.
     *
     * `Einzahlung` (m6) sits here for the SAME reason. Anhang C p. 58 declines to qualify it —
     * „Die ertrag- und umsatzsteuerliche Qualifikation der hier erfassten Einzahlungen ist bezogen
     * auf den jeweiligen Sachverhalt zu prüfen" — so unlike `Privateinlage` (SPEC container 5) the
     * spec does not pin its container. A generic cash-into-till is by construction not a supply, so
     * container 5 is the defensible default, and it keeps the signed payload of a `CASH_DEPOSIT`
     * byte-identical to what it was when that entry type mapped to `Privateinlage` — the GV_TYP
     * label changed, the signature did not. Bucketing it by the client's defaulted rate instead
     * would be the C1 defect all over again.
     */
    private val HOUSE_NICHT_STEUERBAR: Set<String> =
        setOf(
            DsfinvkExportDto.BusinessCaseTypes.DIFFERENZ_SOLL_IST,
            DsfinvkExportDto.BusinessCaseTypes.EINZAHLUNG,
        )

    /**
     * True when the line's DECLARED VAT rate governs its container; false when the GV_TYP pins the
     * line to the 0 %/nicht-steuerbar class regardless of what rate the line declares.
     *
     * @param gvTyp the LINE's GV_TYP — resolve it via
     *   `KassenbuchDsfinvkMapping.getGvTyp(entryType, kind, vorzBetrag, correctedEntryType)`, never
     *   from the entry type alone. The admissible key is a property of the LINE, not of the entry:
     *   Anhang I p. 118 shows one entry legitimately mixing a container-5
     *   `MehrzweckgutscheinEinloesung` line with 19 % and 7 % `Umsatz` lines, which an entry-level
     *   boolean cannot express.
     */
    fun isRateGoverned(gvTyp: String): Boolean = gvTyp !in SPEC_NICHT_STEUERBAR && gvTyp !in HOUSE_NICHT_STEUERBAR

    /**
     * The rate string to PERSIST on a line of a Vorgang whose GV_TYP is [gvTyp] (the entry's header
     * GV_TYP — for a correction, the corrected entry's).
     *
     * For a GV_TYP the spec pins to the 0 %/nicht-steuerbar class the declared rate is not a property
     * of the transaction — on the default UI path it is merely the dropdown's initial `"19"`. The TSE
     * payload and the DSFinV-K CSV already discard it through [effectiveVatRate], but the raw value
     * used to be stored verbatim, so a Geldtransit/Bankeinzahlung showed "19 %" in the detail view and
     * left `<tax>19.00</tax>` in the DATEV XML export. Storing the effective rate (`"0"`) makes the
     * row say what the signed record says. A null stays null (no rate declared at all), and a
     * rate-governed GV_TYP keeps whatever was declared.
     *
     * Only the HEADER GV_TYP is consulted, deliberately: the per-line overrides under `Umsatz`
     * (Gutschein/Pfand) keep their declared rate, which the DATEV account resolver reads for them.
     * The signed bytes are unaffected either way — [effectiveVatRate] still decides those.
     */
    fun storedRate(
        gvTyp: String,
        declaredRate: String?,
    ): String? = if (declaredRate == null || isRateGoverned(gvTyp)) declaredRate else "0"

    /**
     * The rate that actually governs this line's container, as a FRACTION (0.19, 0.07, …) — the
     * declared rate for a rate-governed GV_TYP, `ZERO` otherwise.
     *
     * Callers keep their existing rate→container / rate→UST_SCHLUESSEL step
     * (`ProcessDataBuilder.bucketIndexFor` on the TSE side,
     * `DsfinvkExportDto.VatKeyMapping.getVatKey` on the CSV side); this function is the single
     * place the GV_TYP override is applied, so the two can no longer drift.
     *
     * A null [declaredRate] means the line carries no rate at all (Z-Bon tender lines, legacy
     * rows) and yields `ZERO` — the pre-existing „null rate → container 5" behaviour, unchanged.
     *
     * **Corrections need ZERO special-casing here.** `getGvTyp` already resolves `CORRECTION_*`
     * through its `correctsHeader` link, and `createCorrection` copies the corrected entry's
     * `ustSatz` onto the reversal's lines — so BOTH inputs already come from the corrected entry
     * and the reversal lands in the corrected entry's container automatically. That matters:
     * nothing nets ACROSS containers (Anhang I p. 118), so Tz. 4.2.2 p. 35 („Die Vorzeichen sind
     * umzukehren") and BON_STORNO p. 85 („…die Beträge des ersten Datensatzes rechnerisch wieder
     * ausgleicht") are satisfiable ONLY if the reversal occupies the same container as the
     * original. A GV_TYP→container table would instead need the correction path threaded by hand —
     * another place to drift.
     */
    fun effectiveVatRate(
        gvTyp: String,
        declaredRate: BigDecimal?,
    ): BigDecimal = if (isRateGoverned(gvTyp)) declaredRate ?: BigDecimal.ZERO else BigDecimal.ZERO
}
