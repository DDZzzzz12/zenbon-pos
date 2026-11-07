package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/**
 * How a TSE archive came into the archive register: written by the server's own export-and-delete,
 * or registered afterwards by an administrator from a file already in the TSE export folder (an
 * archive written before the register existed, or one restored from a backup).
 */
@Serializable
enum class TseExportArchiveOrigin { EXPORT_AND_DELETE, REGISTERED }
