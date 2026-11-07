/**
 * Fluent ESC/POS command builder.
 *
 * Provides a chainable API for constructing thermal printer output.
 * Every method returns `this` for fluent chaining. Internally delegates
 * to categorized command objects and writes through [ByteArrayBuilder].
 *
 * Replaces the verbose `output.write(EscPosCommands.XXX)` pattern with
 * a readable DSL: `emitter.align(CENTER).fontSize(DOUBLE).bold(true).textLine("Z-BON")`.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter

import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.printer.core.emitter.commands.AlignmentCommands
import org.example.project.printer.core.emitter.commands.BeepCommands
import org.example.project.printer.core.emitter.commands.CharacterCommands
import org.example.project.printer.core.emitter.commands.InitializationCommands
import org.example.project.printer.core.emitter.commands.KanjiCommands
import org.example.project.printer.core.emitter.commands.LineFeedCommands
import org.example.project.printer.core.emitter.commands.PaperCommands
import org.example.project.printer.core.enums.Alignment
import org.example.project.printer.core.enums.CashDrawerPin
import org.example.project.printer.core.enums.CodePage
import org.example.project.printer.core.enums.CutType
import org.example.project.printer.core.enums.FontSize
import org.example.project.printer.core.enums.KanjiCodeSystem

/**
 * Fluent builder for ESC/POS printer commands.
 *
 * Usage:
 * ```kotlin
 * val data = EscPosEmitter()
 *     .initialize()
 *     .align(Alignment.CENTER)
 *     .fontSize(FontSize.DOUBLE)
 *     .bold(true)
 *     .textLine("Z-BON")
 *     .bold(false)
 *     .fontSize(FontSize.NORMAL)
 *     .newLine()
 *     .cut()
 *     .build()
 * ```
 *
 * @property codePage Code page binding the printer-side page number to the Java charset.
 *                   Defaults to [CodePage.CP850] (Western European / German).
 */
