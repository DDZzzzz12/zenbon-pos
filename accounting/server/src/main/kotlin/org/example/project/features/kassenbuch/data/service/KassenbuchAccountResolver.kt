package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.VatRateNormalizer
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem

/**
 * Resolves the DATEV accounts for a single Kassenbuch line item from the
 * configured account mapping (spec §3.4).
 *
 * Whole-line semantics: any missing piece (no mapping, unmapped entry type,
 * missing cell, unknown VAT rate, null/unknown paymentTypeId) yields [Resolution.Legacy]
 * and the CALLER applies its own pre-existing behavior — the Buchungsstapel
 * path's entry-type defaults, the XML path's raw header fields. This keeps
 * unconfigured shops and unresolvable lines byte-identical to today and never
 * produces mixed rows (mapped Kasse konto + legacy Gegenkonto).
 *
 * On [Resolution.Legacy], headerGegenkonto/headerBu are intentionally ignored
 * by this function — the caller's legacy path is fully responsible for applying
 * header overrides, exactly as the pre-existing export code does.
 */
object KassenbuchAccountResolver {
    sealed interface Resolution {
        /**
         * Use the mapping: [konto] = Kasse account, [gegenkonto]/[bu] per line.
         *
         * [gegenkonto] is null exactly when the line's cell has „Konto automatisch zuordnen"
         * switched off and the entry carries no Gegenkonto of its own; [unassignedCell] then names
         * that cell (and [bu] is null too). Exporters write the contra account EMPTY for such a
         * line and report it, so the Steuerberater completes it in DATEV.
         */
        data class Mapped(
            val konto: Int,
            val gegenkonto: Int?,
            val bu: String?,
            val unassignedCell: CellRef? = null,
        ) : Resolution

        /** Fall back to the caller's pre-existing account behavior. */
        data object Legacy : Resolution
    }

    /**
     * Resolves the DATEV accounts for one line item.
     *
     * [headerBu] and [ustSatz] are both `String?` — call sites should use named
     * arguments to avoid silently swapping them.
     *
     * Null fields on [Resolution.Mapped] are allowed: [Resolution.Mapped.bu] may
     * be null when neither the header nor the mapping cell defines one — a
     * deliberate divergence from the spec pseudocode's `""` (callers render null
     * as an empty BU column themselves).
     *
     * @param entryType Kassenbuch entry type; its `mappingSide` gates whether any
     *   mapping lookup happens at all (null side -> [Resolution.Legacy])
     * @param headerGegenkonto per-entry Gegenkonto override from the entry header;
     *   when non-null it replaces the cell's Konto on [Resolution.Mapped]
     * @param headerBu per-entry BU-Schlüssel override from the entry header;
     *   non-blank values replace the cell's BU on [Resolution.Mapped]
     * @param kind line-item kind selecting the lookup strategy
     *   ([KassenbuchLineKind.specialBooking] -> that special slot, whatever the entry type;
     *   [KassenbuchLineKind.resolvesByPaymentType] -> payment-type table,
     *   others -> kind x rate table)
     * @param ustSatz raw VAT-rate string of the line; normalized via
     *   [VatRateNormalizer] before the kind x rate lookup (ignored for
     *   payment-type-resolved lines)
     * @param paymentTypeId payment type resolving the rate-less kinds
     *   (NON_CASH_PAYMENT, RECEIVABLE_SETTLEMENT); null/unknown id ->
     *   [Resolution.Legacy] (ignored for other kinds)
     * @param mapping the shop's configured account mapping; null = unconfigured ->
     *   [Resolution.Legacy]
     */
    fun resolve(
        entryType: KassenbuchEntryType,
        headerGegenkonto: Int?,
        headerBu: String?,
        kind: KassenbuchLineKind,
        ustSatz: String?,
        paymentTypeId: Long?,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): Resolution {
        if (mapping == null) return Resolution.Legacy

        // A LINE kind with its own special slot wins over everything the entry type would choose:
        // an employee tip sits inside the Z-Bon's CASH_SALE aggregate (whose rate axis would book
        // it as revenue) and inside a tip payout's CASH_WITHDRAWAL (whose special slot is a
        // different account), and both legs must meet on the ONE account the tip is held on.
        // No mappingSide gate either: the slot is entry-type independent by construction.
        val lineSpecial = kind.specialBooking
        if (lineSpecial != null) {
            val tipCell = mapping.special[lineSpecial] ?: return Resolution.Legacy
            return mapped(mapping, tipCell, CellRef(special = lineSpecial), headerGegenkonto, headerBu)
        }

        // Special bookings take precedence and ignore rate/payment-type entirely: a Geldtransit or
        // a Privatentnahme has one contra account and no VAT axis to look up. [specialBooking] and
        // [mappingSide] partition the entry types, so exactly one branch applies.
        val special = entryType.specialBooking
        val (cell, ref) =
            if (special != null) {
                mapping.special[special] to CellRef(special = special)
            } else {
                val side = entryType.mappingSide ?: return Resolution.Legacy
                // The rate-less kinds carry no VAT axis to look up, so they resolve on the
                // payment-type axis instead. A RECEIVABLE_SETTLEMENT resolves through the tender
                // that OPENED the receivable, which is the same cell its NON_CASH_PAYMENT opening
                // leg booked through — settling a receivable clears exactly the account opening it
                // debited. Without this branch every cash Forderungsausgleich fell to Legacy and
                // 409'd the whole period's Buchungsstapel.
                if (kind.resolvesByPaymentType) {
                    val id = paymentTypeId ?: return Resolution.Legacy
                    mapping.paymentTypes[id] to CellRef(paymentTypeId = id)
                } else {
                    val rate = VatRateNormalizer.normalize(ustSatz) ?: return Resolution.Legacy
                    val table = if (side == DatevMappingSide.INCOME) mapping.income else mapping.expense
                    table[kind]?.get(rate) to CellRef(side = side, kind = kind, rate = rate)
                }
            }
        return mapped(mapping, cell ?: return Resolution.Legacy, ref, headerGegenkonto, headerBu)
    }

