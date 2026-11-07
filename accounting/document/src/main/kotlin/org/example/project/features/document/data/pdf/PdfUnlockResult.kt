package org.example.project.features.document.data.pdf

/** What [PdfUnlocker.unlock] made of a PDF. Exhaustive on purpose: callers must handle every case. */
sealed interface PdfUnlockResult {
    /** The protection was removed; [bytes] opens with no password. */
    class Unlocked(
        val bytes: ByteArray,
    ) : PdfUnlockResult

    /** The PDF is not encrypted at all; nothing to do and any supplied password is ignored. */
    data object NotEncrypted : PdfUnlockResult

    /** The PDF needs a non-empty password and an empty one was supplied. */
    data object PasswordRequired : PdfUnlockResult

    /** A non-empty password was supplied and does not open the PDF. */
    data object PasswordIncorrect : PdfUnlockResult

    /** The bytes could not be read as a PDF, or the unlocked copy failed verification. */
    class Unreadable(
        val reason: String,
    ) : PdfUnlockResult
}
