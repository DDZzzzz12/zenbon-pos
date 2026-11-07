package org.example.project.export.datevxml

import org.example.project.export.datevxml.model.DocumentXml
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory

/**
 * Generates a Beleg-only document.xml for the DATEV Buchungsstapel-mit-Belegen ZIP.
 *
 * Unlike [DatevXmlDocumentGenerator] (structured cashLedger import), each
 * `<document>` here carries a `guid` and a single `File` extension only — no
 * ledger datafile — because the bookings come from the accompanying
 * EXTF_Buchungsstapel.csv and are linked by `Beleglink = BEDI "<guid>"`.
 */
object DatevBeleglinkDocumentGenerator {
    /** One Beleg: the PDF filename in the ZIP and the GUID shared with the CSV Beleglink. */
    data class BelegRef(
        val guid: String,
        val pdfFileName: String,
    )

    fun generate(
        belege: List<BelegRef>,
        description: String,
        consultantNumber: String?,
        clientNumber: String?,
        clientName: String,
        generatingSystem: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): ByteArray {
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val baos = ByteArrayOutputStream()
        val w = XMLOutputFactory.newInstance().createXMLStreamWriter(baos, "UTF-8")

        w.writeStartDocument("UTF-8", "1.0")
        w.writeCharacters("\n")
        w.writeStartElement("archive")
        w.writeDefaultNamespace(DocumentXml.NS_DOC)
        w.writeNamespace("xsi", DocumentXml.NS_XSI)
        w.writeAttribute(DocumentXml.NS_XSI, "schemaLocation", DocumentXml.SCHEMA_LOCATION)
        w.writeAttribute("version", "6.0")
        w.writeAttribute("generatingSystem", generatingSystem)
        w.writeCharacters("\n")

        w.writeStartElement("header")
        w.writeStartElement("date")
        w.writeCharacters(dateStr)
        w.writeEndElement()
        w.writeStartElement("description")
        w.writeCharacters(description)
        w.writeEndElement()
        if (!consultantNumber.isNullOrBlank()) {
            w.writeStartElement("consultantNumber")
            w.writeCharacters(consultantNumber)
            w.writeEndElement()
        }
        if (!clientNumber.isNullOrBlank()) {
            w.writeStartElement("clientNumber")
            w.writeCharacters(clientNumber)
            w.writeEndElement()
        }
        w.writeStartElement("clientName")
        w.writeCharacters(clientName)
        w.writeEndElement()
        w.writeEndElement() // header
        w.writeCharacters("\n")

        w.writeStartElement("content")
        for (b in belege) {
            w.writeStartElement("document")
            w.writeAttribute("guid", b.guid)
            w.writeStartElement("extension")
            w.writeAttribute(DocumentXml.NS_XSI, "type", "File")
            w.writeAttribute("name", b.pdfFileName)
            w.writeEndElement() // extension
            w.writeEndElement() // document
        }
        w.writeEndElement() // content

        w.writeEndElement() // archive
        w.writeEndDocument()
        w.flush()
        w.close()
        return baos.toByteArray()
    }
}
