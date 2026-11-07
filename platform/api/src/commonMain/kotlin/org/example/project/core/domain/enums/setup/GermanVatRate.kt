package org.example.project.core.domain.enums.setup

/**
 * The DSFinV-K Anlage-2 VAT rates, split by whether a shop may still choose one.
 *
 * [configurable] is false for a rate that no longer applies. It must stay mappable so historical
 * data still exports under its own Anlage-2 ID — that is what keeps `stamm_ust` truthful, since a
 * line may only reference a key whose declared rate really applied — but offering it in a picker
 * would let an operator configure a rate that has not been legal for years.
 *
 * Keys 1–5 carry whatever rate is statutorily current; every superseded rate has its own fixed ID
 * from 11 upward. When a statutory rate changes, flip the outgoing constant's [configurable] to
 * false and add the incoming one — the tests pin this against `VatKeyMapping`.
 */
enum class GermanVatRate(
    val rate: Double,
    val dsfinvkKey: Int,
    val configurable: Boolean,
) {
    /** 19 % allgemeiner Steuersatz. */
    GENERAL_19(0.19, 1, configurable = true),

    /** 7 % ermäßigter Steuersatz. */
    REDUCED_7(0.07, 2, configurable = true),

    /** 7,8 % Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG — current since 2025. */
    AVERAGE_78(0.078, 3, configurable = true),

    /** 5,5 % Durchschnittssatz § 24 Abs. 1 Nr. 1 UStG. */
    AVERAGE_55(0.055, 4, configurable = true),

    /** 0 % — nicht steuerbare Umsätze. */
    ZERO(0.0, 5, configurable = true),

    /** 16 % historischer allgemeiner Steuersatz (07–12/2020). */
    HIST_GENERAL_16(0.16, 21, configurable = false),

    /** 5 % historischer ermäßigter Steuersatz (07–12/2020). */
    HIST_REDUCED_5(0.05, 22, configurable = false),

    /** 8,4 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG (until 2025). */
    HIST_AVG3_84(0.084, 43, configurable = false),

    /** 9,0 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG. */
    HIST_AVG3_90(0.09, 33, configurable = false),

    /** 9,5 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG. */
    HIST_AVG3_95(0.095, 23, configurable = false),

    /** 10,7 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG. */
    HIST_AVG3_107(0.107, 13, configurable = false),
    ;

    companion object {
        /** The rates a shop may choose today, in descending order for display. */
        val configurableRates: List<GermanVatRate> =
            entries.filter { it.configurable }.sortedByDescending { it.rate }
    }
}
