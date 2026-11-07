package org.example.project.export.datevxml

import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import kotlin.test.Test
import kotlin.test.assertTrue

class DatevXmlLedgerGeneratorTest {
    @Test
    fun `generate produces XML with single cashLedger for one entry`() {
        val entries =
            listOf(
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-89.50",
                    accountNo = "1200",
                    tax = "19.00",
                    currencyCode = "EUR",
                    invoiceId = "RE-001",
                    bookingText = "Tankrechnung",
                    headerId = 1,
                ),
            )

        val result =
            DatevXmlLedgerGenerator.generate(
                entries = entries,
                date = "2023-01-12",
                generatorInfo = "Test Shop",
                generatingSystem = "POS-KMP",
            )

        val xml = String(result, Charsets.UTF_8)
        assertTrue(xml.contains("consolidatedAmount=\"-89.50\""), "consolidatedAmount should equal entry amount")
        assertTrue(xml.contains("consolidatedDate=\"2023-01-12\""), "consolidatedDate should match entry date")
        assertTrue(xml.contains("<cashLedger>"), "should contain cashLedger element")
        assertTrue(xml.contains("<amount>-89.50</amount>"), "should contain amount element")
        assertTrue(xml.contains("<bookingText>Tankrechnung</bookingText>"), "should contain bookingText")
        assertTrue(xml.contains("<invoiceId>RE-001</invoiceId>"), "should contain invoiceId")
    }

    @Test
    fun `generate sums amounts for multi-entry consolidate`() {
        val entries =
            listOf(
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "3223.90",
                    tax = "7.00",
                    bookingText = "TE7%",
                    headerId = 100,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "673.60",
                    tax = "19.00",
                    bookingText = "TE19%",
                    headerId = 100,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-2106.90",
                    bookingText = "EC-Karte",
                    headerId = 100,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-42.90",
                    bookingText = "Gutschein",
                    headerId = 100,
                ),
            )

        val result =
            DatevXmlLedgerGenerator.generate(
                entries = entries,
                date = "2023-01-12",
                generatorInfo = "Test Shop",
                generatingSystem = "POS-KMP",
            )

        val xml = String(result, Charsets.UTF_8)
        assertTrue(xml.contains("consolidatedAmount=\"1747.70\""), "should sum: 3223.90+673.60-2106.90-42.90=1747.70")
        val count = "<cashLedger>".toRegex().findAll(xml).count()
        assertTrue(count == 4, "should have 4 cashLedger elements, found $count")
    }

    @Test
    fun `generate produces correct XML structure for two entries`() {
        val entries =
            listOf(
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "100.00",
                    bookingText = "Entry 1",
                    headerId = 1,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "200.00",
                    bookingText = "Entry 2",
                    headerId = 1,
                ),
            )

        val result =
            DatevXmlLedgerGenerator.generate(
                entries = entries,
                date = "2023-01-12",
                generatorInfo = "Test Shop",
                generatingSystem = "POS-KMP",
            )

        val xml = String(result, Charsets.UTF_8)
        assertTrue(xml.contains("consolidatedAmount=\"300.00\""), "should sum amounts for multi-entry")
        val count = "<cashLedger>".toRegex().findAll(xml).count()
        assertTrue(count == 2, "should have 2 cashLedger elements")
    }
}
