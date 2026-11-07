/**
 * Formatter for kitchen/bar order tickets.
 *
 * Produces an ESC/POS byte array with alphanumeric product sorting,
 * take-away grouping, product extras, and side notes.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.formatter

import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.printer.core.enums.Alignment
import org.example.project.printer.core.enums.FontSize
import org.example.project.printer.core.utils.TextFormatUtils
import org.example.project.printer.core.utils.TextFormatUtils.ColumnAlign

/**
 * Formats kitchen/bar order tickets for thermal printer output.
 */
class OrderFormatter : BaseDocumentFormatter() {
    /**
     * Format an order ticket.
     *
     * @param order Order data to format
     * @param language Language for date formatting
     * @return Result containing ESC/POS byte array or error
     */
    suspend fun format(
        order: OrderDto.OrderResponseDto,
        language: Language,
        beepMode: BeepMode = BeepMode.DISABLED,
        takeAway: Boolean = false,
    ): ByteArray {
        // Sort items and group by take-away status
        val itemsByTakeAway =
            order.orderItems
                .sortedWith(
                    compareBy(
                        { TextFormatUtils.extractAlphanumericSortKey(it.product.productNumber) },
                        { it.product.productName },
                    ),
                ).groupBy { it.takeAway }

        return formatDocument(codePage = codePageFor(language)) {
            // Initialize (sends RESET + code page selection)
            initialize()

            // Enable Kanji mode for CJK languages so printer interprets multi-byte chars
            if (language == Language.CHINESE) munbynChineseMode()

            align(Alignment.LEFT)
            fontSize(FontSize.DOUBLE)
            bold(true)
            text("Bestellung")
            bold(false)
            newLine()

            // Take-away header for the entire order
            if (takeAway) {
                align(Alignment.CENTER)
                bold(true)
                text(
                    TextFormatUtils.formatColumn("-".repeat(7), 7, ColumnAlign.LEFT) +
                        TextFormatUtils.formatColumn("MITNEHMEN", 10, ColumnAlign.CENTER) +
                        TextFormatUtils.formatColumn("-".repeat(7), 7, ColumnAlign.LEFT),
                )
                bold(false)
                newLine()
                align(Alignment.LEFT)
            }

            // Timestamp
            fontSize(FontSize.NORMAL)
            text(
                TextFormatUtils.formatColumn(
                    order.orderTime.formatReadableDateTime(language = Language.GERMAN, withSeconds = true),
                    MAX_CHARS_BIG,
                    ColumnAlign.LEFT,
                ),
            )
            newLine()

            // Table and employee info
            fontSize(FontSize.DOUBLE)
            text(
                TextFormatUtils.formatColumn("# ${order.tableNumber}", MAX_CHARS_BIG / 2, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn("K: ${order.employeeId}", MAX_CHARS_BIG / 2, ColumnAlign.RIGHT),
            )
            newLine()
            separator()

            fontSize(FontSize.DOUBLE)

            // Regular items (takeAway = false)
            itemsByTakeAway[false]?.forEach { item ->
                printOrderItem(item, language = language, withProductNumber = false)
            }

            // Take-away items with header
            itemsByTakeAway[true]?.let { takeAwayItems ->
                if (takeAwayItems.isNotEmpty()) {
                    newLine()
                    feedLines(2)
                    text(
                        TextFormatUtils.formatColumn("-".repeat(7), 7, ColumnAlign.LEFT) +
                            TextFormatUtils.formatColumn("MITNEHMEN", 10, ColumnAlign.CENTER) +
                            TextFormatUtils.formatColumn("-".repeat(7), 7, ColumnAlign.LEFT),
                    )

                    takeAwayItems.forEach { item ->
                        printOrderItem(item, language = language, withProductNumber = false)
                    }
                }
            }

            feedLines(2)
            cut()
            beep(beepMode)
        }
    }
}
