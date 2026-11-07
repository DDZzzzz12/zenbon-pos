package org.example.project.features.salesinvoice.data.service

import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.VatRateNormalizer

/**
 * Resolves the three DATEV accounts for one VAT-rate split of a standalone sales invoice (slice 4d).
 * Pure: no DB, no config reads — the caller passes the already-read booking values, debtor number,
 * Sammeldebitor and account mapping. A null return means "unresolvable" — the caller collects it for
 * the preflight and never emits a guessed account.
 */
object SalesInvoiceDatevAccountResolver {
    /** booking.bpAccountNo -> debtor.debtorNo -> shop Sammeldebitor -> null. */
    fun personenkonto(
        bookingBp: String?,
        debtorNo: String?,
        sammeldebitor: String?,
    ): String? = bookingBp?.trimOrNull() ?: debtorNo?.trimOrNull() ?: sammeldebitor?.trimOrNull()

    /**
     * booking.revenueAccountNo -> DatevAccountMapping income[STANDARD][rate].konto -> null.
     *
     * Also null when that cell's „Konto automatisch zuordnen" switch is off; [unassignedCell] tells
     * that case apart from an unmapped rate, which the preflight still refuses.
     */
    fun erloeskonto(
        bookingRev: String?,
        rate: String,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): String? {
        bookingRev?.trimOrNull()?.let { return it }
        return revenueCell(rate, mapping)?.assignedKonto?.toString()
    }

    /**
     * The income STANDARD cell for [rate] when it has „Konto automatisch zuordnen" switched off and
     * the booking names no revenue account of its own; else null. Such a split is exported without
     * `accountNo`/`buCode` — not refused — and DATEV completes the account.
     */
    fun unassignedCell(
        bookingRev: String?,
        rate: String,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): CellRef? {
        if (bookingRev?.trimOrNull() != null) return null
        val normalized = VatRateNormalizer.normalize(rate) ?: return null
        val cell = revenueCell(rate, mapping) ?: return null
        if (cell.autoAssign) return null
        return CellRef(side = DatevMappingSide.INCOME, kind = KassenbuchLineKind.STANDARD, rate = normalized)
    }

    private fun revenueCell(
        rate: String,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): DatevAccountMappingDto.AccountCell? {
        val normalized = VatRateNormalizer.normalize(rate) ?: return null
        return mapping?.income?.get(KassenbuchLineKind.STANDARD)?.get(normalized)
    }

    /**
     * booking.buCode -> mapping cell bu -> derived from vatCategoryCode -> null (Automatikkonto).
     *
     * No existing DATEV export code in this repo (Kassenbuch, AP) assigns a BU-Schlüssel for
     * reverse-charge/intra-community VAT category codes ("AE" per EN 16931 / UNTDID 5305) — grepped
     * for "reverse"/"94"/"AE"/"intra" under export/ and found nothing authoritative to match. Rather
     * than invent a BU key, every vatCategoryCode (including "AE") falls through to null here, which
     * leaves DATEV's own Automatikkonto logic to apply. Revisit once a real reverse-charge convention
     * exists elsewhere in the codebase.
     */
    fun buCode(
        bookingBu: String?,
        rate: String,
        vatCategoryCode: String?,
        mapping: DatevAccountMappingDto.MappingDto?,
    ): String? {
        bookingBu?.trimOrNull()?.let { return it }
        revenueCell(rate, mapping)?.assignedBu?.let { return it }
        // No vatCategoryCode currently derives a BU key (see class doc); every code, "AE" included,
        // falls through to null (Automatikkonto). vatCategoryCode is accepted for a future derived
        // rung and is intentionally unused today.
        return null
    }

    private fun String.trimOrNull(): String? = trim().ifBlank { null }
}
