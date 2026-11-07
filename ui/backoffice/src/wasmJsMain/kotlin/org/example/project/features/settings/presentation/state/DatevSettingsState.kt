/**
 * DATEV Settings UI State.
 *
 * Represents the complete UI state for the DATEV settings tab.
 * Manages state for DSFinV-K export, Kassenbuch export, and database backup sections.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.scheduler.SchedulerDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.BackupCopyPathRules

/**
 * Main UI state for DATEV settings tab.
 *
 * @property backupState State for database backup/restore section
 * @property error Current error if any
 * @property notice A completed backup, restore or Kassenmeldung export, as localized keys with its own
 *   title (F63; replaced the English `successMessage`)
 * @property savedMessage A completed SAVE, as a localized key; shown under the "saved" title, never
 *   under "export completed" (F54 #6)
 */
data class KassenMeldungState(
    val isLoading: Boolean = false,
    val data: ShopConfigurationDto.KassenMeldungResponseDto? = null,
)

data class DatevSettingsState(
    val kassenMeldungState: KassenMeldungState = KassenMeldungState(),
    val datevXmlConfigState: DatevXmlConfigState = DatevXmlConfigState(),
    val backupState: BackupRestoreState = BackupRestoreState(),
    val accountMappingState: DatevAccountMappingState = DatevAccountMappingState(),
    val error: UiError? = null,
    val notice: SettingsNotice? = null,
    val savedMessage: StringKey? = null,
)

/**
 * State for DATEV XML Schnittstelle configuration section.
 *
 * @property isExpanded Whether the section is expanded
 * @property kassenName Cash register name
 * @property exportPath Server-side export storage path
 * @property isLoading Whether config is being loaded
 * @property isSaving Whether config is being saved
 * @property showDirectoryBrowser Whether directory browser is open
 * @property currentBrowsePath Current path in directory browser
 * @property parentBrowsePath Parent path for navigation
 * @property directoryEntries Directory entries for browser
 * @property currentPathWritable Whether current browsed path is writable
 */
data class DatevXmlConfigState(
    val isExpanded: Boolean = false,
    val kassenName: String = "Kasse",
    val exportPath: String = "",
    val consultantNumber: String = "",
    val clientNumber: String = "",
    val sammeldebitor: String = "",
    val apExportMode: String = "BEST_EFFORT",
    // Buchungsstapel-only fields
    val wirtschaftsjahrBeginn: String = "",
    val sachkontenlaenge: Int = 4,
    val festschreibung: Boolean = false,
    // Scheduler-rewrite Phase 3 Task 15: master toggle, per-type cadences, runHour, job status.
    val enabled: Boolean = false,
    val kassenbuchCadence: DatevAutoCadence = DatevAutoCadence.OFF,
    val apLedgerCadence: DatevAutoCadence = DatevAutoCadence.OFF,
    val buchungsstapelCadence: DatevAutoCadence = DatevAutoCadence.OFF,
    val runHour: Int = 4,
    val datevJobStatus: SchedulerDto.ScheduledJobResponseDto? = null,
    val runningDatevJob: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val showDirectoryBrowser: Boolean = false,
    val currentBrowsePath: String = "",
    val parentBrowsePath: String? = null,
    val directoryEntries: List<DirectoryEntry> = emptyList(),
    val currentPathWritable: Boolean = false,
    /** What the server last returned for the editable fields; null until the first load. See [isDirty]. */
    val savedConfig: ShopConfigurationDto.DatevXmlConfigResponseDto? = null,
) {
    /** The editable fields differ from what was last loaded or saved. Edit-then-undo is clean; unloaded is clean. */
    val isDirty: Boolean
        get() {
            val saved = savedConfig ?: return false
            return kassenName != saved.kassenName ||
                exportPath != saved.exportPath ||
                consultantNumber != saved.consultantNumber ||
                clientNumber != saved.clientNumber ||
                sammeldebitor != saved.sammeldebitor ||
                apExportMode != saved.apExportMode ||
                wirtschaftsjahrBeginn != saved.wirtschaftsjahrBeginn ||
                sachkontenlaenge != saved.sachkontenlaenge ||
                festschreibung != saved.festschreibung ||
                enabled != saved.enabled ||
                kassenbuchCadence != saved.kassenbuchCadence ||
                apLedgerCadence != saved.apLedgerCadence ||
                buchungsstapelCadence != saved.buchungsstapelCadence ||
                runHour != saved.runHour
        }

    /** Takes the server's [config] as both the draft and the saved snapshot (load and save success). */
    fun withConfig(config: ShopConfigurationDto.DatevXmlConfigResponseDto): DatevXmlConfigState =
        copy(
            kassenName = config.kassenName,
            exportPath = config.exportPath,
            consultantNumber = config.consultantNumber,
            clientNumber = config.clientNumber,
            sammeldebitor = config.sammeldebitor,
            apExportMode = config.apExportMode,
            wirtschaftsjahrBeginn = config.wirtschaftsjahrBeginn,
            sachkontenlaenge = config.sachkontenlaenge,
            festschreibung = config.festschreibung,
            enabled = config.enabled,
            kassenbuchCadence = config.kassenbuchCadence,
            apLedgerCadence = config.apLedgerCadence,
            buchungsstapelCadence = config.buchungsstapelCadence,
            runHour = config.runHour,
            savedConfig = config,
        )

    /** Drops the draft: restores the saved snapshot, or leaves the state alone when nothing was loaded. */
    fun withEditsDiscarded(): DatevXmlConfigState = savedConfig?.let(::withConfig) ?: this
}

