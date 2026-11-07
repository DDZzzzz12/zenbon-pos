package org.example.project.features.settings.presentation.components.printer

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression cover for issue #3 — "print history page crashes instantly".
 *
 * The screen renders one [PrintJobCard] per print job and every card formats its `printedAt`
 * through [formatDateTime]. At v1.0.9 that function passed `dateTime.month` — a
 * `kotlinx.datetime.Month`, not an `Int` — to a `%02d` slot of `String.format`, so the JVM
 * formatter threw `IllegalFormatConversionException` while composing the very first card. The
 * screen died before a row appeared, which is exactly how the crash was reported. It was fixed on
 * `dev` in c424cf225 (`monthNumber`) and again in c18277c53, which dropped `String.format`
 * altogether for `padStart` because it is JVM-only and shared-ui must also compile for wasm.
 *
 * Nothing but the running app exercised this path, which is why a formatting slip could ship as a
 * crash. These assertions call the formatter directly: a return to the `Month`-into-`%02d` shape
 * cannot pass them, because it cannot return at all.
 */
class PrintJobTimestampFormatTest {
    private val december = LocalDateTime(2025, 12, 16, 14, 30, 0)

    @Test
    fun `German formats a two-digit day, month and 24-hour clock`() {
        assertEquals("16.12.2025 14:30", formatDateTime(december, Language.GERMAN))
    }

    @Test
    fun `English formats month-first with a 12-hour clock`() {
        assertEquals("12/16/2025 2:30 PM", formatDateTime(december, Language.ENGLISH))
    }

    @Test
    fun `Chinese uses the same 12-hour rendering as English`() {
        assertEquals("12/16/2025 2:30 PM", formatDateTime(december, Language.CHINESE))
    }

    @Test
    fun `a single-digit month is zero-padded rather than rendered as its enum name`() {
        // The exact shape of the original defect: the month is the field that was fed to the
        // formatter as an enum. January is where an unpadded or name-rendered month shows up.
        val january = LocalDateTime(2026, 1, 5, 9, 7, 0)
        assertEquals("05.01.2026 09:07", formatDateTime(january, Language.GERMAN))
        assertEquals("01/05/2026 9:07 AM", formatDateTime(january, Language.ENGLISH))
    }

    @Test
    fun `midnight and noon do not collapse to hour zero`() {
        val midnight = LocalDateTime(2026, 3, 1, 0, 5, 0)
        assertEquals("01.03.2026 00:05", formatDateTime(midnight, Language.GERMAN))
        assertEquals("03/01/2026 12:05 AM", formatDateTime(midnight, Language.ENGLISH))

        val noon = LocalDateTime(2026, 3, 1, 12, 0, 0)
        assertEquals("01.03.2026 12:00", formatDateTime(noon, Language.GERMAN))
        assertEquals("03/01/2026 12:00 PM", formatDateTime(noon, Language.ENGLISH))
    }
}
