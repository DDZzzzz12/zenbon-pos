package org.example.project.export.datevxml

import org.example.project.export.datevxml.model.LedgerXml
import java.io.ByteArrayOutputStream
import javax.xml.stream.XMLOutputFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class DatevApLedgerGeneratorTest {
    /**
     * Verifies that writeApLedgerEntry emits XML elements in XSD sequence order.
     *
     * XSD order (base -> base1 -> accountsPayableLedger):
     * date, amount, accountNo, buCode, costCategoryId, costCategoryId2, tax, information,
     * currencyCode, invoiceId, bookingText, shipFromCountry, partyId, paidAt, vatId,
     * shipToCountry, iban, dueDate, bpAccountNo, deliveryDate, orderId,
     * supplierName, supplierCity
     */
    @Test
    fun `writeApLedgerEntry emits elements in XSD sequence order`() {
        val entry =
            LedgerXml.ApLedgerEntryXml(
                date = "2023-01-12",
                amount = "1190.00",
                accountNo = "3400",
                buCode = "701",
                costCategoryId = "10",
                costCategoryId2 = "20",
                tax = "19.00",
                information = "Test invoice",
                currencyCode = "EUR",
                invoiceId = "R2023x101",
                bookingText = "Produkt B",
                shipFromCountry = "FR",
                partyId = "K333",
                paidAt = "2023-02-05",
                vatId = "DE111654321",
                shipToCountry = "DE",
                iban = "DE38500700100087654321",
                dueDate = "2023-01-26",
                bpAccountNo = "70100",
                deliveryDate = "2023-01-09",
                orderId = "ORD-001",
                supplierName = "Musterlieferant",
                supplierCity = "Musterort",
            )

        val baos = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(baos, "UTF-8")
        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeStartElement("root")
        DatevXmlLedgerGenerator.writeApLedgerEntry(writer, entry)
        writer.writeEndElement()
        writer.writeEndDocument()
        writer.flush()
        writer.close()

        val xml = baos.toString("UTF-8")

        // Verify all elements are present
        val expectedElements =
            listOf(
                "date",
                "amount",
                "accountNo",
                "buCode",
                "costCategoryId",
                "costCategoryId2",
                "tax",
                "information",
                "currencyCode",
                "invoiceId",
                "bookingText",
                "shipFromCountry",
                "partyId",
                "paidAt",
                "vatId",
                "shipToCountry",
                "iban",
                "dueDate",
                "bpAccountNo",
                "deliveryDate",
                "orderId",
                "supplierName",
                "supplierCity",
            )
        for (elem in expectedElements) {
            assertTrue(xml.contains("<$elem>"), "Missing element: $elem")
        }

        // Verify XSD sequence order by checking index positions
        val positions =
            expectedElements.map { elem ->
                val idx = xml.indexOf("<$elem>")
                assertTrue(idx >= 0, "Element <$elem> not found in XML")
                elem to idx
            }

        for (i in 0 until positions.size - 1) {
            val (elemA, posA) = positions[i]
            val (elemB, posB) = positions[i + 1]
            assertTrue(
                posA < posB,
                "XSD order violation: <$elemA> (pos $posA) must come before <$elemB> (pos $posB)",
            )
        }
    }

    @Test
    fun `writeApLedgerEntry omits null optional fields`() {
        val entry =
            LedgerXml.ApLedgerEntryXml(
                date = "2023-01-12",
                amount = "1190.00",
                currencyCode = "EUR",
                invoiceId = "R2023x101",
                // all optional fields are null
            )

        val baos = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(baos, "UTF-8")
        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeStartElement("root")
        DatevXmlLedgerGenerator.writeApLedgerEntry(writer, entry)
        writer.writeEndElement()
        writer.writeEndDocument()
        writer.flush()
        writer.close()

        val xml = baos.toString("UTF-8")

        // Required fields present
        assertTrue(xml.contains("<date>2023-01-12</date>"))
        assertTrue(xml.contains("<amount>1190.00</amount>"))
        assertTrue(xml.contains("<currencyCode>EUR</currencyCode>"))
        assertTrue(xml.contains("<invoiceId>R2023x101</invoiceId>"))

        // Optional fields absent
        assertTrue(!xml.contains("<accountNo>"), "accountNo should be absent when null")
        assertTrue(!xml.contains("<buCode>"), "buCode should be absent when null")
        assertTrue(!xml.contains("<supplierName>"), "supplierName should be absent when null")
        assertTrue(!xml.contains("<paidAt>"), "paidAt should be absent when null")
        assertTrue(!xml.contains("<shipFromCountry>"), "shipFromCountry should be absent when null")
    }

    @Test
    fun `writeApLedgerEntry handles BU codes longer than 2 digits`() {
        val entry =
            LedgerXml.ApLedgerEntryXml(
                date = "2023-01-12",
                amount = "1000.00",
                accountNo = "3200",
                buCode = "701",
                tax = "19.00",
                currencyCode = "EUR",
                invoiceId = "R2023x101xEU",
            )

        val baos = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(baos, "UTF-8")
        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeStartElement("root")
        DatevXmlLedgerGenerator.writeApLedgerEntry(writer, entry)
        writer.writeEndElement()
        writer.writeEndDocument()
        writer.flush()
        writer.close()

        val xml = baos.toString("UTF-8")
        assertTrue(xml.contains("<buCode>701</buCode>"), "3-digit BU code 701 must be preserved")
    }
}
