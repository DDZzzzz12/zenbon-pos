package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException

/**
 * Refuses a Buchungsstapel export when the shop has no DATEV account mapping configured.
 *
 * [KassenbuchAccountResolver] returns `Legacy` for every line when the mapping is null. This gate
 * exists so that case is refused HERE, with a message naming the missing configuration, rather than
 * surfacing later as a per-line failure from [KassenbuchBuchungsstapelRowMapper] that names an
 * arbitrary first Beleg.
 *
 * **The entry-type default fallback this KDoc used to describe is gone.** It said the exporters
 * "fall back to `KassenbuchEntryType.getDefaultAccountNumber` / `getDefaultCounterAccountNumber`";
 * those constants were a fixed SKR03/SKR04 mix that also put the contra account in Konto for five
 * entry types, and they were deleted. [KassenbuchBuchungsstapelRowMapper] now THROWS on `Legacy`.
 * See [PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED] for the three mis-bookings the defaults caused.
 * A wrong Buchungsstapel is worse than a missing one, because nothing downstream flags it.
 *
 * Deliberately **not** applied to the DATEV XML Kassenbuch export: its `Legacy` branch carries the
 * operator's own `header.gegenkonto` / `header.bu` through unchanged rather than substituting
 * entry-type defaults, so it does not fabricate accounts and stays usable unconfigured.
 */
object DatevBuchungsstapelMappingGate {
    /**
     * Where an operator edits the DATEV account mapping, for every refusal that sends them there.
     * It lives in the web back office's Compliance group, not in any "Settings > DATEV" screen —
     * the older wording named a place that does not exist (T5-7).
     */
    const val MAPPING_LOCATION = "Compliance > Data export (Datenexport) in the web back office"

    /**
     * @return the mapping, guaranteed non-null
     * @throws PosException 409 [PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED] when unconfigured, or
     *   when a cell with „Konto automatisch zuordnen" ON holds no account. A cell with the switch
     *   OFF may be empty — its lines are exported with an empty Gegenkonto by the operator's choice.
     */
    fun requireConfigured(mapping: DatevAccountMappingDto.MappingDto?): DatevAccountMappingDto.MappingDto {
        val configured = mapping ?: throw unconfigured()
        val empty = configured.autoAssignedCellsWithoutAccount()
        if (empty.isNotEmpty()) {
            throw PosException(
                PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED,
                HttpStatusCode.Conflict,
                "The DATEV account mapping assigns accounts automatically for ${empty.joinToString(", ")} " +
                    "but holds no account there. Enter the account, or switch off \"Konto automatisch " +
                    "zuordnen\" for it to leave the account to DATEV, under $MAPPING_LOCATION and export again.",
            )
        }
        return configured
    }

    private fun unconfigured(): PosException =
        PosException(
            PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED,
            HttpStatusCode.Conflict,
            "No DATEV account mapping is configured for this shop. Exporting without one would " +
                "reverse cash-expense bookings, book every VAT rate to the 19 % automatic account " +
                "and subtract cashless takings from turnover. Configure the mapping under " +
                "$MAPPING_LOCATION (SKR03/SKR04 defaults are pre-filled) and export again.",
        )
}
