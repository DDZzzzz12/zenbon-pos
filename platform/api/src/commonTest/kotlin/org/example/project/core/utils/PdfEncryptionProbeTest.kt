package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfEncryptionProbeTest {
    private fun bytes(text: String) = text.encodeToByteArray()

    @Test
    fun aPdfWhoseTrailerNamesAnEncryptDictionaryLooksEncrypted() {
        val pdf = bytes("%PDF-1.7\n1 0 obj\n<< >>\nendobj\ntrailer\n<< /Root 1 0 R /Encrypt 5 0 R >>\n%%EOF")
        assertTrue(PdfEncryptionProbe.looksEncrypted(pdf))
    }

    @Test
    fun aPlainPdfDoesNot() {
        val pdf = bytes("%PDF-1.7\n1 0 obj\n<< >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF")
        assertFalse(PdfEncryptionProbe.looksEncrypted(pdf))
    }

    @Test
    fun aFileThatIsNotAPdfNeverLooksEncrypted() {
        assertFalse(PdfEncryptionProbe.looksEncrypted(bytes("hello /Encrypt world")))
    }

    @Test
    fun anEmptyOrTinyFileDoesNot() {
        assertFalse(PdfEncryptionProbe.looksEncrypted(ByteArray(0)))
        assertFalse(PdfEncryptionProbe.looksEncrypted(bytes("%PD")))
    }

    @Test
    fun theMarkerAtTheVeryEndIsFound() {
        assertTrue(PdfEncryptionProbe.looksEncrypted(bytes("%PDF-1.7 ") + bytes("/Encrypt")))
    }
}
