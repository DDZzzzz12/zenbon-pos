package org.example.project.features.kassenbuch.data.service

import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import java.math.BigDecimal

/**
 * Counts the Kassenbuch lines an export writes without a contra account because their mapping
 * cell's „Konto automatisch zuordnen" switch is off.
 *
 * Shared by every Kassenbuch DATEV export (Buchungsstapel, Buchungsstapel mit Belegen, XML) so the
 * notice the operator sees cannot disagree between them. It resolves each line through the same
 * [KassenbuchAccountResolver.resolveLine] the exporters use and skips zero-amount lines, which no
 * exporter writes. Pure over the passed headers: it never refuses anything — an unresolvable line is
 * the exporter's to refuse.
 */
object KassenbuchUnassignedContra {
    /** @return the summary of switched-off lines in [headers], or null when there were none. */
    fun collect(
        headers: List<KassenbuchHeader>,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): UnassignedSummary? {
        if (mapping == null) return null
        val cells =
            headers.flatMap { header ->
                header.lineItems
                    .filter { it.vorzBetrag.compareTo(BigDecimal.ZERO) != 0 }
                    .mapNotNull { line ->
                        val resolution = KassenbuchAccountResolver.resolveLine(header, line, mapping)
                        val ref = (resolution as? KassenbuchAccountResolver.Resolution.Mapped)?.unassignedCell
                        // The export screen has no payment-type list, so the name travels with the ref.
                        ref?.takeIf { it.paymentTypeId != null }?.copy(paymentTypeName = line.paymentType?.name) ?: ref
                    }
            }
        return UnassignedSummary.of(cells)
    }
}
