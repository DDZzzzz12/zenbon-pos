/**
 * Generates the document.xml manifest for DATEV XML Schnittstelle online.
 *
 * The document.xml is the root descriptor file in the DATEV Document-Package ZIP.
 * It uses the namespace `http://xml.datev.de/bedi/tps/document/v06.0` and
 * references per-day ledger XML files via `cashLedger` extension elements.
 *
 * Each `<document>` element contains:
 * - An `<extension xsi:type="cashLedger">` pointing to a ledger file
 * - `<property key="1" value="YYYY-MM"/>` for the accounting period
 * - `<property key="2" value="..."/>` or `<property key="3" value="..."/>` for account/name
 *
 * Uses [javax.xml.stream.XMLStreamWriter] for exact control over namespace
 * declarations, element order, and attribute placement — ensuring XSD compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml

import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import org.example.project.export.datevxml.model.DocumentXml
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Generator for the document.xml manifest in DATEV XML exports.
 */
object DatevXmlDocumentGenerator {
    /**
     * Generates the document.xml content.
     *
     * Each `<document>` element contains the cashLedger extension for that day's
     * ledger XML, plus any `File` extensions for linked PDF documents.
     * Per DATEV XSD, linked PDFs are "geheftet" (stapled) to the import protocol.
     *
     * Output matches the DATEV reference format:
     * ```xml
     * <?xml version="1.0" encoding="UTF-8"?>
     * <archive xmlns="http://xml.datev.de/bedi/tps/document/v06.0"
     *          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     *          xsi:schemaLocation="..." version="6.0" generatingSystem="...">
     *   <header>...</header>
     *   <content>
     *     <document>
     *       <extension xsi:type="cashLedger" datafile="...">
     *         <property key="1" value="..."/>
     *       </extension>
     *     </document>
     *   </content>
     * </archive>
     * ```
     *
     * @param ledgerFileNames List of per-day ledger XML filenames (e.g., "Kasse_20260101.xml")
     * @param config Export configuration with account/name info
     * @param startDate Export period start date (YYYY-MM-DD)
     * @param endDate Export period end date (YYYY-MM-DD)
     * @param pdfFileNamesByDate Map of date (YYYY-MM-DD) to list of PDF filenames in the ZIP
     * @return document.xml content as UTF-8 ByteArray
     */
    fun generate(
        ledgerFileNames: List<String>,
        config: DatevXmlExportDto.ExportConfigDto,
        startDate: String,
        endDate: String,
        pdfFileNamesByDate: Map<String, List<String>> = emptyMap(),
        pdfFileNamesByLedger: Map<String, String> = emptyMap(),
    ): ByteArray {
        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        // Build document entries from ledger filenames
        val documentEntries =
            ledgerFileNames.map { fileName ->
                val dateFromFile = extractDateFromFileName(fileName)
                val period = derivePeriod(dateFromFile)

                // Build properties for the cashLedger extension
                // key="1" = period, key="3" = cash register name (per DATEV spec)
                val properties =
                    listOf(
                        DocumentXml.PropertyXml(key = "1", value = period),
                        DocumentXml.PropertyXml(key = "3", value = config.kassenName),
                    )

                // Build extensions list: cashLedger + optional PDF files
                val extensions =
                    buildList<DocumentXml.ExtensionXml> {
                        add(
                            DocumentXml.CashLedgerExtensionXml(
                                datafile = fileName,
                                properties = properties,
                            ),
                        )

                        // Per-entry PDF linking (einzelDateien mode) takes priority
                        val perEntryPdf = pdfFileNamesByLedger[fileName]
                        if (perEntryPdf != null) {
                            add(DocumentXml.FileExtensionXml(name = perEntryPdf))
                        } else {
                            // Legacy per-date PDF linking (GesamtDatei mode)
                            val pdfNames = pdfFileNamesByDate[dateFromFile].orEmpty()
                            for (pdfName in pdfNames) {
                                add(DocumentXml.FileExtensionXml(name = pdfName))
                            }
                        }
                    }

                DocumentXml.DocumentEntryXml(extensions = extensions)
            }

        val model =
            DocumentXml.ArchiveXml(
                generatingSystem = config.generatingSystem,
                header =
                    DocumentXml.HeaderXml(
                        date = dateStr,
                        description = "DATEV XML Kassenbuch Export $startDate bis $endDate",
                        consultantNumber = config.consultantNumber,
                        clientNumber = config.clientNumber,
                        clientName = config.clientName,
                    ),
                content = DocumentXml.ContentXml(documents = documentEntries),
            )

        return writeArchiveXml(model)
    }

