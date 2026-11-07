package org.example.project.features.kassenbuch.data.service

import org.example.project.export.datevxml.PdfAttachment
import org.example.project.features.document.domain.models.linkedDocument
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.pdf.util.FileStorageHelper
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Resolves the digital Beleg for Kassenbuch headers (linked-document PDF, else
 * generated Z-Bon PDF) for the Buchungsstapel-mit-Belegen export.
 *
 * Returns both the collected PDF bytes (deduped by filename) AND a per-header
 * filename map built in the SAME pass — so a header is mapped to a Beleg filename
 * only when that PDF is actually part of the collected set. This guarantees the
 * CSV Beleglink GUID always has a matching entry in document.xml.
 *
 * Kept separate from the safety-adjacent DatevXmlExportServiceImpl (which is left
 * untouched); the filename convention mirrors that export's collectLinkedPdfs.
 */
class KassenbuchBelegResolver(
    private val zBonService: ZBonService,
) {
    private val logger = LoggerFactory.getLogger(KassenbuchBelegResolver::class.java)

    /**
     * @property belegFileNameByHeaderId header id -> its Beleg PDF filename (only headers with a collected Beleg)
     * @property pdfs unique collected PDFs (deduped by filename)
     */
    data class ResolvedBelege(
        val belegFileNameByHeaderId: Map<Long, String>,
        val pdfs: List<PdfAttachment>,
    )

    suspend fun resolve(
        headers: List<KassenbuchHeader>,
        documentStoragePath: String,
        isoDateOf: (KassenbuchHeader) -> String,
    ): ResolvedBelege {
        val seen = mutableSetOf<String>()
        val pdfs = mutableListOf<PdfAttachment>()
        val byHeader = mutableMapOf<Long, String>()

        for (header in headers) {
            val isoDate = isoDateOf(header)
            val headerId = header.id.value

            // 1) linked document PDF — only if active AND present on disk
            val linkedDoc = header.linkedDocument
            if (linkedDoc != null && linkedDoc.isActive) {
                val fileName = linkedDoc.filename
                if (fileName in seen) {
                    byHeader[headerId] = fileName
                    continue
                }
                val resolvedPath = FileStorageHelper.resolveStoredPath(linkedDoc.filePath, documentStoragePath)
                val pdfFile = File(resolvedPath)
                if (pdfFile.exists() && pdfFile.isFile) {
                    try {
                        val bytes = pdfFile.readBytes()
                        seen.add(fileName)
                        pdfs.add(PdfAttachment(date = isoDate, fileName = fileName, data = bytes))
                        byHeader[headerId] = fileName
                        continue
                    } catch (e: Exception) {
                        logger.warn("KassenbuchBelegResolver: failed to read PDF {}: {}", linkedDoc.filePath, e.message)
                        // fall through to Z-Bon
                    }
                } else {
                    logger.warn("KassenbuchBelegResolver: stored PDF not found on disk: {} (header {})", linkedDoc.filePath, headerId)
                    // fall through to Z-Bon
                }
            }

            // 2) Z-Bon PDF fallback
            val zBon = header.zBon
            if (zBon != null) {
                val fileName = "ZBon_Nr${zBon.zbonNummer}_$isoDate.pdf"
                if (fileName in seen) {
                    byHeader[headerId] = fileName
                    continue
                }
                try {
                    val bytes = zBonService.getZBonPdfBytes(zBon.id.value)
                    seen.add(fileName)
                    pdfs.add(PdfAttachment(date = isoDate, fileName = fileName, data = bytes))
                    byHeader[headerId] = fileName
                } catch (e: Exception) {
                    logger.warn("KassenbuchBelegResolver: error generating Z-Bon PDF for zbonId={}: {}", zBon.id.value, e.message)
                }
            }
        }
        return ResolvedBelege(belegFileNameByHeaderId = byHeader, pdfs = pdfs)
    }
}
