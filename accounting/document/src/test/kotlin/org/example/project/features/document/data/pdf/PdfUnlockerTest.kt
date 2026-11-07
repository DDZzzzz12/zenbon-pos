package org.example.project.features.document.data.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PdfUnlockerTest {
    /** A one-page PDF. [userPassword] null = not encrypted; "" = owner-password-only. */
    private fun pdf(userPassword: String?): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            if (userPassword != null) {
                val policy = StandardProtectionPolicy("owner-secret", userPassword, AccessPermission())
                policy.encryptionKeyLength = 256
                doc.protect(policy)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun pagesOf(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { it.numberOfPages }

    private fun isEncrypted(bytes: ByteArray): Boolean = String(bytes, Charsets.ISO_8859_1).contains("/Encrypt")

    @Test
    fun `the right user password yields an unprotected copy with the same pages`() {
        val result = PdfUnlocker.unlock(pdf("s3cret"), "s3cret")

        val unlocked = assertIs<PdfUnlockResult.Unlocked>(result)
        assertFalse(isEncrypted(unlocked.bytes), "the stored copy must carry no encryption dictionary")
        assertEquals(1, pagesOf(unlocked.bytes), "and must open with no password")
    }

    @Test
    fun `an owner-password-only PDF unlocks with an empty password`() {
        val result = PdfUnlocker.unlock(pdf(""), "")

        assertIs<PdfUnlockResult.Unlocked>(result)
    }

    @Test
    fun `a wrong password is reported as incorrect`() {
        assertIs<PdfUnlockResult.PasswordIncorrect>(PdfUnlocker.unlock(pdf("s3cret"), "nope"))
    }

    @Test
    fun `an empty password on a PDF that needs one is reported as required`() {
        assertIs<PdfUnlockResult.PasswordRequired>(PdfUnlocker.unlock(pdf("s3cret"), ""))
    }

    @Test
    fun `a PDF that is not encrypted is reported as such and the password is ignored`() {
        assertIs<PdfUnlockResult.NotEncrypted>(PdfUnlocker.unlock(pdf(null), "whatever"))
    }

    @Test
    fun `bytes that are not a PDF are unreadable rather than an exception`() {
        val result = PdfUnlocker.unlock("this is not a pdf".toByteArray(), "x")

        assertTrue(result is PdfUnlockResult.Unreadable, "got $result")
    }

    @Test
    fun `an unlocked copy that fails verification is unreadable, never Unlocked`() {
        val result = PdfUnlocker.unlock(pdf("s3cret"), "s3cret", verify = { false })

        assertTrue(result is PdfUnlockResult.Unreadable, "got $result")
    }
}
