package org.example.project.core.domain.dto.datev

import kotlinx.serialization.Serializable

/**
 * The export's notice that [lineCount] lines carry no contra account because their cell's
 * „Konto automatisch zuordnen" switch is off. The Steuerberater must import the Buchungsstapel
 * with „Buchungen trotz Fehler importieren" and complete the accounts in DATEV.
 */
@Serializable
data class UnassignedSummary(
    val lineCount: Int,
    val bookings: List<UnassignedBooking>,
) {
    companion object {
        /** Groups the switched-off cells of an export's lines; null when there were none. */
        fun of(cells: List<CellRef>): UnassignedSummary? {
            if (cells.isEmpty()) return null
            val bookings = cells.groupingBy { it }.eachCount().map { (cell, count) -> UnassignedBooking(cell, count) }
            return UnassignedSummary(lineCount = cells.size, bookings = bookings)
        }
    }
}
