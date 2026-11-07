package org.example.project.features.printer.domain.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.setup.ShopHeaderData

/**
 * Port the printer uses to render receipt-style PDFs for IPP printing.
 *
 * Implemented by `PdfGenerationService` (accounting area); the printer depends only on this port.
 */
interface ReceiptPdfRenderer {
    /**
     * Generates a compact receipt-style PDF from a bill for IPP printing.
     *
     * @param bill Bill response DTO with all bill data
     * @param shopHeader Shop header data for receipt header
     * @return PDF bytes
     */
    suspend fun generateBillReceiptPdf(
        bill: BillDto.BillResponseDto,
        shopHeader: ShopHeaderData,
    ): ByteArray

    /**
     * Generates a compact receipt-style PDF from an order for IPP printing.
     *
     * @param order Order response DTO with all order data
     * @return PDF bytes
     */
    suspend fun generateOrderReceiptPdf(
        order: OrderDto.OrderResponseDto,
        takeAway: Boolean = false,
    ): ByteArray

    /**
     * Generates a compact reference print PDF from open order items for IPP printing.
     *
     * @param shopHeader Shop header data for receipt header section
     * @param tableNumber Table number string for display
     * @param employeeNumber Employee number string for display
     * @param dateTime Formatted date/time string for display
     * @param items List of order item response DTOs to render
     * @return PDF bytes
     */
    suspend fun generateReferencePrintPdf(
        shopHeader: ShopHeaderData,
        tableNumber: String,
        employeeNumber: String,
        dateTime: String,
        items: List<OrderItemDto.OrderItemResponseDto>,
    ): ByteArray

    /**
     * Generates a simple test page PDF for IPP printer testing.
     *
     * Creates a single-page PDF with location name, printer URI, and timestamp.
     * Used by the test print feature for IPP printers configured with PDF format.
     *
     * @param locationName Printer location name
     * @param printerUri IPP URI of the printer
     * @param timestamp Formatted timestamp string
     * @return PDF bytes
     */
    suspend fun generateTestPage(
        locationName: String,
        printerUri: String,
        timestamp: String,
    ): ByteArray
}
