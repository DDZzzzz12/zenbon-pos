/**
 * Seed values for the DATEV account mapping (spec §3.3). Editable defaults,
 * not tax advice — the settings UI and manual carry a Steuerberater note.
 * Verified against DATEV SKR03/SKR04 tables 2026-07-08 — that verification covers the income,
 * expense and paymentTypes tables only.
 *
 * The `special` slots (transfers, private movements, cash differences, balance carries) were
 * re-verified 2026-10-06 (F60) against DATEV's own charts, „DATEV-Kontenrahmen nach dem
 * Bilanzrichtlinie-Umsetzungsgesetz", Gültig für 2026:
 * - SKR03, Art.-Nr. 11174 (2026-01-01): 1000 Kasse, 1360 Geldtransit, 1590 Durchlaufende Posten,
 *   1800 Privatentnahmen allgemein, 1890 Privateinlagen, 4900 Sonstige betriebliche Aufwendungen,
 *   8603 Sonstige betriebliche Erträge, 9000 Saldenvorträge Sachkonten.
 * - SKR04, Art.-Nr. 11175 (2026-01-01): 1370 Durchlaufende Posten, 1460 Geldtransit, 1600 Kasse,
 *   2100 Privatentnahmen allgemein, 2180 Privateinlagen, 4830 Sonstige betriebliche Erträge,
 *   6300 Sonstige betriebliche Aufwendungen, 9000 Saldenvorträge Sachkonten.
 * Both are DATEV's free downloads from datev.de ("Kontenrahmen DATEV SKR 03/04", shop pages
 * kontenrahmen-datev-skr-03 / kontenrahmen-datev-skr-04).
 *
 * F60 corrected four SKR03 cells that were wrong against that chart: PRIVATE_DEPOSIT was 9000
 * (Saldenvorträge) and PRIVATE_WITHDRAWAL 1890 — which is Privat**einlagen**, so every withdrawal
 * was booked as a deposit; CASH_SURPLUS was 2110 (Zinsaufwendungen für kurzfristige
 * Verbindlichkeiten — an interest EXPENSE) and CASH_SHORTAGE 6880, which SKR03 does not have at
 * all (6880 is SKR04's Aufwendungen aus der Währungsumrechnung). DATEV has no dedicated
 * Kassendifferenz account in either chart; the generic „sonstige betriebliche" pair is seeded
 * instead, and the Steuerberater may prefer to treat an unexplained surplus as Erlös.
 *
 * Seeds only reach a shop through a new install or „Standardkonten übernehmen". A mapping already
 * stored is never rewritten — a shop that exported with the old cells keeps them until its operator
 * changes them.
 *
 * Both charts seed all nine special slots, SKR04 mirroring SKR03 one to one: CASH_DEPOSIT,
 * CASH_WITHDRAWAL and BALANCE_CARRY_FORWARD → 9000, CASH_SURPLUS → 4830 (SKR03 8603), CASH_SHORTAGE →
 * 6300 (SKR03 4900). `validate()` still refuses an incomplete mapping and the Buchungsstapel export
 * still refuses to run without a valid one — an operator who clears a slot must fill it again.
 *
 * EMPLOYEE_TIP is seeded in BOTH charts (SKR03 1590 / SKR04 1370, „Durchlaufende Posten") and is
 * backfilled into mappings stored before it existed — see [withBackfilledDefaults]. It too wants
 * the Steuerberater's confirmation; some shops keep a dedicated Verbindlichkeiten-gegenüber-
 * Personal account instead.
 *
 * Still wanting a Steuerberater's review, in BOTH charts: CASH_SURPLUS / CASH_SHORTAGE (the generic
 * Kassendifferenz pair above — SKR03 8603/4900, SKR04 4830/6300), and CASH_DEPOSIT / CASH_WITHDRAWAL
 * seeded to 9000 — 9000 is Saldenvorträge, which is right for a balance carry
 * (BALANCE_CARRY_FORWARD) and questionable for a cash movement.
 */
package org.example.project.core.utils.datev

import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.MappingDto
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind

object DatevAccountMappingDefaults {
    /** Minimal payment-type projection so client (preview) and server (seeding) share this object. */
    data class PaymentTypeSeed(
        val id: Long,
        val isVoucher: Boolean,
    )

