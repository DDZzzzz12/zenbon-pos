package org.example.project.export.datevxml.ar

import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-file test for [DatevArLedgerGenerator].
 *
 * The generated XML is a contract with DATEV — byte-stable output is required,
 * so this test asserts against a full expected XML string rather than
 * spot-checking individual elements.
 */
class DatevArLedgerGeneratorTest {
    private val entry =
        DatevArExportDto.ArLedgerEntryDto(
            date = "2026-08-18",
            amount = "119.00",
            accountNo = "8400",
            bpAccountNo = "10001",
            buCode = null,
            tax = "19.00",
            invoiceId = "RG-2026-00001",
            buyerName = "Kanzlei Maas",
            buyerCity = "Berlin",
        )

    @Test
    fun `generate produces the exact expected AR ledger XML`() {
        val xml =
            DatevArLedgerGenerator.generate(
                entries = listOf(entry),
                invoiceId = "RG-2026-00001",
                invoiceDate = "2026-08-18",
                generatorInfo = "Test Shop",
                generatingSystem = "POS-KMP",
            )

        val actual = xml.toString(Charsets.UTF_8)

        val expected =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<LedgerImport xmlns=\"http://xml.datev.de/bedi/tps/ledger/v060\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://xml.datev.de/bedi/tps/ledger/v060 " +
                "Belegverwaltung_online_ledger_import_v060.xsd\" version=\"6.0\" " +
                "generator_info=\"Test Shop\" generating_system=\"POS-KMP\" " +
                "xml_data=\"Kopie nur zur Verbuchung berechtigt nicht zum Vorsteuerabzug\">\n" +
                "  <consolidate consolidatedAmount=\"119.00\" consolidatedDate=\"2026-08-18\" " +
                "consolidatedInvoiceId=\"RG-2026-00001\" consolidatedCurrencyCode=\"EUR\">\n" +
                "    <accountsReceivableLedger>\n" +
                "      <date>2026-08-18</date>\n" +
                "      <amount>119.00</amount>\n" +
                "      <accountNo>8400</accountNo>\n" +
                "      <tax>19.00</tax>\n" +
                "      <currencyCode>EUR</currencyCode>\n" +
                "      <invoiceId>RG-2026-00001</invoiceId>\n" +
                "      <bpAccountNo>10001</bpAccountNo>\n" +
                "      <customerName>Kanzlei Maas</customerName>\n" +
                "      <customerCity>Berlin</customerCity>\n" +
                "    </accountsReceivableLedger>\n" +
                "  </consolidate>\n" +
                "</LedgerImport>\n"

        assertEquals(expected, actual)
    }

    @Test
    fun `generate emits exactly one accountsReceivableLedger element and never accountsPayableLedger`() {
        val xml =
            DatevArLedgerGenerator.generate(
                entries = listOf(entry),
                invoiceId = "RG-2026-00001",
                invoiceDate = "2026-08-18",
                generatorInfo = "Test Shop",
                generatingSystem = "POS-KMP",
            )
        val actual = xml.toString(Charsets.UTF_8)

        assertEquals(1, Regex("<accountsReceivableLedger>").findAll(actual).count())
        assertTrue(!actual.contains("accountsPayableLedger"), "AR ledger must never emit an AP element")
    }
}