    /**
     * Writes an [DocumentXml.ArchiveXml] model to XML bytes using XMLStreamWriter.
     *
     * @param archive The archive model to serialize
     * @return UTF-8 encoded XML bytes
     */
    private fun writeArchiveXml(archive: DocumentXml.ArchiveXml): ByteArray {
        val baos = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(baos, "UTF-8")

        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeCharacters("\n")

        // <archive xmlns="..." xmlns:xsi="..." xsi:schemaLocation="..." version="6.0" generatingSystem="...">
        writer.writeStartElement("archive")
        writer.writeDefaultNamespace(DocumentXml.NS_DOC)
        writer.writeNamespace("xsi", DocumentXml.NS_XSI)
        writer.writeAttribute(DocumentXml.NS_XSI, "schemaLocation", DocumentXml.SCHEMA_LOCATION)
        writer.writeAttribute("version", archive.version)
        writer.writeAttribute("generatingSystem", archive.generatingSystem)
        writer.writeCharacters("\n")

        // <header>
        writeHeader(writer, archive.header)

        // <content>
        writeContent(writer, archive.content)

        // </archive>
        writer.writeEndElement()
        writer.writeCharacters("\n")
        writer.writeEndDocument()
        writer.flush()
        writer.close()

        return baos.toByteArray()
    }

    /**
     * Writes the `<header>` element with metadata.
     */
    private fun writeHeader(
        writer: XMLStreamWriter,
        header: DocumentXml.HeaderXml,
    ) {
        writer.writeCharacters("  ")
        writer.writeStartElement("header")
        writer.writeCharacters("\n")

        writeTextElement(writer, "date", header.date, indent = 4)
        header.description?.let { writeTextElement(writer, "description", it, indent = 4) }
        header.consultantNumber?.let { writeTextElement(writer, "consultantNumber", it, indent = 4) }
        header.clientNumber?.let { writeTextElement(writer, "clientNumber", it, indent = 4) }
        writeTextElement(writer, "clientName", header.clientName, indent = 4)

        writer.writeCharacters("  ")
        writer.writeEndElement() // </header>
        writer.writeCharacters("\n")
    }

    /**
     * Writes the `<content>` element with all document entries.
     *
     * Per XSD, `<content>` directly contains `<document>` elements (no wrapper).
     */
    private fun writeContent(
        writer: XMLStreamWriter,
        content: DocumentXml.ContentXml,
    ) {
        writer.writeCharacters("  ")
        writer.writeStartElement("content")
        writer.writeCharacters("\n")

        for (doc in content.documents) {
            writeDocument(writer, doc)
        }

        writer.writeCharacters("  ")
        writer.writeEndElement() // </content>
        writer.writeCharacters("\n")
    }

    /**
     * Writes a single `<document>` element with its extensions.
     */
    private fun writeDocument(
        writer: XMLStreamWriter,
        doc: DocumentXml.DocumentEntryXml,
    ) {
        writer.writeCharacters("    ")
        writer.writeStartElement("document")
        writer.writeCharacters("\n")

        for (ext in doc.extensions) {
            writeExtension(writer, ext)
        }

        writer.writeCharacters("    ")
        writer.writeEndElement() // </document>
        writer.writeCharacters("\n")
    }

