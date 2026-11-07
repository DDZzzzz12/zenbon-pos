package org.example.project.export.datevbuchungsstapel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatevBuchungsstapelFieldValidatorTest {
    @Test
    fun `sanitizeText strips quotes, replaces semicolons with commas, strips CR, LF to space, trims`() {
        val input = " Hello; \"World\"\r\nrest "
        assertEquals("Hello, World rest", DatevBuchungsstapelFieldValidator.sanitizeText(input))
    }

    @Test
    fun `truncate caps at max length`() {
        assertEquals("abcde", DatevBuchungsstapelFieldValidator.truncate("abcdefghij", 5))
    }

    @Test
    fun `truncate passes shorter strings unchanged`() {
        assertEquals("abc", DatevBuchungsstapelFieldValidator.truncate("abc", 5))
    }

    @Test
    fun `sanitizeAndTruncate combines both operations`() {
        val result =
            DatevBuchungsstapelFieldValidator.sanitizeAndTruncate(
                " \"very long; text here with semicolons\" ",
                max = 12,
            )
        assertEquals(12, result.length)
        assertTrue(!result.contains('"'))
        assertTrue(!result.contains(';'))
    }

    @Test
    fun `validateAccountNumber accepts digits within sachkontenlaenge`() {
        DatevBuchungsstapelFieldValidator.validateAccountNumber(1600, sachkontenlaenge = 4, fieldName = "Konto")
        DatevBuchungsstapelFieldValidator.validateAccountNumber(99999999, sachkontenlaenge = 8, fieldName = "Konto")
    }

    @Test
    fun `validateAccountNumber rejects too-long number`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                DatevBuchungsstapelFieldValidator.validateAccountNumber(123456, sachkontenlaenge = 4, fieldName = "Konto")
            }
        assertTrue(ex.message!!.contains("Konto"))
        assertTrue(ex.message!!.contains("Sachkontenlänge=4"))
    }

    @Test
    fun `validateAccountNumber rejects negative value`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelFieldValidator.validateAccountNumber(-1, sachkontenlaenge = 4, fieldName = "Konto")
        }
    }

    @Test
    fun `validateConsultantNumber accepts digits-only`() {
        DatevBuchungsstapelFieldValidator.validateConsultantNumber("29098")
    }

    @Test
    fun `validateConsultantNumber rejects non-digit input`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelFieldValidator.validateConsultantNumber("29098A")
        }
    }

    @Test
    fun `validateConsultantNumber rejects empty input`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelFieldValidator.validateConsultantNumber("")
        }
    }

    @Test
    fun `validateClientNumber rejects non-digit input`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelFieldValidator.validateClientNumber("ABC")
        }
    }
}
