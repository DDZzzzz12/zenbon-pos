/**
 * DSFinV-K and TSE-TAR export state.
 *
 * Split out of `DatevSettingsState.kt` so the TSE export sections can live in `shared-ui` while the
 * DATEV-only state stays in adminApp. Same package, different filename — a same-package split
 * MUST NOT reuse the filename, or both modules emit the same facade class.
 */
package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.tse.TseDto

/**
 * State for DSFinV-K export section.
 *
 * @property isExpanded Whether the section is expanded
 * @property startDate Start date for export (ISO format YYYY-MM-DD)
 * @property endDate End date for export (ISO format YYYY-MM-DD)
 * @property includeTseTar Whether to include TSE TAR archive
 * @property validationResult Result of data validation
 * @property statistics Export statistics preview
 * @property isLoadingValidation Whether validation is in progress
 * @property isLoadingStatistics Whether statistics are loading
 * @property isExporting Whether export is in progress
 */
data class DsfinvkExportState(
    val isExpanded: Boolean = false,
    val startDate: String = "",
    val endDate: String = "",
    val includeTseTar: Boolean = false,
    val validationResult: DsfinvkExportDto.ValidationResultDto? = null,
    val statistics: DsfinvkExportDto.ExportStatisticsDto? = null,
    val isLoadingValidation: Boolean = false,
    val isLoadingStatistics: Boolean = false,
    val isExporting: Boolean = false,
) {
    /**
     * Check if date range is valid for operations.
     */
    fun hasValidDateRange(): Boolean = startDate.isNotBlank() && endDate.isNotBlank() && startDate <= endDate

    /**
     * Check if any operation is in progress.
     */
    fun isOperationInProgress(): Boolean = isLoadingValidation || isLoadingStatistics || isExporting

    /**
     * Check if export can proceed based on validation.
     */
    fun canExport(): Boolean =
        hasValidDateRange() &&
            !isOperationInProgress() &&
            (validationResult?.canExport != false)
}

/**
 * Available TSE export types.
 */
enum class TseExportType {
    FULL,
    INCREMENTAL,
    TIME_FILTERED,
}

/**
 * State for TSE data export section.
 *
 * @property isExpanded Whether the section is expanded
 * @property selectedExportType Currently selected export type
 * @property startDate Start date for time-filtered export (ISO format YYYY-MM-DD)
 * @property endDate End date for time-filtered export (ISO format YYYY-MM-DD)
 * @property exportSize Export size estimation result
 * @property exportResult Result of the last export operation
 * @property isLoadingSize Whether size calculation is in progress
 * @property isExporting Whether export is in progress
 * @property isDeleting Whether delete is in progress
 * @property showDeleteConfirmation Whether to show delete confirmation dialog
 * @property clientOptions Registers with a TSE client, offered by the time-filtered export's "Kasse" selector
 * @property selectedClientId The register to export; null = all registers (the default, F69)
 * @property archiveFileName File name typed for "Archiv registrieren" (prefilled with an unregistered export-and-delete archive)
 * @property isRegisteringArchive Whether an archive registration is in progress
 * @property registeredArchive The archive the last registration entered in the register
 * @property unregisteredArchiveFileName An export-and-delete archive the server could not register: the
 *   TSE is already erased, so the operator is warned to register it by hand
 */
data class TseExportState(
    val isExpanded: Boolean = false,
    val selectedExportType: TseExportType = TseExportType.FULL,
    val startDate: String = "",
    val endDate: String = "",
    val exportSize: TseDto.ExportSizeDto? = null,
    val exportResult: TseDto.ExportResultDto? = null,
    val isLoadingSize: Boolean = false,
    val isExporting: Boolean = false,
    val isDeleting: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val clientOptions: List<TseSetupTerminalOption> = emptyList(),
    val selectedClientId: String? = null,
    val archiveFileName: String = "",
    val isRegisteringArchive: Boolean = false,
    val registeredArchive: TseDto.ExportArchiveDto? = null,
    val unregisteredArchiveFileName: String? = null,
) {
    /**
     * Check if any operation is in progress.
     */
    fun isOperationInProgress(): Boolean = isLoadingSize || isExporting || isDeleting || isRegisteringArchive

    /** "Archiv registrieren" needs a file name; the server re-validates it regardless (rule 8). */
    fun canRegisterArchive(): Boolean = archiveFileName.isNotBlank() && !isOperationInProgress()

    /**
     * Check if time-filtered export has valid date range.
     */
    fun hasValidDateRange(): Boolean = startDate.isNotBlank() && endDate.isNotBlank() && startDate <= endDate

    /**
     * Check if export can proceed.
     */
    fun canExport(): Boolean =
        when (selectedExportType) {
            TseExportType.FULL, TseExportType.INCREMENTAL -> !isOperationInProgress()
            TseExportType.TIME_FILTERED -> hasValidDateRange() && !isOperationInProgress()
        }
}
