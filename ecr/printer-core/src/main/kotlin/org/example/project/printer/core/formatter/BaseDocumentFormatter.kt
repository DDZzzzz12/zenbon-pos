/**
 * Abstract base for ESC/POS document formatters.
 *
 * Provides shared extension functions on [EscPosEmitter] for higher-level
 * formatting operations used across bill, order, Z-Bon, and tip receipt
 * formatters: section headers, separators, two-column rows, and
 * the common order-item printing logic.
 *
 * Subclasses implement a single `format(...)` method that produces
 * a `Result<ByteArray, DataError>`.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.formatter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.printLabel
import org.example.project.core.utils.printedRate
import org.example.project.printer.core.emitter.EscPosEmitter
import org.example.project.printer.core.enums.CodePage
import org.example.project.printer.core.enums.FontSize
import org.example.project.printer.core.utils.TextFormatUtils
import org.example.project.printer.core.utils.TextFormatUtils.ColumnAlign
import org.example.project.printer.core.utils.printMoney
import kotlin.math.roundToInt

/**
 * Base class for document formatters providing shared formatting utilities.
 *
 * All formatting methods are extension functions on [EscPosEmitter]
 * so subclasses can chain them naturally within the emitter's fluent API.
 */
abstract class BaseDocumentFormatter {
    companion object {
        /** Big font width (double height/width mode). */
        const val MAX_CHARS_BIG = 24

        /** Small font width (normal text mode). */
        const val MAX_CHARS_SMALL = 48

        /** Width of an item line's price column in small font (`"20,80 EUR"` fits with room). */
        const val PRICE_COLUMN_WIDTH = 11

        /**
         * Resolve the ESC/POS [CodePage] for a given [Language].
         *
         * Chinese requires GB18030 for multi-byte CJK encoding.
         * Western languages use CP850 (the printer default).
         *
         * @param language Application language
         * @return Matching code page for the printer and string encoding
         */
        fun codePageFor(language: Language): CodePage =
            when (language) {
                Language.CHINESE -> CodePage.GB18030
                Language.ENGLISH -> CodePage.CP850
                Language.GERMAN -> CodePage.CP850
            }
    }

    // ==================== Shared Emitter Extensions ====================

    /**
     * Print a bold section header followed by a separator line.
     *
     * Emits: BOLD_ON → text → NEW_LINE → BOLD_OFF → separator
     *
     * @param title Section title text
     * @param separatorWidth Width of the dash separator (default: MAX_CHARS_BIG)
     */
    protected fun EscPosEmitter.sectionHeader(
        title: String,
        separatorWidth: Int = MAX_CHARS_BIG,
    ): EscPosEmitter =
        bold(true)
            .textLine(title)
            .bold(false)
            .textLine(TextFormatUtils.separatorLine(separatorWidth).trimEnd())

    /**
     * Print a dash separator line.
     *
     * @param width Number of dashes (default: MAX_CHARS_BIG)
     */
    protected fun EscPosEmitter.separator(width: Int = MAX_CHARS_BIG): EscPosEmitter = text(TextFormatUtils.separatorLine(width))

    /**
     * Print a two-column row (left-aligned + right-aligned).
     *
     * @param left Left column text
     * @param right Right column text
     * @param totalWidth Total character width (default: MAX_CHARS_SMALL)
     */
    protected fun EscPosEmitter.twoColumns(
        left: String,
        right: String,
        totalWidth: Int = MAX_CHARS_SMALL,
    ): EscPosEmitter = text(TextFormatUtils.twoColumnRow(left, right, totalWidth))

    /**
     * Print a four-column row (all center-aligned).
     *
     * @param col1 First column text
     * @param col2 Second column text
     * @param col3 Third column text
     * @param col4 Fourth column text
     * @param totalWidth Total character width (default: MAX_CHARS_SMALL)
     */
    protected fun EscPosEmitter.fourColumns(
        col1: String,
        col2: String,
        col3: String,
        col4: String,
        totalWidth: Int = MAX_CHARS_SMALL,
    ): EscPosEmitter = text(TextFormatUtils.fourColumnRow(col1, col2, col3, col4, totalWidth))

