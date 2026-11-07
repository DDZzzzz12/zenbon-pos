package org.example.project.export.datevbuchungsstapel.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Typed representation of a single Buchungsstapel data row (125 columns).
 *
 * Only the populated columns are represented as named fields. The remaining
 * ~113 columns are always empty in v1 and handled by the writer.
 */
data class BuchungsstapelRow(
    val umsatz: BigDecimal, // always positive
    val sollHabenKz: SollHaben,
    val konto: Int,
    val gegenkonto: Int,
    val belegdatum: LocalDate,
    val belegfeld1: String? = null,
    val belegfeld2: String? = null,
    val buchungstext: String,
    val buSchluessel: String? = null,
    val kost1: String? = null,
    val kost2: String? = null,
    val kostMenge: BigDecimal? = null,
) {
    enum class SollHaben { S, H }
}

/**
 * Input fields the server provides per line item. Lets the row generator stay
 * decoupled from the Exposed entity model — the server maps
 * KassenbuchHeader + KassenbuchLineItem into this shape.
 */
data class BuchungsstapelRowInput(
    val umsatz: BigDecimal, // signed; positive → S, negative → H
    val belegDate: LocalDate,
    val belegNr: String,
    val buchungstext: String,
    /**
     * The Kasse account. **Always the Kasse side** — the generator puts the Soll/Haben indicator
     * on this account, so anything else here inverts both legs of the booking.
     *
     * Was an `kontoOverride`/`defaultKonto` pair falling back to per-entry-type constants; those
     * constants mixed SKR03 and SKR04 and put the *contra* account on this side for five entry
     * types. The mapping is now the single source, so there is nothing to fall back to.
     */
    val konto: Int,
    /**
     * The contra account, resolved from the shop's account mapping — or null when the mapping
     * cell's „Konto automatisch zuordnen" switch is off. The field is then written EMPTY (not `0`):
     * DATEV marks it Necessary, so the Steuerberater imports with „Buchungen trotz Fehler
     * importieren" and completes the account there.
     */
    val gegenkonto: Int?,
    val bu: String? = null,
    val kost1: String? = null,
    val kost2: String? = null,
    val kostMenge: BigDecimal? = null,
    /** Pre-formatted DATEV Beleglink cell value (e.g. DatevBeleglink.cellValue(guid)), or null. */
    val belegLink: String? = null,
)
