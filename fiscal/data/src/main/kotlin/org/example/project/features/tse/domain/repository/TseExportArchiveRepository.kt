package org.example.project.features.tse.domain.repository

import org.example.project.features.tse.domain.models.TseExportArchiveRecord

/** The TSE archive register. Plain data access: every call needs an ambient transaction. */
interface TseExportArchiveRepository {
    fun findBySha256(sha256: String): TseExportArchiveRecord?

    /** Every registered archive of the device [serialHex] (upper-case hex), lowest TANR first. */
    fun findBySerial(serialHex: String): List<TseExportArchiveRecord>

    /** Stores [record] (its id is ignored) and returns it with the id it was given. */
    fun insert(record: TseExportArchiveRecord): TseExportArchiveRecord
}
