/**
 * ESC/POS Kanji character commands.
 *
 * FS-prefixed commands (0x1C) for CJK character handling on
 * Simplified Chinese, Traditional Chinese, and Japanese printer models.
 *
 * Based on EPSON ESC/POS specification — Kanji Commands section.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter.commands

import org.example.project.printer.core.enums.KanjiCodeSystem

/**
 * Kanji character commands for CJK thermal printer models.
 *
 * These commands are supported only by printers with CJK firmware
 * (Simplified Chinese, Traditional Chinese, Japanese).
 */
object KanjiCommands {
    // ==================== Mode Selection ====================

    /**
     * Select Kanji character mode — standard ESC/POS (FS &).
     *
     * Enables Kanji character processing. When enabled, the printer
     * interprets two-byte character codes as Kanji characters.
     * Works on Epson CJK models and most standard ESC/POS printers.
     */
    val KANJI_MODE_ON = byteArrayOf(0x1C, 0x26)

    /**
     * Cancel Kanji character mode — standard ESC/POS (FS .).
     *
     * Disables Kanji character processing and returns to
     * single-byte character mode.
     */
    val KANJI_MODE_OFF = byteArrayOf(0x1C, 0x2E)

    // ==================== Vendor-Specific CJK Modes ====================

    /**
     * Enable Simplified Chinese mode — Munbyn vendor-specific command.
     *
     * Munbyn printers (ITPP047, P075, etc.) use this proprietary sequence
     * instead of the standard FS & to activate their built-in GB18030
     * font library (24x24 dot CJK characters).
     *
     * Command: 1F 1B 1F EE 4F 4E 54 00
     */
    val MUNBYN_CHINESE_SIMPLIFIED: ByteArray =
        byteArrayOf(
            0x1F,
            0x1B,
            0x1F,
            0xEE.toByte(), // 0xEE > 0x7F → explicit .toByte() required
            0x4F,
            0x4E,
            0x54,
            0x00, // all ≤ 0x7F → auto-narrowed to Byte
        )

    /**
     * Enable Traditional Chinese mode — Munbyn vendor-specific command.
     *
     * Activates Traditional Chinese (Taiwan/HK) font library on
     * Munbyn printers that support it.
     *
     * Command: 1F 1B 1F EE 4F 4E 54 01
     */
    val MUNBYN_CHINESE_TRADITIONAL: ByteArray =
        byteArrayOf(
            0x1F,
            0x1B,
            0x1F,
            0xEE.toByte(),
            0x4F,
            0x4E,
            0x54,
            0x01,
        )

    // ==================== Print Mode ====================

    /**
     * Set print mode(s) for Kanji characters (FS !).
     *
     * Controls double-width, double-height, and underline modes
     * simultaneously via a bitmask:
     * - Bit 2: Double-width (0 = OFF, 1 = ON)
     * - Bit 3: Double-height (0 = OFF, 1 = ON)
     * - Bit 7: Underline (0 = OFF, 1 = ON)
     *
     * @param doubleWidth Enable double-width mode
     * @param doubleHeight Enable double-height mode
     * @param underline Enable underline mode
     * @return ESC/POS byte sequence
     */
    fun setPrintMode(
        doubleWidth: Boolean = false,
        doubleHeight: Boolean = false,
        underline: Boolean = false,
    ): ByteArray {
        var n = 0
        if (doubleWidth) n = n or 0x04
        if (doubleHeight) n = n or 0x08
        if (underline) n = n or 0x80
        return byteArrayOf(0x1C, 0x21, (n and 0xFF).toByte())
    }

    // ==================== Underline ====================

    /**
     * Turn underline mode on/off for Kanji characters (FS –).
     *
     * @param mode 0 = OFF, 1 = one-dot underline, 2 = two-dot underline
     * @return ESC/POS byte sequence
     */
    fun setUnderline(mode: Int): ByteArray = byteArrayOf(0x1C, 0x2D, (mode.coerceIn(0, 2) and 0xFF).toByte())

    // ==================== Code System ====================

    /**
     * Select Kanji character code system (FS C).
     *
     * Selects the encoding system for Kanji character interpretation.
     * The available systems depend on the printer's CJK firmware.
     *
     * @param system Kanji code system to select
     * @return ESC/POS byte sequence
     */
    fun selectCodeSystem(system: KanjiCodeSystem): ByteArray = byteArrayOf(0x1C, 0x43, system.code)

    // ==================== Spacing ====================

    /**
     * Set Kanji character spacing (FS S).
     *
     * Sets the left and right spacing for Kanji characters in dot units.
     *
     * @param leftSpacing Left-side spacing in dots (0–255)
     * @param rightSpacing Right-side spacing in dots (0–255)
     * @return ESC/POS byte sequence
     */
    fun setSpacing(
        leftSpacing: Int,
        rightSpacing: Int,
    ): ByteArray =
        byteArrayOf(
            0x1C,
            0x53,
            (leftSpacing.coerceIn(0, 255) and 0xFF).toByte(),
            (rightSpacing.coerceIn(0, 255) and 0xFF).toByte(),
        )

    // ==================== Quadruple Size ====================

    /**
     * Turn quadruple-size mode on/off for Kanji characters (FS W).
     *
     * When enabled, Kanji characters are printed at 4x size
     * (double height + double width).
     *
     * @param enabled True to enable quadruple-size, false to disable
     * @return ESC/POS byte sequence
     */
    fun setQuadrupleSize(enabled: Boolean): ByteArray = byteArrayOf(0x1C, 0x57, if (enabled) 0x01 else 0x00)

    // ==================== Character Effects ====================

    /**
     * Define character effects of Kanji characters (FS ( A).
     *
     * Selects the Kanji character font. Sub-function for font selection:
     * pL=2, pH=0, fn=48 (0x30), data=font number.
     *
     * @param font Font number (0 = Font A, 1 = Font B, etc.)
     * @return ESC/POS byte sequence
     */
    fun selectFont(font: Int): ByteArray =
        byteArrayOf(
            0x1C,
            0x28,
            0x41,
            0x02,
            0x00, // pL=2, pH=0
            0x30, // fn=48 (select font)
            (font and 0xFF).toByte(),
        )

    // ==================== User-Defined Characters ====================

    /**
     * Define user-defined Kanji characters (FS 2).
     *
     * Defines custom Kanji character patterns for a range of character codes.
     * The character code range and dot data depend on the code system
     * and printer model.
     *
     * @param code1High High byte of starting character code
     * @param code1Low Low byte of starting character code
     * @param code2High High byte of ending character code
     * @param code2Low Low byte of ending character code
     * @param dotData Dot pattern data for all characters in range
     * @return ESC/POS byte sequence
     */
    fun defineUserCharacters(
        code1High: Byte,
        code1Low: Byte,
        code2High: Byte,
        code2Low: Byte,
        dotData: ByteArray,
    ): ByteArray = byteArrayOf(0x1C, 0x32, code1High, code1Low, code2High, code2Low) + dotData

    /**
     * Cancel user-defined Kanji characters (FS ?).
     *
     * Cancels user-defined Kanji characters for the specified character code,
     * reverting to the built-in font.
     *
     * @param codeHigh High byte of the character code to cancel
     * @param codeLow Low byte of the character code to cancel
     * @return ESC/POS byte sequence
     */
    fun cancelUserCharacters(
        codeHigh: Byte,
        codeLow: Byte,
    ): ByteArray = byteArrayOf(0x1C, 0x3F, codeHigh, codeLow)
}
