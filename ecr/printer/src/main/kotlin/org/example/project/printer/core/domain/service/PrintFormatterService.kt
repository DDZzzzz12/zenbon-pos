/**
 * Service interface for formatting documents into ESC/POS commands.
 *
 * Converts Bill and Order DTOs and reference prints into ESC/POS byte arrays suitable for
 * thermal printers. Handles multi-language encoding, text formatting, and
 * 48-character thermal printer width constraints.
 *
 * Throws on formatting failure (Stage C: Result envelope removed).
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.domain.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.Language

interface PrintFormatterService {
    suspend fun formatBill(
        bill: BillDto.BillResponseDto,
        shopHeader: ShopHeaderData,
        language: Language,
        bewirtungsbeleg: Boolean = false,
    ): ByteArray

    suspend fun formatOrder(
        order: OrderDto.OrderResponseDto,
        language: Language,
        beepMode: BeepMode = BeepMode.DISABLED,
        takeAway: Boolean = false,
    ): ByteArray

    suspend fun formatReferencePrint(
        shopHeader: ShopHeaderData,
        tableNumber: String,
        employeeNumber: String,
        dateTime: String,
        items: List<OrderItemDto.OrderItemResponseDto>,
        language: Language,
    ): ByteArray
}