    /**
     * [resolve] for one line of [header], deriving every argument the way both exporters must: a
     * Storno through the entry it reverses (F64, [KassenbuchBookingSource]), and a NON-numeric
     * `header.gegenkonto` dropped (spec §3.4 accepted edge — in a mapped shop the cell wins).
     */
    fun resolveLine(
        header: KassenbuchHeader,
        line: KassenbuchLineItem,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): Resolution {
        val bookedAs = KassenbuchBookingSource.of(header)
        return resolve(
            entryType = bookedAs.entryType,
            headerGegenkonto = bookedAs.gegenkonto?.toIntOrNull(),
            headerBu = bookedAs.bu,
            kind = line.kind,
            ustSatz = line.ustSatz,
            paymentTypeId = line.paymentType?.id?.value,
            mapping = mapping,
        )
    }

    /**
     * The [Resolution.Mapped] for a found [cell].
     *
     * The entry's own Gegenkonto still wins over a switched-off cell: the switch withholds the
     * mapping's AUTOMATIC account, and an account the operator typed on the entry is not automatic.
     * Only when neither exists is the contra account left empty — and then the BU-Schlüssel too,
     * since a BU with no account to apply it to is meaningless to DATEV.
     *
     * A cell whose switch is ON but which holds no account (refused on save, but a stored blob is
     * never re-validated) resolves to [Resolution.Legacy], so the caller refuses the line rather than
     * silently exporting it unassigned.
     */
    private fun mapped(
        mapping: DatevAccountMappingDto.MappingDto,
        cell: DatevAccountMappingDto.AccountCell,
        ref: CellRef,
        headerGegenkonto: Int?,
        headerBu: String?,
    ): Resolution {
        val overrideBu = headerBu?.takeIf { it.isNotBlank() }
        if (headerGegenkonto != null) {
            return Resolution.Mapped(mapping.kasseKonto, headerGegenkonto, overrideBu ?: cell.assignedBu)
        }
        if (!cell.autoAssign) {
            return Resolution.Mapped(mapping.kasseKonto, gegenkonto = null, bu = null, unassignedCell = ref)
        }
        val konto = cell.konto ?: return Resolution.Legacy
        return Resolution.Mapped(mapping.kasseKonto, konto, overrideBu ?: cell.bu)
    }
}
