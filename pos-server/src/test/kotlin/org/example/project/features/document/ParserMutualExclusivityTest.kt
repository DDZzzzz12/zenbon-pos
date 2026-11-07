package org.example.project.features.document

import org.example.project.banking.BankStatementParseResult
import org.example.project.banking.DefaultBankStatementParser
import org.example.project.einvoice.DefaultEInvoiceParser
import org.example.project.einvoice.EInvoiceParseResult
import java.io.File
import java.net.URISyntaxException
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `document.processing_status` is one column with two writers: `uploadDocument` runs both the
 * e-invoice parser (`:accounting-einvoice`) and the bank-statement parser (`:accounting-banking`) over every uploaded
 * file, ungated by the client-declared type. That is only safe because their format detectors
 * never both claim the same file — [org.example.project.einvoice.detect.EInvoiceSyntaxDetector] and
 * [org.example.project.banking.detect.BankStatementFormatDetector] must stay disjoint. If that
 * invariant ever breaks, one parser silently overwrites the other's verdict — a document can end
 * up `COMPLETED` for data that was never actually stored, with no error anywhere.
 *
 * This test does not exercise the upload route or the database; it asserts the invariant directly
 * against the two parser entry points, over the fixture corpus both `EInvoiceUploadIntegrationTest`
 * and `BankStatementUploadIntegrationTest` already use (`pos-server/src/test/resources/corpus/`).
 */
class ParserMutualExclusivityTest {
    private val invoiceParser = DefaultEInvoiceParser()
    private val statementParser = DefaultBankStatementParser()

    @Test
    fun `no corpus fixture is claimed by both parsers`() {
        val fixtures = corpusFixtures()
        assertTrue(fixtures.isNotEmpty(), "no fixtures found under /corpus; this test would otherwise be vacuous")

        val claims =
            fixtures.map { file ->
                val bytes = file.readBytes()
                val mediaType = detectMediaType(bytes)
                val invoiceClaimed = invoiceParser.parse(bytes, mediaType) !is EInvoiceParseResult.NoEInvoiceFound
                val statementClaimed = statementParser.parse(bytes, mediaType) != BankStatementParseResult.NoStatementFound
                Triple(file.name, invoiceClaimed, statementClaimed)
            }

        // Guard on the guard: if neither parser ever claims anything, the "never both" assertion
        // below would pass vacuously while proving nothing about mutual exclusivity.
        assertTrue(
            claims.any { (_, invoiceClaimed, _) -> invoiceClaimed },
            "no fixture was claimed by the e-invoice parser; the corpus can no longer prove exclusivity",
        )
        assertTrue(
            claims.any { (_, _, statementClaimed) -> statementClaimed },
            "no fixture was claimed by the statement parser; the corpus can no longer prove exclusivity",
        )

        val contested = claims.filter { (_, invoiceClaimed, statementClaimed) -> invoiceClaimed && statementClaimed }
        if (contested.isNotEmpty()) {
            fail(
                "both parsers claim these files, so processing_status has two writers: " +
                    contested.joinToString { (name, _, _) -> name },
            )
        }
    }

    private fun detectMediaType(bytes: ByteArray): String =
        when {
            bytes.startsWith("%PDF") -> "application/pdf"
            bytes.startsWith("<?xml") -> "application/xml"
            else -> "application/octet-stream"
        }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        if (size < prefix.length) return false
        return String(this, 0, prefix.length, Charsets.ISO_8859_1) == prefix
    }

    /**
     * Resolved via the classpath root rather than a relative `../` path so the test does not depend
     * on the working directory `:pos-server:test` happens to run in.
     */
    private fun corpusFixtures(): List<File> {
        val corpusUrl = javaClass.getResource("/corpus") ?: return emptyList()
        val corpusDir =
            try {
                File(corpusUrl.toURI())
            } catch (e: URISyntaxException) {
                File(corpusUrl.path)
            }
        if (!corpusDir.isDirectory) return emptyList()
        return corpusDir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension in setOf("xml", "pdf", "sta") }
    }
}
