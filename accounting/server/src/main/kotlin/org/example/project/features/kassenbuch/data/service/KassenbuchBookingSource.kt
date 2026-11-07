package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader

/**
 * The header whose booking decides a Kassenbuch entry's DATEV accounts (F64).
 *
 * For every ordinary entry that is the entry itself. A Storno — a `CORRECTION_IN`/`CORRECTION_OUT`
 * linked to the entry it reverses through `correctsHeader` — is NOT a Geschäftsvorfall of its own:
 * it reverses the original booking, so it must hit the original's accounts with the opposite sign.
 * Resolving it through its own entry type instead put every Storno on the generic income or
 * expense table: a Privateinlage of +20 in a Tresor exported as `1010 / Privateinlage`, while its
 * Storno exported as `1010 / 8100` — a revenue reduction that never existed, and the Privateinlage
 * itself never reversed in the ledger. The same happened to Privatentnahmen, Barentnahmen,
 * Kassendifferenzen (whose rate-less lines then failed to resolve at all and refused the export)
 * and to the Storno of a Rückerstattung, which landed on the EXPENSE side.
 *
 * The amount already carries the reversal: the Storno's line `vorzBetrag` is the original's
 * cash-flow sign inverted, so the row generator turns the same account pair into the opposite
 * S/H. Only the accounts are taken from the original — entry type plus the header's own
 * Gegenkonto/BU override — and the line kind, rate and payment type stay the Storno's own, which
 * `createCorrection` copied from the original line by line.
 *
 * The DSFinV-K side already works this way (`KassenbuchDsfinvkMapping.getGvTyp(entryType,
 * correctedEntryType)`), so DATEV and DSFinV-K now agree on what a Storno is.
 *
 * A `CORRECTION_*` without a link (legacy rows) keeps its own entry type. A corrected RE_ENTRY
 * carries the link too, but it is a real booking with an operator-chosen entry type, never a
 * `CORRECTION_*`, and is therefore unaffected.
 */
object KassenbuchBookingSource {
    private val reversalTypes = setOf(KassenbuchEntryType.CORRECTION_IN, KassenbuchEntryType.CORRECTION_OUT)

    /** The header to read entry type and Gegenkonto/BU overrides from when resolving [header]'s accounts. */
    fun of(header: KassenbuchHeader): KassenbuchHeader {
        if (header.entryType !in reversalTypes) return header
        return header.correctsHeader ?: header
    }
}
