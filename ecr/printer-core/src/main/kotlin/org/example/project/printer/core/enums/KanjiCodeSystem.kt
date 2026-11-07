/**
 * Type-safe ESC/POS Kanji character code system enum.
 *
 * Encapsulates the FS C command parameter for selecting
 * the Kanji character encoding system on CJK printer models.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.enums

/**
 * Kanji character code system selection for CJK printer models.
 *
 * Used with the FS C command to switch between JIS and Shift-JIS
 * encoding on Japanese models, or GB2312/BIG5 on Chinese models.
 *
 * @property code The parameter byte value for the FS C command
 */
enum class KanjiCodeSystem(
    val code: Byte,
) {
    /** JIS encoding (Japanese Industrial Standards). */
    JIS(0x00),

    /** Shift-JIS encoding (Microsoft/IBM extension of JIS). */
    SHIFT_JIS(0x01),

    /** GB2312/GB18030 — Simplified Chinese encoding. */
    GB2312(0x00),

    /** BIG5 — Traditional Chinese encoding. */
    BIG5(0x01),
}