/**
 * State for database Backup/Restore section.
 *
 * @property isExpanded Whether the section is expanded
 * @property defaultBackupPath Default directory path for backups
 * @property lastBackupAt Timestamp of the last successful backup
 * @property isLoadingConfig Whether configuration is being loaded
 * @property isCreatingBackup Whether backup creation is in progress
 * @property isRestoring Whether restore operation is in progress
 * @property showRestoreConfirmation Whether to show restore confirmation dialog
 * @property selectedRestoreFilePath Selected backup file path on server for restoration
 * @property showDirectoryBrowser Whether to show directory browser dialog
 * @property currentBrowsePath Current path in directory browser
 * @property directoryEntries Directory entries for browser
 * @property parentBrowsePath Parent path for "go up" navigation
 * @property currentPathWritable Whether current browsed path is writable
 * @property availableBackups List of backup files in configured directory
 * @property isLoadingBackups Whether backup list is loading
 */
data class BackupRestoreState(
    val isExpanded: Boolean = false,
    val defaultBackupPath: String = "",
    val lastBackupAt: String? = null,
    val isLoadingConfig: Boolean = false,
    val isCreatingBackup: Boolean = false,
    val isRestoring: Boolean = false,
    val showRestoreConfirmation: Boolean = false,
    val selectedRestoreFilePath: String? = null,
    val showDirectoryBrowser: Boolean = false,
    val currentBrowsePath: String = "",
    val directoryEntries: List<DirectoryEntry> = emptyList(),
    val parentBrowsePath: String? = null,
    val currentPathWritable: Boolean = false,
    val availableBackups: List<BackupFile> = emptyList(),
    val isLoadingBackups: Boolean = false,
    // Scheduler-rewrite Phase 3 Task 14: auto-backup controls + job status.
    val autoEnabled: Boolean = false,
    val cadence: BackupRestoreDto.BackupCadence = BackupRestoreDto.BackupCadence.DAILY,
    val retentionCount: Int = 7,
    val runHour: Int = 3,
    val maxUploadBytes: Long = 2L * 1024 * 1024 * 1024,
    val backupJobStatus: SchedulerDto.ScheduledJobResponseDto? = null,
    val runningBackupJob: Boolean = false,
    /** Second backup target (blank = none) and how many archives it keeps. */
    val copyToPath: String = "",
    val copyRetentionCount: Int = 7,
    /** The retention text field as typed, so it can be empty while the user retypes it; [copyRetentionCount] follows a valid number. */
    val copyRetentionInput: String = "7",
    val lastCopyAt: String? = null,
    val lastCopyStatus: BackupCopyStatus? = null,
    val lastCopyError: String? = null,
    /** What the server last returned for the editable config fields; null until the first load. */
    val savedConfig: BackupRestoreDto.BackupConfigDto? = null,
) {
    /** The config fields differ from what was last loaded or saved. Edit-then-undo is clean; unloaded is clean. */
    val isConfigDirty: Boolean
        get() {
            val saved = savedConfig ?: return false
            return defaultBackupPath != (saved.defaultBackupPath ?: "") ||
                autoEnabled != saved.autoEnabled ||
                cadence != saved.cadence ||
                retentionCount != saved.retentionCount ||
                runHour != saved.runHour ||
                maxUploadBytes != saved.maxUploadBytes ||
                copyToPath != (saved.copyTo?.path ?: "") ||
                copyRetentionCount != saved.copyRetentionCount
        }

    /** A copy target is set but the retention field was emptied: nothing valid to send until a number is typed. */
    val copyRetentionInvalid: Boolean
        get() = copyToPath.isNotBlank() && copyRetentionInput.isBlank()

    /**
     * Applies what was typed in the retention field. The text is kept as typed (empty included); the stored
     * count follows only a number, coerced to 1..365, and an out-of-range number is replaced by the coerced one.
     */
    fun withCopyRetentionInput(text: String): BackupRestoreState {
        val digits = text.filter { it.isDigit() }.take(3)
        val typed = digits.toIntOrNull() ?: return copy(copyRetentionInput = digits)
        val count = typed.coerceIn(1, 365)
        return copy(copyRetentionInput = if (count == typed) digits else count.toString(), copyRetentionCount = count)
    }

    /** The copy path is filled in but the server would refuse it (relative, `..`, or inside the backup folder). */
    val copyPathInvalid: Boolean
        get() {
            val path = copyToPath.trim()
            if (path.isEmpty()) return false
            return !BackupCopyPathRules.isAbsolute(path) ||
                BackupCopyPathRules.hasParentSegment(path) ||
                (defaultBackupPath.isNotBlank() && BackupCopyPathRules.isSameOrInside(path, defaultBackupPath))
        }

    /** The last copy did not reach the device although the local backup exists. */
    val copyWarning: Boolean
        get() = lastCopyStatus == BackupCopyStatus.FAILED || lastCopyStatus == BackupCopyStatus.SKIPPED_NOT_MOUNTED

    /**
     * The `copyTo` to send on save: the saved target unchanged when the field was not edited, the new
     * path when it was, null when the field is empty. The saved kind is kept so a later target kind survives.
     */
    fun copyTargetForSave(): BackupRestoreDto.BackupCopyTargetDto? {
        val path = copyToPath.trim().takeIf { it.isNotEmpty() } ?: return null
        val kind = savedConfig?.copyTo?.kind ?: BackupCopyTargetKind.MOUNTED_PATH
        return BackupRestoreDto.BackupCopyTargetDto(kind, path)
    }

    /** Takes the server's [config] as both the draft and the saved snapshot (load and save success). */
    fun withConfig(config: BackupRestoreDto.BackupConfigDto): BackupRestoreState =
        copy(
            defaultBackupPath = config.defaultBackupPath ?: "",
            lastBackupAt = config.lastBackupAt,
            autoEnabled = config.autoEnabled,
            cadence = config.cadence,
            retentionCount = config.retentionCount,
            runHour = config.runHour,
            maxUploadBytes = config.maxUploadBytes,
            copyToPath = config.copyTo?.path ?: "",
            copyRetentionCount = config.copyRetentionCount,
            copyRetentionInput = config.copyRetentionCount.toString(),
            lastCopyAt = config.lastCopyAt,
            lastCopyStatus = config.lastCopyStatus,
            lastCopyError = config.lastCopyError,
            savedConfig = config,
        )

    /** Takes only what a job run changes from [config], so a draft being edited is not overwritten. */
    fun withRunRecord(config: BackupRestoreDto.BackupConfigDto): BackupRestoreState =
        copy(
            lastBackupAt = config.lastBackupAt,
            lastCopyAt = config.lastCopyAt,
            lastCopyStatus = config.lastCopyStatus,
            lastCopyError = config.lastCopyError,
            savedConfig =
                savedConfig?.copy(
                    lastBackupAt = config.lastBackupAt,
                    lastCopyAt = config.lastCopyAt,
                    lastCopyStatus = config.lastCopyStatus,
                    lastCopyError = config.lastCopyError,
                ),
        )

    /** Drops the config draft: restores the saved snapshot, or leaves the state alone when nothing was loaded. */
    fun withConfigDiscarded(): BackupRestoreState = savedConfig?.let(::withConfig) ?: this

    /**
     * Check if any operation is in progress.
     */
    fun isOperationInProgress(): Boolean = isLoadingConfig || isCreatingBackup || isRestoring || isLoadingBackups

    /**
     * Check if backup can be created.
     */
    fun canCreateBackup(): Boolean = !isOperationInProgress() && defaultBackupPath.isNotBlank()

    /**
     * Check if restore can proceed.
     */
    fun canRestore(): Boolean = selectedRestoreFilePath != null && !isOperationInProgress()
}

