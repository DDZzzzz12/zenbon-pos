package org.example.project.core.domain.dto.sync

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType

object ChangeFeedDto {
    @Serializable
    data class EntryDto(
        val seq: Long,
        val entityType: ChangeEntityType,
        val entityId: String,
        val operation: LogOperation,
    )

    @Serializable
    data class ResponseDto(
        val entries: List<EntryDto>,
        /** Newest seq the server holds right now. */
        val highWaterSeq: Long,
        /** Oldest seq still retained; clients with lastSeenSeq < this must full-sync. */
        val minAvailableSeq: Long,
    )
}
