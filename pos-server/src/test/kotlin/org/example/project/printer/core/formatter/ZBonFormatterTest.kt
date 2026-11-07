package org.example.project.printer.core.formatter

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSourceType
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZBonFormatterTest {
    private fun zBon(
        rebookCount: Int,
        rebookAmount: String,
    ) = ZBonDto.ZBonResponseDto(
        id = 1,
        zbonNummer = 1,
        shopInfoId = 1,
        shopName = "Testladen",
        employeeNumber = "A001",
        startTime = "2026-09-30T00:00:00",
        endTime = "2026-09-30T23:00:00",
        cancelledAmount = "0.00",
        refundAmount = "170.00",
        stornoBillCount = 8,
        rebookStornoCount = rebookCount,
        rebookStornoAmount = rebookAmount,
        totalRevenue = "233.50",
        openingBalance = "0.00",
        expenses = "0.00",
        closingBalance = "79.50",
        createdAt = "2026-09-30T23:00:00",
    )

    private fun print(zBon: ZBonDto.ZBonResponseDto) = runBlocking { ZBonFormatter().format(zBon, Language.GERMAN) }.printedLines()

    /** F63: the VAT rate printed as "19.00%" next to German amounts; it now reads "19 %". */
    @Test
    fun `the VAT table prints its rate in German form`() {
        val lines =
            print(
                zBon(rebookCount = 0, rebookAmount = "0.00").copy(
                    vatGroups = listOf(ZBonDto.VatGroupResponseDto("19.00", "A", "16.81", "3.19", "20.00")),
                ),
            )

        val row = assertNotNull(lines.firstOrNull { "16,81" in it }, lines.joinToString("\n"))
        assertEquals(listOf("19", "%", "3,19", "16,81", "20,00"), row.trim().split(Regex(" +")), "'$row'")
    }

    @Test
    fun `the Zahlartwechsel line prints its full label, its count and its amount on one line`() {
        val lines = print(zBon(rebookCount = 7, rebookAmount = "158.50"))

        val line = assertNotNull(lines.firstOrNull { "Zahlartwechsel" in it }, "no Zahlartwechsel line in:\n${lines.joinToString("\n")}")
        assertTrue("davon Zahlartwechsel (7):" in line, "label and count must be readable: '$line'")
        assertTrue(line.trimEnd().endsWith("-158,50 EUR"), "amount must close the line: '$line'")
        assertTrue(line.length <= BaseDocumentFormatter.MAX_CHARS_SMALL, "the line must fit the paper: '$line' (${line.length})")
    }

    @Test
    fun `a large count and amount still fit without cutting either`() {
        val lines = print(zBon(rebookCount = 1234, rebookAmount = "123456.78"))

        val line = assertNotNull(lines.firstOrNull { "Zahlartwechsel" in it })
        assertTrue("(1234):" in line, "'$line'")
        assertTrue(line.trimEnd().endsWith("-123.456,78 EUR"), "'$line'")
        assertTrue(line.length <= BaseDocumentFormatter.MAX_CHARS_SMALL, "'$line' (${line.length})")
    }

    @Test
    fun `emu16 NOTE-9 - a zero Stornierungen or Rabatte prints 0_00 without a minus, a non-zero one keeps it`() {
        val zero = print(zBon(rebookCount = 0, rebookAmount = "0.00"))
        listOf("Stornierungen:", "Rabatte:").forEach { label ->
            val line = assertNotNull(zero.firstOrNull { label in it }, "no $label line")
            assertTrue(line.trimEnd().endsWith(" 0,00 EUR") && "-0,00" !in line, "'$line'")
        }

        val nonZero = print(zBon(rebookCount = 0, rebookAmount = "0.00").copy(cancelledAmount = "4.50", totalDiscounts = "1.20"))
        assertTrue(nonZero.first { "Stornierungen:" in it }.trimEnd().endsWith("-4,50 EUR"))
        assertTrue(nonZero.first { "Rabatte:" in it }.trimEnd().endsWith("-1,20 EUR"))
    }

    @Test
    fun `the refund line above it is unchanged`() {
        val lines = print(zBon(rebookCount = 7, rebookAmount = "158.50"))

        val refund = assertNotNull(lines.firstOrNull { "Rückerstattungen" in it })
        assertTrue("Rückerstattungen (8):" in refund && refund.trimEnd().endsWith("-170,00 EUR"), "'$refund'")
    }

    @Test
    fun `emu21 NOTE-bb - the Schulungsbelege line prints like the till's, and only when there are any`() {
        val lines = print(zBon(rebookCount = 0, rebookAmount = "0.00").copy(trainingBillCount = 5, trainingRevenue = "7.40"))

        val line = assertNotNull(lines.firstOrNull { "Schulungsbelege" in it }, "no training line in:\n${lines.joinToString("\n")}")
        assertTrue("Schulungsbelege (5, kein Umsatz):" in line, "'$line'")
        assertTrue(line.trimEnd().endsWith("7,40 EUR") && "-7,40" !in line, "'$line'")
        assertTrue(line.length <= BaseDocumentFormatter.MAX_CHARS_SMALL, "'$line' (${line.length})")
        assertFalse(print(zBon(rebookCount = 0, rebookAmount = "0.00")).any { "Schulungsbelege" in it })
    }

    private fun tipped() =
        zBon(rebookCount = 0, rebookAmount = "0.00").copy(
            adminTip = "4.00",
            totalRevenue = "298.10",
            totalEmployeeTips = "6.00",
            employeeSummaries =
                listOf(
                    ZBonDto.EmployeeZBonSummaryResponseDto(employeeNumber = "M001", role = "MANAGER", amount = "4.00", totalRevenue = "179.10"),
                    ZBonDto.EmployeeZBonSummaryResponseDto(
                        employeeNumber = "A001",
                        role = "ADMIN",
                        amount = "4.00",
                        totalRevenue = "46.00",
                        ownerTip = true,
                    ),
                    ZBonDto.EmployeeZBonSummaryResponseDto(employeeNumber = "K001", role = "CASHIER", amount = "2.00", totalRevenue = "69.00"),
                ),
        )

    /** The lines of the tip block, from its heading up to the blank line closing it. */
    private fun tipBlock(lines: List<String>): List<String> {
        val start = lines.indexOfFirst { "TRINKGELD PRO MITARBEITER" in it }
        assertTrue(start >= 0, "no tip block in:\n${lines.joinToString("\n")}")
        return lines.drop(start).takeWhile { it.isNotBlank() }
    }

    @Test
    fun `QA emu14 NOTE-5 - the tip block lists employee tips only and its total adds up`() {
        val block = tipBlock(print(tipped()))

        val listed = block.filter { it.trimEnd().endsWith(" EUR") && it.first().isLetterOrDigit() && "Trinkgeld" !in it }
        assertEquals(listOf("M001", "K001"), listed.map { it.substringBefore(' ') }, "employee tip rows: $block")
        val total = assertNotNull(block.firstOrNull { "Trinkgeld gesamt:" in it }, "$block")
        assertTrue("6,00 EUR" in total, "the total is the listed 4,00 + 2,00: '$total'")
    }

    @Test
    fun `the owner tip has its own line labelled as revenue, which fits the paper`() {
        val block = tipBlock(print(tipped()))

        val ownerAt = block.indexOfFirst { "Trinkgeld Betrieb (im Umsatz enthalten):" in it }
        assertTrue(ownerAt >= 0, "the full revenue label is printed uncut: $block")
        val amount = block[ownerAt + 1]
        assertTrue(amount.trimEnd().endsWith("4,00 EUR"), "the owner tip's amount follows its label: '$amount'")
        assertTrue(block.all { it.length <= BaseDocumentFormatter.MAX_CHARS_SMALL + 2 }, "every line fits the paper: $block")
        assertTrue(block.none { "Trinkgeld gesamt" in it && "10,00" in it }, "the owner tip is not added to the employee total")
        assertTrue(block.none { it.startsWith("A001") }, "the owner is not listed as an employee tip: $block")
    }

    @Test
    fun `admin17 NOTE-15 - the revenue block lists the owner tip so its rows add up to Gesamtumsatz`() {
        val lines = print(tipped())

        val start = lines.indexOfFirst { "UMSATZ PRO MITARBEITER" in it }
        val block = lines.drop(start).takeWhile { "TRINKGELD PRO MITARBEITER" !in it }
        val ownerTip = assertNotNull(block.firstOrNull { it.startsWith("Inhabertrinkgeld") }, "no owner-tip row: $block")
        assertTrue(ownerTip.trimEnd().endsWith("4,00 EUR"), "'$ownerTip'")
        assertTrue(ownerTip.length <= BaseDocumentFormatter.MAX_CHARS_SMALL, "fits the paper: '$ownerTip'")
        val listed =
            block
                .filter { !it.startsWith(" ") && it.trimEnd().endsWith(" EUR") }
                .sumOf { line -> line.amountBeforeEur() }
        assertEquals(0, listed.compareTo(tipped().totalRevenue.toBigDecimal()), "179.10 + 46.00 + 69.00 + 4.00 owner tip: $block")
    }

    @Test
    fun `the employee revenue block is unchanged and the Mitarbeiter-Nr is spelled right`() {
        val lines = print(tipped())

        assertTrue(lines.any { "UMSATZ PRO MITARBEITER" in it }, "the revenue block keeps its heading")
        assertTrue(lines.any { it.startsWith("A001 ") && it.trimEnd().endsWith("46,00 EUR") }, "the owner's revenue still listed")
        // The role is the employee's current role, not a closing fact: never printed (emu16 NOTE-4).
        assertTrue(lines.none { "(ADMIN)" in it || "(EMPLOYEE)" in it || "(MANAGER)" in it }, "no role label: $lines")
        assertTrue(lines.any { "Mitarbeiter-Nr.:" in it }, "header spelling")
        assertTrue(lines.none { "Mitabeiter" in it })
    }

    private fun kassenbuchIncomeEntry() =
        KassenbuchDto.KassenbuchGroupedSummaryDto(
            headerId = 1,
            cashBookId = 1,
            belegNr = "2026-10-0001",
            entryType = KassenbuchEntryType.CASH_SALE,
            belegtext = "ZBon Nr. 1",
            belegDatum = "01.10.2026",
            waehrung = "EUR",
            totalAmount = "93.50",
            lineItems = emptyList(),
            currentBalance = "93.50",
            employeeNumber = "M001",
            createdAt = "2026-09-30T23:00:00",
        )

    /**
     * QA emu15 NOTE-6: the ZBon's own aggregate kassenbuch entry printed as "B-Nr:2026-ZBon Nr. 1" —
     * a fixed 10-char column for "B-Nr:$belegNr" truncated the number ("2026-10-0001") after 5
     * digits and the belegtext ("ZBon Nr. 1") ran straight into it with no separator. The full Beleg
     * number must survive and stay visibly separate from the text that follows it.
     */
    @Test
    fun `the EINNAHMEN line keeps the full Beleg number, cleanly separated from its text`() {
        val lines = print(zBon(rebookCount = 0, rebookAmount = "0.00").copy(kassenbuchSummaryDto = listOf(kassenbuchIncomeEntry())))

        val line = assertNotNull(lines.firstOrNull { "B-Nr:" in it }, "no B-Nr line in:\n${lines.joinToString("\n")}")
        assertTrue("B-Nr:2026-10-0001" in line, "the full Beleg number must not be cut off: '$line'")
        assertTrue("2026-10-0001 ZBon Nr. 1" in line, "the number and text must be separated by whitespace: '$line'")
        assertTrue(line.trimEnd().endsWith("93,50 EUR"), "the amount still closes the line: '$line'")
        assertFalse("2026-ZBon" in line, "the old garbled run-on must be gone: '$line'")
    }

    /**
     * QA emu15 NOTE-6 mixed "60,00 EUR" with "60.00 EUR" on one paper because some totals went
     * through the JVM-locale `String.format`. F63 then made the whole paper German: every amount goes
     * through [org.example.project.printer.core.utils.printMoney], which is locale-independent by
     * construction. The default locale is forced to US here, so a regression back to
     * `String.format`/`NumberFormat` (which would print a dot under US) fails even on a German machine.
     */
    @Test
    fun `every EUR amount uses a German comma whatever the JVM default locale`() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val bon =
                tipped().copy(
                    kassenbuchSummaryDto = listOf(kassenbuchIncomeEntry()),
                    vouchers = listOf(VoucherDto.VoucherSummeryDto(id = 1, voucherNumber = "G1", originalValue = "30.00", employeeNumber = "M001")),
                    voucherRedeems =
                        listOf(
                            VoucherDto.RedeemVoucherResponse(
                                id = 1,
                                voucherNumber = "G1",
                                voucherSource = VoucherSourceType.INTERNAL,
                                redeemedAmount = "12.50",
                                remainingValue = "17.50",
                                redeemedAt = "2026-09-30T12:00:00",
                                billId = 1,
                            ),
                        ),
                    promotionBreakdown =
                        listOf(
                            ZBonDto.PromotionBreakdownDto(
                                promotionId = 1,
                                promotionName = "Stammgast 10",
                                discountType = "FIXED",
                                timesApplied = 1,
                                totalDeducted = "3.00",
                            ),
                        ),
                    totalPromotions = "3.00",
                )
            val lines = print(bon)

            val eurLines = lines.filter { it.trimEnd().endsWith("EUR") }
            assertTrue(eurLines.isNotEmpty(), "sanity: there are EUR lines to check")
            assertTrue(
                eurLines.none { Regex("""\d\.\d{2}(?![\d.])""").containsMatchIn(it) },
                "no EUR line may use a dot decimal separator under a US default locale:\n${eurLines.joinToString("\n")}",
            )
            assertTrue(
                eurLines.all { Regex("""-?[\d.]*\d,\d{2} EUR$""").containsMatchIn(it.trimEnd()) },
                "every EUR line must end in a German comma amount:\n${eurLines.joinToString("\n")}",
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    /** The German printed amount closing the line (`1.234,50 EUR`) back as a number. */
    private fun String.amountBeforeEur() =
        trimEnd()
            .removeSuffix(" EUR")
            .substringAfterLast(' ')
            .replace(".", "")
            .replace(',', '.')
            .toBigDecimal()
}
