package org.example.project.banking

/**
 * Parses a bank statement out of raw bytes. Implementations live in sub-project 3; this
 * sub-project defines the boundary only.
 */
fun interface BankStatementParser {
    /**
     * @param bytes the whole file — CAMT.053 XML or MT940 text.
     * @param mediaType the detected media type.
     */
    fun parse(
        bytes: ByteArray,
        mediaType: String,
    ): BankStatementParseResult
}
