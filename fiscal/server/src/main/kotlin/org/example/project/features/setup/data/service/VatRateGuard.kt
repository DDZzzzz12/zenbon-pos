package org.example.project.features.setup.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.payment.domain.service.VatRateAdmission
import org.example.project.infrastructure.errors.PosException

/**
 * Single-source guard rejecting VAT rates that DSFinV-K cannot map to a real Anlage-2 key
 * (round-3 STAM-4).
 *
 * Shared by EVERY rate-config path — [VatServiceImpl.createVatRate]/[VatServiceImpl.updateVatRate]
 * AND [InitialSetupServiceImpl.executeVatSetup] (the primary setup-wizard path) — so the guard
 * cannot be bypassed by configuring a custom rate through the wizard (CLAUDE.md rule 8: guard at
 * every level, one source of truth). The mappable set itself lives in
 * [DsfinvkExportDto.VatKeyMapping.MAPPED_RATES].
 *
 * A rate outside that set exports under UST_SCHLUESSEL 7 (NICHT_ERMITTELBAR) carrying a NON-ZERO
 * computed USt while vat.csv declares key 7 = 0,00 % — an arithmetic contradiction.
 */
object VatRateGuard : VatRateAdmission {
    /** Throws a 422 [PosException] if [rate] (a decimal fraction, e.g. 0.19) is not DSFinV-K-mappable. */
    override fun requireMappable(rate: Double) {
        if (!DsfinvkExportDto.VatKeyMapping.isMappableRate(rate)) {
            throw unmappableVatRate(rate)
        }
    }

    private fun unmappableVatRate(rate: Double): PosException {
        val allowed =
            DsfinvkExportDto.VatKeyMapping.MAPPED_RATES
                .sorted()
                .joinToString(", ") { "${formatPercent(it)}%" }
        return PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.UnprocessableEntity,
            "VAT rate ${formatPercent(rate)}% is not a DSFinV-K-mappable rate — allowed: $allowed. " +
                "An unmapped rate would export under UST_SCHLUESSEL 7 with a non-zero USt while " +
                "vat.csv declares key 7 = 0,00 (round-3 STAM-4).",
        )
    }

    /** Formats a decimal-fraction VAT rate (0.078) as a trimmed percent string ("7.8"). */
    private fun formatPercent(rate: Double): String {
        val pct = Math.round(rate * 10000) / 100.0
        return if (pct % 1.0 == 0.0) pct.toInt().toString() else pct.toString()
    }
}
