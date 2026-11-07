package org.example.project.einvoice

/**
 * Parses an e-invoice out of raw bytes. Implementations live in sub-project 2; this sub-project
 * defines the boundary only.
 */
fun interface EInvoiceParser {
    /**
     * @param bytes the whole file — a PDF with embedded CII XML, or bare CII/UBL XML.
     * @param mediaType the detected media type, e.g. `application/pdf` or `application/xml`.
     */
    fun parse(
        bytes: ByteArray,
        mediaType: String,
    ): EInvoiceParseResult
}
