/**
 * Type-safe ESC/POS code page enum.
 *
 * Binds the printer-side code page number (ESC t n) to the matching
 * Java charset name so both the byte encoding and the printer's
 * character interpretation stay in sync.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.enums

import java.nio.charset.Charset

/**
 * Character code pages for ESC/POS thermal printers.
 *
 * Each entry couples the ESC/POS code page number with its
 * corresponding Java [Charset] for consistent text encoding.
 *
 * Single-byte code pages (Western, Cyrillic, Arabic, etc.) are selected
 * on the printer via `ESC t n`. CJK code pages (GB18030, Shift-JIS, BIG5)
 * do **not** use `ESC t` — they require Kanji/CJK mode activation via
 * `FS &` or vendor-specific commands instead. The [isCjk] flag distinguishes
 * the two so [EscPosEmitter][org.example.project.printer.core.emitter.EscPosEmitter]
 * can skip the `ESC t` command for CJK pages.
 *
 * @property pageNumber ESC/POS code page number (parameter for ESC t). Unused for CJK pages.
 * @property charset Java charset for encoding strings to bytes
 * @property isCjk True if this is a multi-byte CJK code page that requires Kanji mode
 *                 instead of ESC t for printer-side activation
 */
enum class CodePage(
    val pageNumber: Int,
    val charset: Charset,
    val isCjk: Boolean = false,
) {
    /** Code Page 437 — US English (IBM PC). */
    CP437(0, charset("Cp437")),

    /** Code Page 850 — Western European (Multilingual Latin I). Default for German. */
    CP850(2, charset("Cp850")),

    /** Code Page 858 — Western European with Euro sign. */
    CP858(19, charset("Cp858")),

    /** Code Page 860 — Portuguese. */
    CP860(3, charset("Cp860")),

    /** Code Page 863 — Canadian French. */
    CP863(4, charset("Cp863")),

    /** Code Page 865 — Nordic (Norwegian/Danish). */
    CP865(5, charset("Cp865")),

    /** Windows-1252 — Western European (Windows Latin-1). */
    WINDOWS_1252(16, charset("Windows-1252")),

    /** Code Page 866 — Cyrillic (Russian). */
    CP866(17, charset("Cp866")),

    /** Windows-1256 — Arabic. */
    WINDOWS_1256(46, charset("Windows-1256")),

    /** ISO-8859-1 — Latin-1 (Western European). */
    ISO_8859_1(6, charset("ISO-8859-1")),

    /** ISO-8859-15 — Latin-9 (Western European with Euro sign). */
    ISO_8859_15(40, charset("ISO-8859-15")),

    /**
     * GB18030 — Simplified Chinese.
     *
     * CJK page: requires Kanji mode activation (FS & or vendor command)
     * instead of ESC t. The Java charset handles multi-byte encoding.
     */
    GB18030(0, charset("GB18030"), isCjk = true),

    /**
     * Shift-JIS — Japanese.
     *
     * CJK page: requires Kanji mode activation instead of ESC t.
     */
    SHIFT_JIS(0, charset("Shift_JIS"), isCjk = true),
    ;

    /**
     * The currency suffix printed after an amount: `€` only on a single-byte page whose charset
     * encodes it (CP858, Windows-1252, ISO-8859-15, …); `EUR` otherwise. CP850 — the default — has
     * no `€`, and a CJK page is left on `EUR` because the printer's own font may lack the glyph
     * even where the charset can encode it.
     */
    val currencySuffix: String
        get() = if (!isCjk && charset.newEncoder().canEncode('\u20AC')) "\u20AC" else "EUR"
}
