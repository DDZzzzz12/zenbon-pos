package org.example.project.core.data.sync

import org.example.project.core.data.domain.service.dedupeByKey
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityResolverTest {
    private fun entry(
        seq: Long,
        type: ChangeEntityType,
        id: String,
        op: LogOperation,
    ) = ChangeFeedDto.EntryDto(seq = seq, entityType = type, entityId = id, operation = op)

    @Test
    fun `keeps latest-by-seq per key`() {
        val result =
            dedupeByKey(
                listOf(
                    entry(1, ChangeEntityType.PRODUCT, "42", LogOperation.CREATE),
                    entry(5, ChangeEntityType.PRODUCT, "42", LogOperation.UPDATE),
                    entry(3, ChangeEntityType.PRODUCT, "42", LogOperation.UPDATE),
                ),
            )
        assertEquals(1, result.size)
        assertEquals(5, result.single().seq)
        assertEquals(LogOperation.UPDATE, result.single().operation)
    }

    @Test
    fun `later DELETE supersedes earlier CREATE-UPDATE for same key`() {
        val result =
            dedupeByKey(
                listOf(
                    entry(2, ChangeEntityType.PRODUCT, "7", LogOperation.CREATE),
                    entry(4, ChangeEntityType.PRODUCT, "7", LogOperation.UPDATE),
                    entry(9, ChangeEntityType.PRODUCT, "7", LogOperation.DELETE),
                ),
            )
        assertEquals(1, result.size)
        assertEquals(9, result.single().seq)
        assertEquals(LogOperation.DELETE, result.single().operation)
    }

    @Test
    fun `distinct keys are all kept and sorted by seq`() {
        val result =
            dedupeByKey(
                listOf(
                    entry(10, ChangeEntityType.PRODUCT, "1", LogOperation.UPDATE),
                    entry(2, ChangeEntityType.CATEGORY, "1", LogOperation.CREATE),
                    entry(7, ChangeEntityType.PRODUCT, "2", LogOperation.CREATE),
                    // same id "1" but a different type is a distinct key:
                    entry(3, ChangeEntityType.PAYMENT_TYPE, "1", LogOperation.UPDATE),
                ),
            )
        assertEquals(4, result.size)
        assertEquals(listOf(2L, 3L, 7L, 10L), result.map { it.seq })
    }
}