/**
 * Directory entry for server-side directory browser.
 */
data class DirectoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isReadable: Boolean,
    val isWritable: Boolean,
)

/**
 * Backup file info for restore selection.
 */
data class BackupFile(
    val filename: String,
    val filePath: String,
    val sizeBytes: Long,
    val createdAt: String,
)

/**
 * State for the DATEV account-mapping settings section (spec §3.3).
 */
data class DatevAccountMappingCellState(
    val konto: String = "",
    val bu: String = "",
    /** „Konto automatisch zuordnen". Off = exported with an empty contra account; Konto/BU are kept but disabled. */
    val autoAssign: Boolean = true,
)

data class DatevAccountMappingPaymentRow(
    val paymentTypeId: Long,
    val name: String,
    val isVoucher: Boolean,
    val cell: DatevAccountMappingCellState = DatevAccountMappingCellState(),
)

data class DatevAccountMappingState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** True only after a successful full load (payment types + mapping). Save is gated on this. */
    val loaded: Boolean = false,
    val configured: Boolean = false,
    val kontenrahmen: Kontenrahmen = Kontenrahmen.SKR03,
    val kasseKonto: String = "",
    val income: Map<KassenbuchLineKind, Map<String, DatevAccountMappingCellState>> = emptyMap(),
    val expense: Map<KassenbuchLineKind, Map<String, DatevAccountMappingCellState>> = emptyMap(),
    val paymentTypes: List<DatevAccountMappingPaymentRow> = emptyList(),
    /** Contra accounts that depend on neither VAT rate nor payment type; every slot must be set to save. */
    val special: Map<KassenbuchSpecialBooking, DatevAccountMappingCellState> = emptyMap(),
    val showSeedConfirm: Boolean = false,
    val validationErrorKey: StringKey? = null,
    /** The editable fields as last loaded or saved (its own `saved` is null); null until the first load. */
    val saved: DatevAccountMappingState? = null,
) {
    /** The mapping differs from what was last loaded or saved. Edit-then-undo is clean; unloaded is clean. */
    val isDirty: Boolean
        get() {
            val base = saved ?: return false
            return kontenrahmen != base.kontenrahmen ||
                kasseKonto != base.kasseKonto ||
                income != base.income ||
                expense != base.expense ||
                paymentTypes != base.paymentTypes ||
                special != base.special
        }

    /** The same state with [saved] pinned to its current editable fields (load and save success). */
    fun pinned(): DatevAccountMappingState = copy(saved = copy(saved = null))

    /** Drops the draft: restores the saved fields, or leaves the state alone when nothing was loaded. */
    fun withEditsDiscarded(): DatevAccountMappingState {
        val base = saved ?: return this
        return copy(
            kontenrahmen = base.kontenrahmen,
            kasseKonto = base.kasseKonto,
            income = base.income,
            expense = base.expense,
            paymentTypes = base.paymentTypes,
            special = base.special,
            showSeedConfirm = false,
            validationErrorKey = null,
        )
    }
}
