package org.example.project.banking.detect

import org.example.project.banking.model.BankStatementFormat

/**
 * Decides, from the bytes alone, whether a file is a bank statement this module can read.
 *
 * Content, never the client's declared type: a browser sends `application/octet-stream` for any
 * extension it does not recognise, and trusting that would file a real statement as a plain scan
 * with no error anywhere.
 *
 * Returning null is the **normal** outcome. Most uploads are invoices and receipts, and this
 * detector must claim none of them — the e-invoice parser runs over the same bytes, and the two
 * must be mutually exclusive so that only one of them can write a document's processing status.
 */
object BankStatementFormatDetector {
    /** Enough to see a root element or the first few SWIFT tags without reading a large file. */
    private const val SNIFF_BYTES = 4096

    private val CAMT_NAMESPACE = Regex("""urn:iso:std:iso:20022:tech:xsd:camt\.053\.001\.\d{2}""")

    /**
     * `:20:` (transaction reference) is the mandatory first tag of an MT940 statement, optionally
     * behind SWIFT block framing. Requiring a second tag as well is what separates a statement from
     * a prose document that happens to contain a colon-delimited token.
     */
    private val MT940_TAG = Regex("""(?m)^:(\d{2}[A-Z]?):""")

    fun detect(
        bytes: ByteArray,
        mediaType: String,
    ): BankStatementFormat? {
        if (bytes.isEmpty()) return null
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)

        if (CAMT_NAMESPACE.containsMatchIn(head)) return BankStatementFormat.CAMT053
        if (looksLikeMt940(head)) return BankStatementFormat.MT940
        return null
    }

    /**
     * The `:20:` tag must appear at the start of a line — after block framing is stripped — and at
     * least one further tag must follow. A single tag-shaped token in running text is not a
     * statement.
     */
    private fun looksLikeMt940(head: String): Boolean {
        val body = head.substringAfter("{4:", head)
        val tags =
            MT940_TAG
                .findAll(body)
                .map { it.groupValues[1] }
                .take(3)
                .toList()
        return tags.firstOrNull() == "20" && tags.size >= 2
    }
}