    fun seed(
        kontenrahmen: Kontenrahmen,
        nonCashPaymentTypes: List<PaymentTypeSeed>,
    ): MappingDto =
        when (kontenrahmen) {
            Kontenrahmen.SKR03 ->
                MappingDto(
                    kontenrahmen = Kontenrahmen.SKR03,
                    kasseKonto = 1000,
                    income =
                        mapOf(
                            KassenbuchLineKind.STANDARD to
                                mapOf(
                                    "19" to AccountCell(8400), // Erlöse 19 % USt
                                    "7" to AccountCell(8300), // Erlöse 7 % USt
                                    "0" to AccountCell(8100), // Steuerfreie Umsätze § 4 Nr. 8 ff. UStG (placeholder, see manual)
                                ),
                            KassenbuchLineKind.PFAND to
                                mapOf(
                                    "19" to AccountCell(8400), // seeded to STANDARD; dedicated Pfand accounts are shop-specific
                                ),
                            KassenbuchLineKind.GUTSCHEIN to
                                mapOf(
                                    "19" to AccountCell(8720), // Erlösschmälerungen 19 % USt
                                    "7" to AccountCell(8710), // Erlösschmälerungen 7 % USt
                                ),
                        ),
                    expense =
                        mapOf(
                            KassenbuchLineKind.STANDARD to
                                mapOf(
                                    "19" to AccountCell(3400), // Wareneingang 19 % VSt
                                    "7" to AccountCell(3300), // Wareneingang 7 % VSt
                                    "0" to AccountCell(3200), // Wareneingang ohne VSt
                                ),
                            KassenbuchLineKind.PFAND to
                                mapOf(
                                    "19" to AccountCell(3400),
                                ),
                            KassenbuchLineKind.GUTSCHEIN to
                                mapOf(
                                    "19" to AccountCell(3720), // Nachlässe 19 % VSt
                                    "7" to AccountCell(3710), // Nachlässe 7 % VSt
                                ),
                        ),
                    paymentTypes =
                        nonCashPaymentTypes.associate {
                            it.id to if (it.isVoucher) AccountCell(1700) else AccountCell(1360) // Sonst. Verbindlichkeiten / Geldtransit
                        },
                    // Verified against DATEV SKR03 2026 (Art.-Nr. 11174) — see the object KDoc. The three
                    // 9000 cells are carried forward unchanged and still want a Steuerberater's review.
                    special =
                        mapOf(
                            KassenbuchSpecialBooking.GELDTRANSIT to AccountCell(1360), // Geldtransit
                            KassenbuchSpecialBooking.CASH_DEPOSIT to AccountCell(9000), // Saldenvorträge, Sachkonten
                            KassenbuchSpecialBooking.PRIVATE_DEPOSIT to AccountCell(1890), // Privateinlagen
                            KassenbuchSpecialBooking.CASH_WITHDRAWAL to AccountCell(9000),
                            KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL to AccountCell(1800), // Privatentnahmen allgemein
                            KassenbuchSpecialBooking.CASH_SURPLUS to AccountCell(8603), // Sonstige betriebliche Erträge
                            KassenbuchSpecialBooking.CASH_SHORTAGE to AccountCell(4900), // Sonstige betriebliche Aufwendungen
                            KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD to AccountCell(9000),
                            KassenbuchSpecialBooking.EMPLOYEE_TIP to AccountCell(EMPLOYEE_TIP_SKR03),
                        ),
                )

            Kontenrahmen.SKR04 ->
                MappingDto(
                    kontenrahmen = Kontenrahmen.SKR04,
                    kasseKonto = 1600,
                    income =
                        mapOf(
                            KassenbuchLineKind.STANDARD to
                                mapOf(
                                    "19" to AccountCell(4400), // Erlöse 19 % USt
                                    "7" to AccountCell(4300), // Erlöse 7 % USt
                                    "0" to AccountCell(4100), // Steuerfreie Umsätze § 4 Nr. 8 ff. UStG (placeholder, see manual)
                                ),
                            KassenbuchLineKind.PFAND to
                                mapOf(
                                    "19" to AccountCell(4400), // seeded to STANDARD; dedicated Pfand accounts are shop-specific
                                ),
                            KassenbuchLineKind.GUTSCHEIN to
                                mapOf(
                                    "19" to AccountCell(4720), // Erlösschmälerungen 19 % USt
                                    "7" to AccountCell(4710), // Erlösschmälerungen 7 % USt
                                ),
                        ),
                    expense =
                        mapOf(
                            KassenbuchLineKind.STANDARD to
                                mapOf(
                                    "19" to AccountCell(5400), // Wareneingang 19 % VSt
                                    "7" to AccountCell(5300), // Wareneingang 7 % VSt
                                    "0" to AccountCell(5200), // Wareneingang ohne VSt
                                ),
                            KassenbuchLineKind.PFAND to
                                mapOf(
                                    "19" to AccountCell(5400), // seeded to STANDARD; dedicated Pfand accounts are shop-specific
                                ),
                            KassenbuchLineKind.GUTSCHEIN to
                                mapOf(
                                    "19" to AccountCell(5720), // Nachlässe 19 % VSt
                                    "7" to AccountCell(5710), // Nachlässe 7 % VSt
                                ),
                        ),
                    paymentTypes =
                        nonCashPaymentTypes.associate {
                            it.id to if (it.isVoucher) AccountCell(3500) else AccountCell(1460) // Sonst. Verbindlichkeiten / Geldtransit
                        },
                    // Verified against DATEV SKR04 2026 (Art.-Nr. 11175) — see the object KDoc. Each
                    // cell mirrors its SKR03 counterpart; the 9000 cash-movement cells and the
                    // Kassendifferenz pair still want a Steuerberater's review, as in SKR03.
                    special =
                        mapOf(
                            KassenbuchSpecialBooking.GELDTRANSIT to AccountCell(1460), // Geldtransit
                            KassenbuchSpecialBooking.CASH_DEPOSIT to AccountCell(9000), // Saldenvorträge, Sachkonten
                            KassenbuchSpecialBooking.PRIVATE_DEPOSIT to AccountCell(2180), // Privateinlagen
                            KassenbuchSpecialBooking.CASH_WITHDRAWAL to AccountCell(9000),
                            KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL to AccountCell(2100), // Privatentnahmen allgemein
                            KassenbuchSpecialBooking.CASH_SURPLUS to AccountCell(4830), // Sonstige betriebliche Erträge
                            KassenbuchSpecialBooking.CASH_SHORTAGE to AccountCell(6300), // Sonstige betriebliche Aufwendungen
                            KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD to AccountCell(9000), // Saldenvorträge, Sachkonten
                            KassenbuchSpecialBooking.EMPLOYEE_TIP to AccountCell(EMPLOYEE_TIP_SKR04),
                        ),
                )
        }

