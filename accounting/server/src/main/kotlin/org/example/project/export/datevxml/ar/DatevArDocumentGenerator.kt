/**
 * Generates the document.xml manifest for DATEV XML Ausgangsrechnungen export.
 *
 * Mirrors [org.example.project.export.datevxml.ap.DatevApDocumentGenerator] — uses an
 * `accountsReceivableLedger` extension type and includes
 * `<property key="3" value="Ausgangsrechnungen"/>` instead of the AP "Eingangsrechnungen" text.
 *
 * Each `<document>` element references a per-invoice ledger XML file and
 * the associated invoice PDF as a `File` extension.
 *
 * The shared `DocumentXml` model is reused as-is: entries are carried as
 * [org.example.project.export.datevxml.model.DocumentXml.ApLedgerExtensionXml] instances
 * (a generic datafile+properties holder with no AP-specific data), and only the
 * `xsi:type` string written by this generator differs from the AP writer.
 *
 * Uses [javax.xml.stream.XMLStreamWriter] for exact control over namespace
 * declarations and element structure — ensuring XSD compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml.ar

import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.export.datevxml.model.DocumentXml
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Generator for the document.xml manifest in DATEV AR XML exports.
 */
object DatevArDocumentGenerator {
    /**
     * Entry describing a single invoice in the export for document.xml generation.
     *
     * @property ledgerFileName The generated ledger XML filename (e.g., "Rechnungsdaten_AR_001.xml")
     * @property invoiceDate Invoice date in YYYY-MM-DD format (used to derive period)
     * @property pdfFileName The invoice PDF filename in the ZIP, or null if no PDF
     */
    data class ArDocumentEntry(
        val ledgerFileName: String,
        val invoiceDate: String,
        val pdfFileName: String? = null,
    )

    /**
     * Generates the document.xml content for AR export.
     *
     * Each `<document>` element contains an `accountsReceivableLedger` extension
     * for the invoice's ledger XML, a property key=3 "Ausgangsrechnungen",
     * and optionally a `File` extension for the invoice PDF.
     *
     * @param entries List of AR document entries (one per invoice)
     * @param config Export configuration with client info
     * @param startDate Export period start date (YYYY-MM-DD)
     * @param endDate Export period end date (YYYY-MM-DD)
     * @return document.xml content as UTF-8 ByteArray
     */
    fun generate(
        entries: List<ArDocumentEntry>,
        config: DatevArExportDto.ArExportConfigDto,
        startDate: String,
        endDate: String,
    ): ByteArray {
        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        // Build document entries from AR invoice entries
        val documentEntries =
            entries.map { entry ->
                val period = derivePeriod(entry.invoiceDate)

                // Build extensions list: accountsReceivableLedger + optional PDF
                val extensions =
                    buildList<DocumentXml.ExtensionXml> {
                        add(
                            DocumentXml.ApLedgerExtensionXml(
                                datafile = entry.ledgerFileName,
                                properties =
                                    listOf(
                                        DocumentXml.PropertyXml(key = "1", value = period),
                                        DocumentXml.PropertyXml(key = "3", value = "Ausgangsrechnungen"),
                                    ),
                            ),
                        )
                        // Add invoice PDF as File extension if present
                        entry.pdfFileName?.let { pdfName ->
                            add(DocumentXml.FileExtensionXml(name = pdfName))
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
                        description = "DATEV XML Ausgangsrechnungen Export $startDate bis $endDate",
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
     * [DocumentXml.ApLedgerExtensionXml] is reused generically here to carry the AR
     * ledger reference (datafile + properties) — this writer emits `xsi:type="accountsReceivableLedger"`
     * for it rather than the AP writer's `"accountsPayableLedger"`.
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
                    writer.writeEndElement()
                } else {
                    writer.writeCharacters("\n")
                    for (prop in ext.properties) {
                        writeProperty(writer, prop)
                    }
                    writer.writeCharacters("      ")
                    writer.writeEndElement()
                }
                writer.writeCharacters("\n")
            }

            is DocumentXml.ApLedgerExtensionXml -> {
                writer.writeCharacters("      ")
                writer.writeStartElement("extension")
                writer.writeAttribute(DocumentXml.NS_XSI, "type", "accountsReceivableLedger")
                writer.writeAttribute("datafile", ext.datafile)

                if (ext.properties.isEmpty()) {
                    writer.writeEndElement()
                } else {
                    writer.writeCharacters("\n")
                    for (prop in ext.properties) {
                        writeProperty(writer, prop)
                    }
                    writer.writeCharacters("      ")
                    writer.writeEndElement()
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
     * Derives the YYYY-MM period from a date string.
     */
    private fun derivePeriod(dateStr: String): String =
        if (dateStr.length >= 7) {
            dateStr.substring(0, 7)
        } else {
            LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
        }
}
