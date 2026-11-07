package org.example.project.features.tse.verification

/**
 * A TANR range of one TSE held by a registered archive (`tse_export_archive`) — entries an
 * export-and-delete erased from the device, so a later export of that device cannot hold them.
 */
data class ArchivedTanrRange(
    val serialHex: String,
    val tanrs: LongRange,
    val fileName: String,
)
