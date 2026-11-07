package org.example.project.core.data.cache

import org.example.project.core.data.database.RTableDataManager
import org.example.project.core.data.models.toRTable
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.dashboard.presentation.util.patchTableStatus

/** Applies a live TableStatusUpdate to the cached table row, preserving layout fields. */
class TableStatusCacheWriter(
    private val manager: RTableDataManager,
    private val cacheChangeBus: CacheChangeBus,
) {
    suspend fun applyStatusUpdate(
        tableId: Long,
        status: String,
        employeeNumber: String?,
        sessionId: Long?,
        isTraining: Boolean,
        openAmount: String?,
    ) {
        // Read-modify-write is safe here: runs on the single-threaded main dispatcher with a
        // synchronous DataManager (no suspension between read and write). If the manager is
        // ever moved off-thread, wrap this in one database.transaction to avoid a lost update.
        val existing = manager.selectById(tableId)?.let { it.toRTable() } ?: return
        val patched =
            listOf(existing).patchTableStatus(
                tableId = tableId,
                status = status,
                openAmount = openAmount,
                employeeNumber = employeeNumber,
                sessionId = sessionId,
                isTraining = isTraining,
            )
        manager.upsertAll(patched)
        cacheChangeBus.emit(CacheChange(ChangeEntityType.RTABLE, existing.locationTypeId))
    }
}
