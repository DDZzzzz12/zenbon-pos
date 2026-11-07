/**
 * Lightweight data holder for PDF files to include in DATEV XML exports.
 *
 * Represents a single PDF document linked to a Kassenbuch entry,
 * carrying the filename and raw bytes for ZIP packaging.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml

/**
 * PDF attachment for inclusion in DATEV XML export ZIP.
 *
 * @property date The entry date this PDF is associated with (YYYY-MM-DD)
 * @property fileName Filename for the PDF inside the ZIP archive
 * @property data Raw PDF file bytes
 */
data class PdfAttachment(
    val date: String,
    val fileName: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfAttachment) return false
        return date == other.date && fileName == other.fileName
    }

    override fun hashCode(): Int {
        var result = date.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}
