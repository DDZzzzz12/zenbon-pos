package org.example.project.einvoice.detect

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import java.io.IOException

/**
 * Finds the XML payload in an uploaded file.
 *
 * A ZUGFeRD/Factur-X invoice is a PDF/A-3 carrying the CII XML as an embedded file, so a PDF has to
 * be opened before we know whether it is an e-invoice at all. A PDF with no attachment is the normal
 * scanned-document case and yields null rather than an error.
 *
 * Null therefore means one thing only: *this file was read and holds no XML payload*. Anything that
 * prevented the file from being read — a corrupt PDF, one larger than [MAX_PDF_BYTES] — leaves this
 * object as an [EInvoicePayloadException] instead, because "could not look" and "looked, found
 * nothing" have opposite consequences for the operator.
 */
object EInvoicePayloadLocator {
    /** Attachment names used by ZUGFeRD, Factur-X and XRechnung, newest convention first. */
    private val ATTACHMENT_NAMES =
        listOf("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml", "ZUGFeRD-invoice.xml")

    /** Generous for an invoice, but bounded: PDFBox must not be pointed at an unbounded upload. */
    const val MAX_PDF_BYTES: Int = 64 * 1024 * 1024

    /**
     * A name tree is a balanced tree, so a legitimate one is only a few levels deep. The bound
     * exists to stop a hand-crafted file whose `/Kids` cycle back on themselves.
     */
    private const val MAX_NAME_TREE_DEPTH = 32

    fun locate(
        bytes: ByteArray,
        mediaType: String,
    ): ByteArray? =
        when {
            mediaType.contains("xml", ignoreCase = true) -> bytes
            mediaType.contains("pdf", ignoreCase = true) -> embeddedXml(bytes)
            else -> null
        }

    private fun embeddedXml(bytes: ByteArray): ByteArray? {
        if (bytes.size > MAX_PDF_BYTES) {
            throw EInvoicePayloadException("PDF exceeds ${MAX_PDF_BYTES}B limit: ${bytes.size}B")
        }
        return try {
            Loader.loadPDF(bytes).use(::firstAttachment)
        } catch (e: IOException) {
            // PDFBox reports every structural refusal — truncated file, broken xref, unsupported
            // encryption — as an IOException. All of them mean the same thing here: we could not
            // determine whether this PDF carries an invoice.
            throw EInvoicePayloadException("PDF could not be opened: ${e.message}", e)
        } catch (e: RuntimeException) {
            // Every malformed sample we probed came back as an IOException, so this branch is a net
            // rather than a known path: the bytes are supplier-controlled, and a library defect on
            // hostile input must not escape as an untyped exception and fail the whole upload. The
            // sender decides these bytes; they must not decide our failure mode.
            throw EInvoicePayloadException("PDF could not be read: ${e.message}", e)
        }
    }

    private fun firstAttachment(pdf: PDDocument): ByteArray? {
        val root = pdf.documentCatalog?.names?.embeddedFiles ?: return null
        val byName = LinkedHashMap<String, PDComplexFileSpecification>()
        collectNames(root, byName, MAX_NAME_TREE_DEPTH)
        // Prefer the conventional names; fall back to any single .xml attachment, because some
        // senders use their own filename and the PDF still carries a valid EN 16931 payload.
        val chosen =
            ATTACHMENT_NAMES.firstNotNullOfOrNull { byName[it] }
                ?: byName.entries.firstOrNull { it.key.endsWith(".xml", ignoreCase = true) }?.value
        return chosen?.embeddedFileBytes()
    }

    /**
     * Flattens the embedded-files name tree. A producer may put every entry in the root node's
     * `/Names`, or split them across `/Kids` once the tree grows; both are valid, so reading only
     * the root would miss attachments that are plainly there.
     */
    private fun collectNames(
        node: PDNameTreeNode<PDComplexFileSpecification>,
        into: MutableMap<String, PDComplexFileSpecification>,
        depthBudget: Int,
    ) {
        if (depthBudget <= 0) return
        node.names?.forEach { (name, spec) -> into.putIfAbsent(name, spec) }
        node.kids?.forEach { kid -> collectNames(kid, into, depthBudget - 1) }
    }

    /**
     * The three `/EF` variants are alternative spellings of the same stream — a file specification
     * carries whichever its producer chose, so all three have to be tried.
     */
    private fun PDComplexFileSpecification.embeddedFileBytes(): ByteArray? = (embeddedFile ?: embeddedFileUnicode ?: embeddedFileDos)?.toByteArray()
}