    /**
     * Writes an `<extension>` element based on its type.
     *
     * Per XSD, `<property>` elements are direct children of `<extension>` (no wrapper).
     */
    private fun writeExtension(
        writer: XMLStreamWriter,
        ext: DocumentXml.ExtensionXml,
    ) {
        when (ext) {
            is DocumentXml.CashLedgerExtensionXml -> {
                writer.writeCharacters("      ")
                writer.writeStartElement("extension")
                writer.writeAttribute(DocumentXml.NS_XSI, "type", "cashLedger")
                writer.writeAttribute("datafile", ext.datafile)

                if (ext.properties.isEmpty()) {
                    writer.writeEndElement() // self-closing
                } else {
                    writer.writeCharacters("\n")
                    for (prop in ext.properties) {
                        writeProperty(writer, prop)
                    }
                    writer.writeCharacters("      ")
                    writer.writeEndElement() // </extension>
                }
                writer.writeCharacters("\n")
            }

            is DocumentXml.ApLedgerExtensionXml -> {
                writer.writeCharacters("      ")
                writer.writeStartElement("extension")
                writer.writeAttribute(DocumentXml.NS_XSI, "type", "accountsPayableLedger")
                writer.writeAttribute("datafile", ext.datafile)

                if (ext.properties.isEmpty()) {
                    writer.writeEndElement()
                } else {
                    writer.writeCharacters("\n")
                    for (prop in ext.properties) {
                        writeProperty(writer, prop)
                    }
                    writer.writeCharacters("      ")
                    writer.writeEndElement() // </extension>
                }
                writer.writeCharacters("\n")
            }

            is DocumentXml.FileExtensionXml -> {
                writer.writeCharacters("      ")
                writer.writeEmptyElement("extension")
                writer.writeAttribute(DocumentXml.NS_XSI, "type", "File")
                writer.writeAttribute("name", ext.name)
                writer.writeCharacters("\n")
            }
        }
    }

    /**
     * Writes a `<property key="..." value="..."/>` empty element.
     */
    private fun writeProperty(
        writer: XMLStreamWriter,
        prop: DocumentXml.PropertyXml,
    ) {
        writer.writeCharacters("        ")
        writer.writeEmptyElement("property")
        writer.writeAttribute("key", prop.key)
        writer.writeAttribute("value", prop.value)
        writer.writeCharacters("\n")
    }

    /**
     * Writes a simple text element like `<name>value</name>`.
     */
    private fun writeTextElement(
        writer: XMLStreamWriter,
        name: String,
        value: String,
        indent: Int,
    ) {
        writer.writeCharacters(" ".repeat(indent))
        writer.writeStartElement(name)
        writer.writeCharacters(value)
        writer.writeEndElement()
        writer.writeCharacters("\n")
    }

    /**
     * Extracts date string from a ledger filename.
     *
     * Handles both formats:
     * - GesamtDatei: "Kasse_20260115.xml" -> "2026-01-15"
     * - einzelDateien: "Kasse_20260115_3.xml" -> "2026-01-15"
     *
     * @param fileName Filename like "Kasse_20260115.xml" or "Kasse_20260115_3.xml"
     * @return Date string like "2026-01-15", or empty if parsing fails
     */
    private fun extractDateFromFileName(fileName: String): String {
        val stripped = fileName.removePrefix("Kasse_").removeSuffix(".xml")
        // Take first 8 chars (the date part), ignoring any _sequence suffix
        val dateStr = if (stripped.length >= 8) stripped.substring(0, 8) else stripped
        return if (dateStr.length == 8) {
            "${dateStr.substring(0, 4)}-${dateStr.substring(4, 6)}-${dateStr.substring(6, 8)}"
        } else {
            ""
        }
    }

    /**
     * Derives the YYYY-MM period from a date string.
     *
     * @param dateStr Date in YYYY-MM-DD format
     * @return Period in YYYY-MM format
     */
    private fun derivePeriod(dateStr: String): String =
        if (dateStr.length >= 7) {
            dateStr.substring(0, 7)
        } else {
            LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
        }
}
