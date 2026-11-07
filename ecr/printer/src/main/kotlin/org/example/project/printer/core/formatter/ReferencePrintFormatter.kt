/**
 * Formatter for customer reference print receipts.
 *
 * Produces an ESC/POS byte array with shop header, table metadata,
 * item list, subtotal, and VAT breakdown. No bill number, no payment,
 * no TSE — this is not a fiscal document.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.formatter

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.Language
import org.example.project.printer.core.enums.Alignment
import org.example.project.printer.core.enums.FontSize
import org.example.project.printer.core.utils.TextFormatUtils
import org.example.project.printer.core.utils.TextFormatUtils.ColumnAlign
import org.example.project.printer.core.utils.printMoney
import java.math.BigDecimal

class ReferencePrintFormatter : BaseDocumentFormatter() {
    suspend fun format(
        shopHeader: ShopHeaderData,
        tableNumber: String,
        employeeNumber: String,
        dateTime: String,
        items: List<OrderItemDto.OrderItemResponseDto>,
        language: Language,
    ): ByteArray =
        formatDocument(codePage = codePageFor(language)) {
            initialize()

            if (language == Language.CHINESE) munbynChineseMode()

            // ==================== SHOP HEADER ====================
            align(Alignment.CENTER)
            fontSize(FontSize.DOUBLE)
            bold(true)
            textLine(shopHeader.shopName)

            fontSize(FontSize.NORMAL)
            shopHeader.shopAddress?.let { textLine(it) }
            textLine("${shopHeader.shopCity}, ${shopHeader.zipCode ?: ""}")
            shopHeader.shopPhone?.let { textLine(it) }
            shopHeader.shopTaxNumber?.let { text(it) }
            bold(false)
            feedLines(2)
            newLine()

            // ==================== METADATA ====================
            align(Alignment.LEFT)
            fontSize(FontSize.NORMAL)

            text(
                TextFormatUtils.formatColumn(dateTime, 24, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn("Tisch: $tableNumber", 24, ColumnAlign.RIGHT),
            )
            newLine()

            text(
                TextFormatUtils.formatColumn("", 24, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn("Bedienung: $employeeNumber", 24, ColumnAlign.RIGHT),
            )
            newLine()

            // ==================== ITEMS ====================
            fontSize(FontSize.DOUBLE)
            bold(true)
            separator()

            fontSize(FontSize.NORMAL)

            items.sortedBy { it.product.productType }.forEach { item ->
                printOrderItem(item, MAX_CHARS_SMALL, language)
            }

            fontSize(FontSize.DOUBLE)
            bold(true)
            separator()

            bold(false)
            fontSize(FontSize.NORMAL)

            // ==================== SUBTOTAL ====================
            val subtotal = items.sumOf { BigDecimal(it.finalPrice) }

            fontSize(FontSize.DOUBLE)
            text(
                TextFormatUtils.formatColumn("SUMME: ", 8, ColumnAlign.LEFT) +
                    TextFormatUtils.formatColumn(printMoney(subtotal, codePageFor(language)), 16, ColumnAlign.RIGHT),
            )
//        newLine()
//
//        // ==================== VAT BREAKDOWN ====================
//        feedLines(1)
//        fontSize(FontSize.NORMAL)
//        fourColumns("MWST-Satz", "MWST", "NETTO", "BRUTTO")
//        newLine()
//
//        val vatGroups = items.groupBy { it.vatRate }
//        vatGroups.forEach { (vatRate, groupItems) ->
//            val grossTotal = groupItems.sumOf { BigDecimal(it.finalPrice) }
//            val vatDivisor = BigDecimal.ONE + BigDecimal(vatRate.toString()).divide(BigDecimal(100), 10, RoundingMode.HALF_UP)
//            val netTotal = grossTotal.divide(vatDivisor, 2, RoundingMode.HALF_UP)
//            val vatAmount = grossTotal - netTotal
//
//            val vatClass = groupItems.first().vatClass
//            val vatPercentage = BigDecimal(vatRate).toPercentage()
//
//            fourColumns(
//                "$vatClass $vatPercentage%",
//                "${vatAmount.setScale(2, RoundingMode.HALF_UP)}",
//                "${netTotal.setScale(2, RoundingMode.HALF_UP)}",
//                "${grossTotal.setScale(2, RoundingMode.HALF_UP)}"
//            )
//            newLine()
//        }

            // ==================== FINALIZE ====================
            feedLines(2)
            cut()
        }
}
