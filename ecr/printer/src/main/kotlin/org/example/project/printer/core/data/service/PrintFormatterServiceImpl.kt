/**
 * Print formatter service implementation — thin delegator.
 *
 * Delegates each document type to its dedicated formatter class.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.data.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.Language
import org.example.project.printer.core.domain.service.PrintFormatterService
import org.example.project.printer.core.formatter.BillFormatter
import org.example.project.printer.core.formatter.OrderFormatter
import org.example.project.printer.core.formatter.ReferencePrintFormatter

class PrintFormatterServiceImpl(
    private val billFormatter: BillFormatter,
    private val orderFormatter: OrderFormatter,
    private val referencePrintFormatter: ReferencePrintFormatter,
) : PrintFormatterService {
    override suspend fun formatBill(
        bill: BillDto.BillResponseDto,
        shopHeader: ShopHeaderData,
        language: Language,
        bewirtungsbeleg: Boolean,
    ): ByteArray = billFormatter.format(bill, shopHeader, language, bewirtungsbeleg)

    override suspend fun formatOrder(
        order: OrderDto.OrderResponseDto,
        language: Language,
        beepMode: BeepMode,
        takeAway: Boolean,
    ): ByteArray = orderFormatter.format(order, language, beepMode, takeAway)

    override suspend fun formatReferencePrint(
        shopHeader: ShopHeaderData,
        tableNumber: String,
        employeeNumber: String,
        dateTime: String,
        items: List<OrderItemDto.OrderItemResponseDto>,
        language: Language,
    ): ByteArray = referencePrintFormatter.format(shopHeader, tableNumber, employeeNumber, dateTime, items, language)
}
