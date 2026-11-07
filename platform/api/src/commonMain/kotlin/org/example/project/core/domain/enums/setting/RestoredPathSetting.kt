package org.example.project.core.domain.enums.setting

import kotlinx.serialization.Serializable

/**
 * The configured filesystem paths a component writes to, as checked after a backup restore.
 *
 * A restored path is *foreign* when it is absolute and lies outside this installation's app-data
 * directory — typically the original shop's folder when the backup came from another machine or
 * install. Each foreign one is listed for review and file writes to it pause until an admin decides.
 */
@Serializable
enum class RestoredPathSetting(
    /**
     * Whether "keep" ([RestoredPathDecision.KEEP]) is a valid decision. False only for the
     * document path: the restore extracted the archive's files into this installation's default
     * folder, so keeping the foreign path would point every stored document at a folder that does
     * not hold the restored files.
     */
    val keepAllowed: Boolean,
) {
    /** `datev_xml_config.exportPath` — every DATEV export's server copy and the TSE export folder. */
    DATEV_EXPORT_PATH(keepAllowed = true),

    /** `backup_config.defaultBackupPath` — scheduled, pre-update and pre-restore backups. */
    BACKUP_PATH(keepAllowed = true),

    /**
     * `pdf_storage_config.path` — documents, generated PDFs and product images. Only "use default"
     * is offered (see [keepAllowed]).
     */
    DOCUMENT_STORAGE_PATH(keepAllowed = false),

    /**
     * `backup_config.copyToPath` — the second copy of each backup. A target restored from another
     * machine pauses the copy until reviewed; "use default" clears it (there is no default target).
     */
    BACKUP_COPY_PATH(keepAllowed = true),
}
