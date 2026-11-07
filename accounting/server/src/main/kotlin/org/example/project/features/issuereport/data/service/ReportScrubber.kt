package org.example.project.features.issuereport.data.service

/**
 * Best-effort removal of secrets and personal data from text that is about to leave the server.
 *
 * It is a filter, not a guarantee: the operator reading the exact preview before sending is the real
 * safeguard. Order matters — the IBAN pattern must run before the long-digit pattern, or the digit
 * pass would shred an IBAN into `DE[number]` and leave the checksum prefix behind.
 */
object ReportScrubber {
    private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val GITHUB_TOKEN = Regex("\\b(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}(?:\\s?[A-Z0-9]{1,4})?\\b")
    private val LONG_DIGITS = Regex("\\d{9,}")

    fun scrub(text: String): String =
        text
            .replace(BEARER, "Bearer [redacted]")
            .replace(JWT, "[jwt]")
            .replace(GITHUB_TOKEN, "[token]")
            .replace(EMAIL, "[email]")
            .replace(IBAN, "[iban]")
            .replace(LONG_DIGITS, "[number]")
}