class EscPosEmitter(
    private val codePage: CodePage = CodePage.CP850,
) {
    private val builder = ByteArrayBuilder(codePage.charset)

    // ==================== Initialization ====================

    /**
     * Send printer reset command and select the configured code page.
     *
     * Emits ESC @ (reset). For single-byte code pages, also sends
     * ESC t n so the printer's character interpretation matches the
     * Java charset used for string encoding.
     *
     * CJK code pages (GB18030, Shift-JIS) skip the ESC t command because
     * multi-byte encoding is activated via Kanji mode commands (FS &) or
     * vendor-specific commands (e.g. [munbynChineseMode]) instead.
     */
    fun initialize(): EscPosEmitter {
        builder.append(InitializationCommands.RESET)
        // ESC t only applies to single-byte code pages; CJK uses FS & or vendor commands
        if (!codePage.isCjk) {
            builder.append(InitializationCommands.selectCodePage(codePage.pageNumber))
        }
        return this
    }

    /**
     * Override the printer's code page without changing the emitter's charset.
     *
     * Use this only when you need to temporarily switch code pages
     * within a single document. For most cases, set the code page
     * via the constructor instead.
     *
     * @param page Code page number (0–255)
     */
    fun codePage(page: Int): EscPosEmitter {
        builder.append(InitializationCommands.selectCodePage(page))
        return this
    }

    // ==================== Alignment ====================

    /**
     * Set horizontal text alignment.
     *
     * @param alignment LEFT, CENTER, or RIGHT
     */
    fun align(alignment: Alignment): EscPosEmitter {
        builder.append(AlignmentCommands.setAlignment(alignment))
        return this
    }

    // ==================== Font Size ====================

    /**
     * Set font size mode.
     *
     * @param size Font size enum value
     */
    fun fontSize(size: FontSize): EscPosEmitter {
        builder.append(CharacterCommands.setFontSize(size))
        return this
    }

    // ==================== Character Formatting ====================

    /**
     * Enable or disable bold text.
     *
     * @param enabled True to enable bold, false to disable
     */
    fun bold(enabled: Boolean): EscPosEmitter {
        builder.append(if (enabled) CharacterCommands.BOLD_ON else CharacterCommands.BOLD_OFF)
        return this
    }

    /**
     * Enable or disable underline text.
     *
     * @param enabled True to enable underline, false to disable
     */
    fun underline(enabled: Boolean): EscPosEmitter {
        builder.append(if (enabled) CharacterCommands.UNDERLINE_ON else CharacterCommands.UNDERLINE_OFF)
        return this
    }

    // ==================== Text Output ====================

    /**
     * Append text without a trailing line feed.
     *
     * @param text Text to print
     */
    fun text(text: String): EscPosEmitter {
        builder.append(text)
        return this
    }

    /**
     * Append text followed by a line feed.
     *
     * @param text Text to print
     */
    fun textLine(text: String): EscPosEmitter {
        builder.append(text)
        builder.append(LineFeedCommands.LINE_FEED)
        return this
    }

    // ==================== Line Feed ====================

    /**
     * Emit a single line feed.
     */
    fun newLine(): EscPosEmitter {
        builder.append(LineFeedCommands.LINE_FEED)
        return this
    }

    /**
     * Feed multiple lines.
     *
     * @param count Number of lines to feed (default: 2)
     */
    fun feedLines(count: Int = 2): EscPosEmitter {
        builder.append(LineFeedCommands.feedLines(count))
        return this
    }

    // ==================== Paper Control ====================

    /**
     * Cut the paper.
     *
     * @param type Full or partial cut (default: FULL)
     */
    fun cut(type: CutType = CutType.FULL): EscPosEmitter {
        builder.append(PaperCommands.cut(type))
        return this
    }

    /**
     * Open the cash drawer.
     *
     * @param pin Cash drawer pin (default: PIN_2)
     */
    fun openCashDrawer(pin: CashDrawerPin = CashDrawerPin.PIN_2): EscPosEmitter {
        builder.append(PaperCommands.openCashDrawer(pin))
        return this
    }

    // ==================== Beep / Buzzer ====================

    /**
     * Emit a beep alert after printing.
     *
     * Sends ESC B for duration control, followed by BEL as fallback
     * for printers that don't support ESC B. No-op when DISABLED.
     *
     * @param mode Beep mode (SHORT, LONG, or DISABLED)
     */
    fun beep(mode: BeepMode): EscPosEmitter {
        if (mode != BeepMode.DISABLED) {
            builder.append(BeepCommands.beep(mode))
            builder.append(BeepCommands.bel())
        }
        return this
    }

    // ==================== Kanji Commands ====================

    /**
     * Enable Kanji character mode (FS &).
     *
     * When enabled, the printer interprets two-byte character codes
     * as Kanji characters. Only supported on CJK printer models.
     */
    fun kanjiMode(enabled: Boolean): EscPosEmitter {
        builder.append(if (enabled) KanjiCommands.KANJI_MODE_ON else KanjiCommands.KANJI_MODE_OFF)
        return this
    }

    /**
     * Enable Simplified Chinese mode on Munbyn printers.
     *
     * Sends the Munbyn vendor-specific command (1F 1B 1F EE 4F 4E 54 00)
     * to activate the built-in GB18030 font library.
     * Use this instead of [kanjiMode] for Munbyn ITPP047, P075, etc.
     *
     * @param traditional True for Traditional Chinese (Taiwan/HK), false for Simplified
     */
    fun munbynChineseMode(traditional: Boolean = false): EscPosEmitter {
        builder.append(
            if (traditional) {
                KanjiCommands.MUNBYN_CHINESE_TRADITIONAL
            } else {
                KanjiCommands.MUNBYN_CHINESE_SIMPLIFIED
            },
        )
        return this
    }

    /**
     * Set print mode(s) for Kanji characters (FS !).
     *
     * Controls double-width, double-height, and underline simultaneously.
     *
     * @param doubleWidth Enable double-width mode
     * @param doubleHeight Enable double-height mode
     * @param underline Enable underline mode
     */
    fun kanjiPrintMode(
        doubleWidth: Boolean = false,
        doubleHeight: Boolean = false,
        underline: Boolean = false,
    ): EscPosEmitter {
        builder.append(KanjiCommands.setPrintMode(doubleWidth, doubleHeight, underline))
        return this
    }

    /**
     * Turn underline mode on/off for Kanji characters (FS –).
     *
     * @param mode 0 = OFF, 1 = one-dot underline, 2 = two-dot underline
     */
    fun kanjiUnderline(mode: Int): EscPosEmitter {
        builder.append(KanjiCommands.setUnderline(mode))
        return this
    }

    /**
     * Select Kanji character code system (FS C).
     *
     * @param system Encoding system (JIS, SHIFT_JIS, GB2312, BIG5)
     */
    fun kanjiCodeSystem(system: KanjiCodeSystem): EscPosEmitter {
        builder.append(KanjiCommands.selectCodeSystem(system))
        return this
    }

    /**
     * Set Kanji character spacing (FS S).
     *
     * @param leftSpacing Left-side spacing in dots (0–255)
     * @param rightSpacing Right-side spacing in dots (0–255)
     */
    fun kanjiSpacing(
        leftSpacing: Int,
        rightSpacing: Int,
    ): EscPosEmitter {
        builder.append(KanjiCommands.setSpacing(leftSpacing, rightSpacing))
        return this
    }

    /**
     * Turn quadruple-size mode on/off for Kanji characters (FS W).
     *
     * @param enabled True for 4x size (double height + double width)
     */
    fun kanjiQuadrupleSize(enabled: Boolean): EscPosEmitter {
        builder.append(KanjiCommands.setQuadrupleSize(enabled))
        return this
    }

    /**
     * Select Kanji character font (FS ( A).
     *
     * @param font Font number (0 = Font A, 1 = Font B, etc.)
     */
    fun kanjiFont(font: Int): EscPosEmitter {
        builder.append(KanjiCommands.selectFont(font))
        return this
    }

    // ==================== Raw Output ====================

    /**
     * Append raw bytes directly to the output.
     *
     * Use for commands not covered by the fluent API.
     *
     * @param bytes Raw byte array to append
     */
    fun raw(bytes: ByteArray): EscPosEmitter {
        builder.append(bytes)
        return this
    }

    // ==================== Build ====================

    /**
     * Build the final ESC/POS byte array from all emitted commands.
     *
     * @return Combined byte array ready to send to the printer
     */
    fun build(): ByteArray = builder.build()
}
