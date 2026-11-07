package org.example.project.core.domain.dto.reason

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.utils.requireValidReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActionReasonValidationTest {
    private val badReasons = listOf(null, "", "   ", "x".repeat(501))

    private fun cancel(reason: String?) = OrderItemDto.CancelOrderItemsRequestDto(itemIds = listOf(1L), reason = reason)

    private fun transferItems(reason: String?) = OrderItemDto.TransferOrderItemsRequestDto(itemIds = listOf(1L), targetTableId = 2L, reason = reason)

    private fun transferTable(reason: String?) = RTableDto.TransferTableRequestDto(targetTableId = 2L, targetEmployeeNumber = "1", reason = reason)

    @Test
    fun cancelRequiresReason() {
        badReasons.forEach { assertFailsWith<IllegalArgumentException>("reason=$it") { cancel(it).validate() } }
        cancel("x".repeat(500)).validate()
    }

    @Test
    fun transferItemsRequiresReason() {
        badReasons.forEach { assertFailsWith<IllegalArgumentException>("reason=$it") { transferItems(it).validate() } }
        transferItems("x".repeat(500)).validate()
    }

    @Test
    fun transferTableRequiresReason() {
        badReasons.forEach { assertFailsWith<IllegalArgumentException>("reason=$it") { transferTable(it).validate() } }
        transferTable("x".repeat(500)).validate()
    }

    @Test
    fun paddedOverlongReasonIsRefused() {
        assertFailsWith<IllegalArgumentException> { (" " + "x".repeat(500)).requireValidReason() }
    }

    @Test
    fun requireValidReasonTrimsAndReturns() {
        assertEquals("Küchenfehler", "  Küchenfehler ".requireValidReason())
    }
}
