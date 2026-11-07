/**
 * Text formatting utilities for thermal printer output.
 *
 * Provides column alignment, word wrapping, separator lines, and
 * alphanumeric sort key extraction used across all document formatters.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.utils

/**
 * Shared text formatting functions for ESC/POS document formatting.
 *
 * All methods are stateless and operate on plain strings,
 * independent of any ESC/POS byte sequences.
 */
object TextFormatUtils {
    /**
     * Column alignment types for text formatting.
     */
    enum class ColumnAlign {
        LEFT,
        RIGHT,
        CENTER,
    }

    /**
     * Format a string into a fixed-width column with alignment.
     *
     * Truncates text that exceeds [width] and pads with spaces
     * according to the specified [align]ment.
     *
     * @param text Text to format
     * @param width Exact character width of the column
     * @param align Horizontal alignment within the column
     * @return String of exactly [width] characters
     */
    fun formatColumn(
        text: String,
        width: Int,
        align: ColumnAlign,
    ): String {
        val truncated = if (text.length >= width) text.substring(0, width) else text
        val remaining = width - truncated.length
        return when (align) {
            ColumnAlign.LEFT -> truncated + " ".repeat(remaining)
            ColumnAlign.RIGHT -> " ".repeat(remaining) + truncated
            ColumnAlign.CENTER -> {
                val leftPad = remaining / 2
                val rightPad = remaining - leftPad
                " ".repeat(leftPad) + truncated + " ".repeat(rightPad)
            }
        }
    }

    /**
     * Build a two-column row spanning a given total width.
     *
     * Left text is left-aligned, right text is right-aligned.
     *
     * @param left Text for the left column
     * @param right Text for the right column
     * @param totalWidth Total character width of the line
     * @return Formatted string of exactly [totalWidth] characters
     */
    fun twoColumnRow(
        left: String,
        right: String,
        totalWidth: Int,
    ): String {
        val half = totalWidth / 2
        return formatColumn(left, half, ColumnAlign.LEFT) +
            formatColumn(right, totalWidth - half, ColumnAlign.RIGHT)
    }

    /**
     * Build a label/amount row where the amount keeps its full width and the label gets the rest.
     *
     * [twoColumnRow] halves the line, which cuts a label longer than half the width (e.g.
     * "  davon Zahlartwechsel (7):" lost its count). Here the amount is never truncated and the
     * label is cut only if the two genuinely cannot share one line, with at least one space between.
     *
     * @return Formatted string of exactly [totalWidth] characters (or the amount alone if it is wider)
     */
    fun labelAmountRow(
        label: String,
        amount: String,
        totalWidth: Int,
    ): String {
        val labelWidth = (totalWidth - amount.length - 1).coerceAtLeast(0)
        return formatColumn(label, labelWidth, ColumnAlign.LEFT) + " " + amount
    }

    /**
     * Build a four-column row with each column getting equal width.
     *
     * All columns are center-aligned.
     *
     * @param col1 First column text
     * @param col2 Second column text
     * @param col3 Third column text
     * @param col4 Fourth column text
     * @param totalWidth Total character width of the line
     * @return Formatted string of exactly [totalWidth] characters
     */
    fun fourColumnRow(
        col1: String,
        col2: String,
        col3: String,
        col4: String,
        totalWidth: Int,
    ): String {
        val colWidth = totalWidth / 4
        return formatColumn(col1, colWidth, ColumnAlign.CENTER) +
            formatColumn(col2, colWidth, ColumnAlign.CENTER) +
            formatColumn(col3, colWidth, ColumnAlign.CENTER) +
            formatColumn(col4, totalWidth - 3 * colWidth, ColumnAlign.CENTER)
    }

    /**
     * Generate a dash separator line.
     *
     * @param width Number of dash characters (defaults to 24 for double-width mode)
     * @return String of dashes followed by a newline
     */
    fun separatorLine(width: Int = 24): String = "-".repeat(width.coerceAtLeast(0)) + "\n"

    /**
     * Word-wrap text into lines that fit within the given character width.
     *
     * Breaks at word boundaries when possible; hard-breaks when a word
     * is longer than [maxWidth].
     *
     * @param text Text to wrap
     * @param maxWidth Maximum characters per line
     * @return List of lines, each fitting within [maxWidth]
     */
    fun wordWrap(
        text: String,
        maxWidth: Int,
    ): List<String> {
        if (text.length <= maxWidth) return listOf(text)

        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxWidth) {
                lines.add(remaining)
                break
            }
            val breakAt = remaining.lastIndexOf(' ', maxWidth)
            if (breakAt <= 0) {
                lines.add(remaining.substring(0, maxWidth))
                remaining = remaining.substring(maxWidth)
            } else {
                lines.add(remaining.substring(0, breakAt))
                remaining = remaining.substring(breakAt + 1)
            }
        }
        return lines
    }

    /**
     * Generate a blank fill-in line (underscores) fitting within [width].
     *
     * Optionally prepends a [prefix] (e.g. "1. ") before the underscores.
     *
     * @param width Total character width of the line
     * @param prefix Optional prefix text before the underscores
     * @return String of exactly [width] characters
     */
    fun blankLine(
        width: Int,
        prefix: String = "",
    ): String {
        val underscoreCount = (width - prefix.length).coerceAtLeast(0)
        return prefix + "_".repeat(underscoreCount)
    }

    /**
     * Extract alphanumeric sort key from a product number.
     *
     * Splits the product number into its alphabetic prefix and numeric suffix,
     * then zero-pads the number for consistent lexicographic sorting.
     *
     * @param productNumber Product number string (e.g., "A12", "B3", "100")
     * @return Sort key in format "PREFIX0000000NUM" for consistent ordering
     */
    fun extractAlphanumericSortKey(productNumber: String): String {
        val prefix = productNumber.replace(Regex("\\d+"), "")
        val number = productNumber.replace(Regex("\\D+"), "")
        val sortPrefix = prefix.ifEmpty { "" }
        val numValue = if (number.isEmpty()) 0 else number.toInt()
        return String.format("%s%010d", sortPrefix, numValue)
    }
}
