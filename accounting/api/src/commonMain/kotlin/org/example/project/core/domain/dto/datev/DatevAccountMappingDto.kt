/**
 * Container for the DATEV account-mapping configuration DTOs
 * (ConfigKey DATEV_ACCOUNT_MAPPING, spec §3).
 */
package org.example.project.core.domain.dto.datev

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.VatRateNormalizer

object DatevAccountMappingDto {
    /**
     * Response for GET /config/{shopId}/datev-account-mapping.
     * `mapping` is null when the shop has no mapping configured — the DATEV
     * exports then use the legacy per-entry-type default accounts.
     */
    @Serializable
    data class MappingResponseDto(
        val shopId: Long,
        val mapping: MappingDto? = null,
    )

    /**
     * One mapping cell: a DATEV account plus an optional BU-Schlüssel.
     * BU stays null for automatic accounts (they carry their own tax logic;
     * a BU on an automatic account is a DATEV import error).
     *
     * [autoAssign] is the operator's „Konto automatisch zuordnen" switch. When it is off, every
     * export leaves this cell's contra account EMPTY — the Buchungsstapel's Gegenkonto and
     * BU-Schlüssel, the DATEV XML's `accountNo` and `buCode` — and the Steuerberater completes the
     * account in DATEV after importing. It exists because not every seeded default has been
     * confirmed by a Steuerberater, and an empty account is visible in DATEV where a plausible wrong
     * one is not. [konto] and [bu] are kept (and ignored) while the switch is off, so switching it
     * back on restores them; [konto] may be null only then.
     *
     * **[autoAssign] defaults to `true` on purpose**, unlike the stored-shape settings classes that
     * declare no defaults (`ShopConfigurationDto.DatevXmlConfig`). Every mapping stored before the
     * switch existed lacks the key and must read as ON — the behaviour it was saved under — and the
     * only reader of the stored blob is this same class, so an omitted `true` (the save path encodes
     * with `encodeDefaults = false`) round-trips to `true`. An all-ON mapping therefore stores
     * byte-identically to before; only an OFF cell writes `"autoAssign":false`.
     */
    @Serializable
    data class AccountCell(
        val konto: Int?,
        val bu: String? = null,
        val autoAssign: Boolean = true,
    ) {
        /** The contra account an export books, or null when the switch is off (or, invalidly, unset). */
        val assignedKonto: Int? get() = if (autoAssign) konto else null

        /** The BU-Schlüssel an export writes; null whenever the switch is off. */
        val assignedBu: String? get() = if (autoAssign) bu else null
    }

    /**
     * The per-shop account mapping. Absent config key = unconfigured =
     * full legacy fallback (spec §3.4). Rate keys are canonical
     * VatRateNormalizer output ("19"/"7"/"0").
     */
    @Serializable
    data class MappingDto(
        val kontenrahmen: Kontenrahmen,
        val kasseKonto: Int,
        /** kind -> canonical vatRate -> cell. NON_CASH_PAYMENT is never present here. */
        val income: Map<KassenbuchLineKind, Map<String, AccountCell>> = emptyMap(),
        val expense: Map<KassenbuchLineKind, Map<String, AccountCell>> = emptyMap(),
        /** paymentTypeId -> clearing/liability cell; resolves NON_CASH_PAYMENT lines. */
        val paymentTypes: Map<Long, AccountCell> = emptyMap(),
        /**
         * Contra account for bookings that depend on neither VAT rate nor payment type —
         * transfers, private movements, cash differences, balance carries.
         *
         * Every [KassenbuchSpecialBooking] must be present ([validate] enforces it). These used to
         * resolve through hardcoded per-entry-type constants that mixed SKR03 and SKR04, so an
         * SKR04 shop silently exported SKR03's Kasse and Privatentnahme accounts.
         */
        val special: Map<KassenbuchSpecialBooking, AccountCell> = emptyMap(),
    ) {
        /** @return validation error messages; empty if the mapping is storable. */
        fun validate(): List<String> {
            val errors = mutableListOf<String>()

            fun checkKonto(
                konto: Int,
                where: String,
            ) {
                if (konto !in 1..99_999_999) errors.add("$where: Konto must be 1..99999999")
            }

            // A switched-off cell is exported without an account, so whatever it still holds is
            // ignored here too — it may be empty. Only a cell that assigns must carry a valid one.
            fun checkCell(
                cell: AccountCell,
                where: String,
            ) {
                if (!cell.autoAssign) return
                val konto = cell.konto
                if (konto == null) {
                    errors.add("$where: no account configured (switch off automatic assignment to leave it empty)")
                    return
                }
                checkKonto(konto, where)
                cell.bu?.let { if (it.isBlank() || it.length > 4) errors.add("$where: BU must be 1..4 characters") }
            }
            checkKonto(kasseKonto, "kasseKonto")
            listOf("income" to income, "expense" to expense).forEach { (sideName, side) ->
                side.forEach { (kind, cells) ->
                    if (kind == KassenbuchLineKind.NON_CASH_PAYMENT) {
                        errors.add("$sideName.$kind: NON_CASH_PAYMENT is mapped per payment type, not per rate")
                    }
                    // A rate-table cell for a special-booking kind would never be read (the
                    // resolver takes special[...] first), so storing one only misleads the reader.
                    kind.specialBooking?.let { slot ->
                        errors.add("$sideName.$kind: $kind is mapped via special.$slot, not per rate")
                    }
                    cells.forEach { (rate, cell) ->
                        if (VatRateNormalizer.normalize(rate) != rate) {
                            errors.add("$sideName.$kind: rate key '$rate' is not canonical")
                        }
                        checkCell(cell, "$sideName.$kind.$rate")
                    }
                }
            }
            paymentTypes.forEach { (id, cell) ->
                if (id <= 0) errors.add("paymentTypes.$id: paymentTypeId must be positive")
                checkCell(cell, "paymentTypes.$id")
            }
            // Every special slot must be filled. No fallback exists by design: these bookings used
            // to resolve through a hardcoded SKR03/SKR04 mix, and a plausible-looking wrong contra
            // account is not detectable anywhere downstream. A blocked export beats a wrong one.
            // The slot must still be PRESENT when its switch is off: an absent slot is a gap nobody
            // decided on, a switched-off one is the operator's explicit "leave it to DATEV".
            KassenbuchSpecialBooking.entries.forEach { slot ->
                val cell = special[slot]
                if (cell == null) {
                    errors.add("special.$slot: no account configured")
                } else {
                    checkCell(cell, "special.$slot")
                }
            }
            return errors
        }

        /**
         * Every cell whose „Konto automatisch zuordnen" switch is ON but which holds no account, named
         * like [validate] names them. [validate] already refuses these on save; the Buchungsstapel
         * gate asks again because a stored blob is read verbatim and never re-validated.
         */
        fun autoAssignedCellsWithoutAccount(): List<String> {
            fun missing(cell: AccountCell) = cell.autoAssign && cell.konto == null
            val rateCells =
                listOf("income" to income, "expense" to expense).flatMap { (sideName, side) ->
                    side.flatMap { (kind, cells) ->
                        cells.filterValues(::missing).keys.map { rate -> "$sideName.$kind.$rate" }
                    }
                }
            return rateCells +
                paymentTypes.filterValues(::missing).keys.map { "paymentTypes.$it" } +
                special.filterValues(::missing).keys.map { "special.$it" }
        }
    }
}
