package org.example.project.features.partner.domain.service

import org.example.project.core.domain.dto.partner.DatevStammdatenExportDto

/**
 * The master-data handover: every business partner's Personenkonten, as a DATEV
 * Debitoren-/Kreditorenstamm CSV.
 *
 * **One row per account, not per party.** DATEV's format is one row per Personenkonto, so a company
 * we both buy from and sell to genuinely has two accounts and cannot be one row. What keeps the
 * advisor from having to spot and reconcile two unrelated-looking rows is field 232, `Verknüpftes
 * OPOS-Konto`, which names the other account on each. That is informative only — DATEV performs no
 * Verrechnung on it — which is exactly what is wanted here: presentation, explicitly not netting.
 *
 * Transient by design, unlike the Buchungsstapel: master data is a snapshot of *now*, has no period,
 * and nothing downstream needs a `datev_export_log` row proving which version was handed over.
 */
interface DatevStammdatenExportService {
    /**
     * Builds the file for every non-merged party of the shop.
     *
     * Never fails because of the data: a role with no account number is left out of the file and
     * named in [DatevStammdatenExportDto.ResultDto.skipped]. It *does* fail when the shop's DATEV
     * configuration is missing, because Beraternummer, Mandantennummer and Sachkontenrahmen decide
     * where the file goes and how DATEV reads it — a guessed default there sends a correct file to
     * the wrong advisor.
     */
    suspend fun exportTransient(): DatevStammdatenExportDto.ResultDto
}
