package org.example.project.export.datevbuchungsstapelbelege

import org.example.project.export.datevxml.PdfAttachment
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles the DATEV Buchungsstapel-mit-Belegen ZIP: a flat archive containing
 * EXTF_Buchungsstapel.csv + document.xml + the Beleg PDFs. Enforces DATEV's
 * Belegtransfer constraints (Dok. 1005516) and fails fast if violated.
 */
object DatevBuchungsstapelBelegeZipBuilder {
    const val CSV_ENTRY = "EXTF_Buchungsstapel.csv"
    const val DOCUMENT_XML_ENTRY = "document.xml"

    private const val MAX_FILE_BYTES = 20L * 1024 * 1024 // 20 MB per file
    private const val MAX_BELEGE = 5000 // incl. XML files
    private const val MAX_ZIP_BYTES = 476L * 1024 * 1024 // 476 MB total

    fun build(
        csvBytes: ByteArray,
        documentXmlBytes: ByteArray,
        pdfs: List<PdfAttachment>,
    ): ByteArray {
        require(csvBytes.size <= MAX_FILE_BYTES) { "EXTF_Buchungsstapel.csv exceeds 20 MB DATEV limit" }
        require(documentXmlBytes.size <= MAX_FILE_BYTES) { "document.xml exceeds 20 MB DATEV limit" }
        pdfs.forEach {
            require(it.data.size <= MAX_FILE_BYTES) { "Beleg ${it.fileName} exceeds 20 MB DATEV limit" }
            require(!it.fileName.contains('/') && !it.fileName.contains('\\')) {
                "Beleg filename must not contain path separators (flat ZIP required): ${it.fileName}"
            }
        }

        val belegCount = pdfs.size + 1 // document.xml counts as a Beleg file; the CSV is booking data
        require(belegCount <= MAX_BELEGE) { "ZIP has $belegCount Belege (incl. document.xml), exceeds DATEV limit of $MAX_BELEGE" }

        val names = pdfs.map { it.fileName }
        require(names.size == names.toSet().size) { "duplicate Beleg filenames in ZIP" }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(CSV_ENTRY))
            zos.write(csvBytes)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(DOCUMENT_XML_ENTRY))
            zos.write(documentXmlBytes)
            zos.closeEntry()
            pdfs.forEach { pdf ->
                zos.putNextEntry(ZipEntry(pdf.fileName))
                zos.write(pdf.data)
                zos.closeEntry()
            }
        }
        val zip = baos.toByteArray()
        // Not unit-tested: triggering this would require allocating ~476 MB of input bytes.
        require(zip.size <= MAX_ZIP_BYTES) { "ZIP is ${zip.size} bytes, exceeds DATEV limit of $MAX_ZIP_BYTES" }
        return zip
    }
}
