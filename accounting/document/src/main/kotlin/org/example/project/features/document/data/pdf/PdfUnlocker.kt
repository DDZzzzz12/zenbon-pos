package org.example.project.features.document.data.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Removes the password protection from an uploaded PDF.
 *
 * The password is only ever a parameter here: it is never logged, never put in a [PdfUnlockResult]
 * reason and never in an exception message, so nothing downstream can leak it.
 *
 * "Empty password" is a real case, not a missing value: a PDF with only an *owner* password opens
 * with the empty user password, and PDFBox then lets us strip it.
 *
 * The unlocked copy is **re-opened with no password** before it is returned. A copy that still
 * demands a password, or cannot be read, would otherwise be archived as the operator's "unlocked"
 * document and be unreadable later, when nobody remembers the file.
 */
object PdfUnlocker {
    fun unlock(
        bytes: ByteArray,
        password: String,
        verify: (ByteArray) -> Boolean = ::opensWithoutPassword,
    ): PdfUnlockResult =
        try {
            open(bytes, password).use { stripProtection(it, verify) }
        } catch (e: InvalidPasswordException) {
            if (password.isEmpty()) PdfUnlockResult.PasswordRequired else PdfUnlockResult.PasswordIncorrect
        } catch (e: IOException) {
            PdfUnlockResult.Unreadable("PDF could not be opened: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            // The bytes are operator-supplied but originate from a third party; a library defect on a
            // hostile file must not escape as an untyped failure.
            PdfUnlockResult.Unreadable("PDF could not be read: ${e.javaClass.simpleName}")
        }

    private fun open(
        bytes: ByteArray,
        password: String,
    ): PDDocument = Loader.loadPDF(bytes, password)

    private fun stripProtection(
        document: PDDocument,
        verify: (ByteArray) -> Boolean,
    ): PdfUnlockResult {
        if (!document.isEncrypted) return PdfUnlockResult.NotEncrypted
        document.setAllSecurityToBeRemoved(true)
        val unlocked = ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        return if (verify(unlocked)) {
            PdfUnlockResult.Unlocked(unlocked)
        } else {
            PdfUnlockResult.Unreadable("unlocked copy failed verification")
        }
    }

    private fun opensWithoutPassword(bytes: ByteArray): Boolean =
        try {
            Loader.loadPDF(bytes).use { !it.isEncrypted }
        } catch (e: IOException) {
            false
        }
}
