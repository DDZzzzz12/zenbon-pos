/**
 * Main printer service interface for orchestrating print operations.
 *
 * Coordinates between formatter service, printer configuration, factory,
 * and audit logging to execute complete print operations. Provides high-level
 * print methods for bills, orders, and Z-Bons.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 5.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.printer.domain.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.Language
import org.example.project.features.employee.domain.models.Employee

/**
 * Printer service for high-level print operations.
 *
 * Handles complete print workflow including:
 * - Printer configuration retrieval
 * - Document formatting
 * - Printer selection (USB/Network)
 * - Print execution
 * - Audit trail logging
 */
interface PrinterService {
    /**
     * Print a bill to a thermal printer.
     *
     * Returns a PrintResponseDto. Hardware-I/O failures populate `success=false` with the
     * failure message; configuration / not-found errors throw PosException.
     */
    suspend fun printBill(
        bill: BillDto.BillResponseDto,
        employee: Employee,
        printerId: Long? = null,
        bewirtungsbeleg: Boolean = false,
    ): PrinterDto.PrintResponseDto

    /**
     * Print an order to a kitchen/bar printer.
     */
    suspend fun printOrder(
        order: OrderDto.OrderResponseDto,
        printerId: Long? = null,
        language: Language? = null,
        takeAway: Boolean,
    ): PrinterDto.PrintResponseDto

    /**
     * Print a Z-Bon (end-of-day report) to a printer.
     *
     * The Z-Bon is fiscal content, so the printer does not format it: [format] produces the
     * finished ESC/POS bytes and is called once, after the printer has been found. A throwing
     * [format] is reported like a failed print (`success=false` with its message); no printer
     * configured throws PosException before [format] is called.
     */
    suspend fun printZBon(
        format: suspend () -> ByteArray,
        printerId: Long? = null,
    ): PrinterDto.PrintResponseDto

    /**
     * Print a reference print (Referenzdruck) for a table order.
     */
    suspend fun printReferencePrint(
        shopHeader: ShopHeaderData,
        tableNumber: String,
        employeeNumber: String,
        dateTime: String,
        items: List<OrderItemDto.OrderItemResponseDto>,
        printerId: Long? = null,
    ): PrinterDto.PrintResponseDto

    /**
     * Sends a test print page to the printer configured for the given location.
     *
     * Auto-detects printer type:
     * - USB/NETWORK/IPP+ESC_POS → generates ESC/POS test receipt
     * - IPP+PDF → generates PDF test page
     *
     * Logs the test print to the audit trail.
     */
    suspend fun testPrint(
        printerLocationId: Long,
        printedBy: String,
    ): PrinterDto.PrintResponseDto
}
