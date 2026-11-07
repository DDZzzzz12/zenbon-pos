package org.example.project.pdf.generator

import org.example.project.core.domain.dto.report.ZBonDto
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA emu14 NOTE-5, PDF leg: the tip block must agree with the printed Z-Bon — employee tips only
 * under a tip heading, a total that sums them, and the owner tip (revenue) on its own line.
 */
class GenerateZBonReportTipBlockTest {
    private fun extractText(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    private fun summary(
        number: String,
        role: String,
        tip: String,
        owner: Boolean = false,
    ) = ZBonDto.EmployeeZBonSummaryResponseDto(
        employeeNumber = number,
        role = role,
        amount = tip,
        totalRevenue = "10.00",
        ownerTip = owner,
    )

    private val zBon =
        ZBonDto.ZBonResponseDto(
            id = 1,
            zbonNummer = 1,
            shopInfoId = 1,
            shopName = "Testladen",
            employeeNumber = "A001",
            startTime = "2026-10-01T00:00:00",
            endTime = "2026-10-01T23:00:00",
            cancelledAmount = "0.00",
            adminTip = "4.00",
            totalRevenue = "298.10",
            openingBalance = "0.00",
            expenses = "0.00",
            closingBalance = "0.00",
            createdAt = "2026-10-01T23:00:00",
            employeeSummaries =
                listOf(
                    summary("M001", "MANAGER", "4.50"),
                    summary("A001", "ADMIN", "4.00", owner = true),
                    summary("K001", "CASHIER", "2.00"),
                ),
            totalEmployeeTips = "6.50",
        )

    @Test
    fun `the PDF tip block lists employee tips only and shows the owner tip apart`() {
        val text = extractText(generateZBonReportPdfBytes(zBon))
        assertTrue("TRINKGELD PRO MITARBEITER" in text, "the tip block is headed as tips:\n$text")
        // The extractor emits section headings first and table rows after, in document order: the
        // tip rows follow the last employee-revenue row and precede the VAT table.
        val block = text.substringAfter("K001 10,00 EUR", missingDelimiterValue = "").substringBefore("MWST%")

        assertTrue(block.isNotBlank(), "no tip rows in:\n$text")
        assertTrue("M001" in block && "4,50 EUR" in block && "K001" in block && "2,00 EUR" in block, block)
        assertFalse("A001" in block, "the owner is not listed as an employee tip:\n$block")
        assertTrue("Trinkgeld gesamt:" in block && "6,50 EUR" in block, block)
        assertTrue("Trinkgeld Betrieb (im Umsatz enthalten):" in block && "4,00 EUR" in block, block)
        assertFalse("Mitabeiter" in text)
    }

    @Test
    fun `admin17 NOTE-15 - the PDF revenue block lists the owner tip after the employee rows`() {
        val text = extractText(generateZBonReportPdfBytes(zBon))
        val afterRows = text.substringAfter("K001 10,00 EUR", missingDelimiterValue = "")
        val row = afterRows.trimStart().lineSequence().first()
        assertTrue(row.startsWith("Inhabertrinkgeld") && row.endsWith("4,00 EUR"), "owner-tip row follows the employee rows: '$row'\n$text")
    }

    @Test
    fun `emu21 NOTE-bb - the PDF Z-Bon names the Schulungsbelege like the till, and only when there are any`() {
        val text = extractText(generateZBonReportPdfBytes(zBon.copy(trainingBillCount = 5, trainingRevenue = "7.40")))
        assertTrue("Schulungsbelege (5, kein Umsatz):" in text && "7,40 EUR" in text, text)
        assertFalse("Schulungsbelege" in extractText(generateZBonReportPdfBytes(zBon)))
    }
}