    /** SKR03 1590 „Durchlaufende Posten" — employee tips held for the staff until paid out. */
    const val EMPLOYEE_TIP_SKR03 = 1590

    /** SKR04 1370 „Durchlaufende Posten" — the SKR04 counterpart of [EMPLOYEE_TIP_SKR03]. */
    const val EMPLOYEE_TIP_SKR04 = 1370

    /**
     * The special slots added AFTER shops had already stored a mapping, and therefore filled in on
     * READ when a stored mapping lacks them.
     *
     * `ShopConfigurationManager` deserializes the stored settings blob verbatim: a Kotlin default
     * backfills a missing FIELD, never a missing map KEY, so without this every shop configured
     * before the slot existed would fail `validate()` and meet the Buchungsstapel's 409
     * `DATEV_ACCOUNT_MAPPING_REQUIRED` the first time a tip was booked — on a mapping its operator
     * had completed. Only slots with a default that is correct for BOTH charts belong here. The
     * other special slots are seeded on a new install and by „Standardkonten übernehmen" only — a
     * stored mapping lacking them is never backfilled, because a mapping its operator saved is never
     * rewritten (see the object KDoc).
     */
    val BACKFILLED_SPECIAL_SLOTS: Set<KassenbuchSpecialBooking> = setOf(KassenbuchSpecialBooking.EMPLOYEE_TIP)

    /**
     * [mapping] with every [BACKFILLED_SPECIAL_SLOTS] entry it lacks filled from its own chart's
     * seed. A slot the mapping already carries is never touched, so an operator's own account wins,
     * and a mapping that needs nothing is returned unchanged (the same instance).
     */
    fun withBackfilledDefaults(mapping: MappingDto): MappingDto {
        val missing = BACKFILLED_SPECIAL_SLOTS.filterNot { it in mapping.special }
        if (missing.isEmpty()) return mapping
        val seeded = seed(mapping.kontenrahmen, emptyList()).special
        val added = missing.mapNotNull { slot -> seeded[slot]?.let { slot to it } }
        return mapping.copy(special = mapping.special + added)
    }
}