    /**
     * Print a single order item with word-wrapped name, extras, and side notes.
     *
     * Used by both [BillFormatter] and [OrderFormatter].
     *
     * @param item Order item to print
     * @param charWidth Character width (MAX_CHARS_BIG for kitchen, MAX_CHARS_SMALL for bills)
     * @param price The printed line amount (small font only); a Storno Beleg passes it negated.
     */
    protected fun EscPosEmitter.printOrderItem(
        item: OrderItemDto.OrderItemResponseDto,
        charWidth: Int = MAX_CHARS_BIG,
        language: Language = Language.GERMAN,
        withProductNumber: Boolean = true,
        price: String = item.finalPrice,
    ): EscPosEmitter {
        val isBigChar = charWidth == MAX_CHARS_BIG
        if (isBigChar) {
            fontSize(FontSize.DOUBLE).bold(true)
        } else {
            fontSize(FontSize.NORMAL).bold(false)
        }

        val product = item.product
        val productName =
            when (language) {
                Language.CHINESE -> product.ch ?: product.productName
                Language.ENGLISH -> product.en ?: product.productName
                Language.GERMAN -> product.productName
            }

        // Build prefix: "Qx NUMBER " — remainder for product name
        val prefix =
            if (isBigChar) {
                "${item.quantity}"
            } else {
                "${item.vatClass} ${item.quantity}"
            }

        val type = ProductType.valueOf(item.product.productType)
        val wineProduct = item.product.productNumber.startsWith("W")

        val text =
            if (withProductNumber || type == ProductType.FOOD || wineProduct) {
                "x${item.product.productNumber} $productName"
            } else {
                " $productName"
            }

        val indent = prefix.length
        val nameWidth =
            if (isBigChar) {
                charWidth - indent
            } else {
                charWidth - indent - PRICE_COLUMN_WIDTH
            }.coerceAtLeast(1)

        val nameLines = TextFormatUtils.wordWrap(text, nameWidth)

        // First line: prefix + first chunk of name (+ price for small font)
        if (isBigChar) {
            text(
                TextFormatUtils.formatColumn(
                    prefix + (nameLines.firstOrNull() ?: ""),
                    charWidth,
                    ColumnAlign.LEFT,
                ),
            )
        } else {
            // A grouped German amount ("1.234,50 EUR") is 12 chars: the price column grows into the
            // name rather than cutting the amount.
            val priceText = printMoney(price, codePageFor(language))
            val priceWidth = maxOf(PRICE_COLUMN_WIDTH, priceText.length)
            text(
                TextFormatUtils.formatColumn(
                    prefix + (nameLines.firstOrNull() ?: ""),
                    charWidth - priceWidth,
                    ColumnAlign.LEFT,
                ) +
                    TextFormatUtils.formatColumn(
                        priceText,
                        priceWidth,
                        ColumnAlign.RIGHT,
                    ),
            )
        }

        // Continuation lines: indented to align with name start
        nameLines.drop(1).forEach { line ->
            newLine()
            text(
                TextFormatUtils.formatColumn(
                    " ".repeat(indent) + line,
                    if (isBigChar) charWidth else charWidth - PRICE_COLUMN_WIDTH,
                    ColumnAlign.LEFT,
                ),
            )
        }

        bold(false)

        // Extras
        val extras = item.extras
        if (!extras.isNullOrEmpty()) {
            extras.forEach { opt ->
                // One definition with the PDF receipt (emu21 NOTE-y): quantity, name, delta.
                val label = "- ${opt.printLabel()}"
                val extrasLines = TextFormatUtils.wordWrap(label, nameWidth)
                newLine()
                extrasLines.forEach { line ->
                    text(TextFormatUtils.formatColumn(" ".repeat(indent) + line, charWidth, ColumnAlign.LEFT))
                }
            }
        }

        // Side notes
        if (!item.sideNotes.isNullOrEmpty()) {
            val noteLines = TextFormatUtils.wordWrap("\"${item.sideNotes}\"", nameWidth)
            noteLines.forEach { line ->
                text(
                    TextFormatUtils.formatColumn(
                        " ".repeat(indent) + line,
                        charWidth,
                        ColumnAlign.LEFT,
                    ),
                )
            }
        }

        // Discount
        if (item.discount > 0.0 && item.discount < 1.0) {
            val discountPercent = ((1 - item.discount) * 100).roundToInt()
            newLine()
            text(
                TextFormatUtils.formatColumn(
                    " ".repeat(indent) + "-${printedRate(discountPercent.toString())} Rabatt",
                    charWidth,
                    ColumnAlign.LEFT,
                ),
            )
        }

        // Trailing newline
        return newLine()
    }

    // ==================== Document Building ====================

    /**
     * Execute a formatting block within a coroutine context and return the
     * resulting ESC/POS byte array. Throws on failure (Stage C: Result envelope
     * removed).
     *
     * @param codePage Code page for printer and string encoding (default: CP850)
     * @param block Suspend lambda that builds the document using the emitter
     */
    protected suspend fun formatDocument(
        codePage: CodePage = CodePage.CP850,
        block: suspend EscPosEmitter.() -> Unit,
    ): ByteArray {
        val emitter = EscPosEmitter(codePage)
        withContext(Dispatchers.IO) {
            emitter.block()
        }
        return emitter.build()
    }
}
