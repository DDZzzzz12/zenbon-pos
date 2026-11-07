package org.example.project.features.salesinvoice

import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceMapper
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StandaloneInvoiceMapperTest {
    private fun line(
        name: String = "Raummiete",
        gross: String = "119.00",
        rate: String = "19.00",
        quantity: String = "1",
    ) = SalesInvoiceDto.StandaloneLineInputDto(
        itemName = name,
        quantity = quantity,
        grossUnitPrice = gross,
        vatRate = rate,
    )

    @Test
    fun `each line derives net by division and vat by subtraction so net plus vat is exactly gross`() {
        val content = StandaloneInvoiceMapper.toContent(listOf(line()))

        val l = content.lines.single()
        assertEquals(0, BigDecimal("119.0000").compareTo(l.lineGrossAmount))
        assertEquals(0, BigDecimal("100.00").compareTo(l.lineNetAmount), "119 / 1.19 = 100 at scale 2")
        assertEquals(0, BigDecimal("19.00").compareTo(l.vatAmount))
        assertEquals(0, l.lineNetAmount.add(l.vatAmount).compareTo(l.lineGrossAmount))
        assertEquals("S", l.vatCategoryCode)
        Unit
    }

    @Test
    fun `the breakdown is summed from the lines per rate`() {
        val content =
            StandaloneInvoiceMapper.toContent(
                listOf(
                    line(gross = "119.00", rate = "19.00"),
                    line(name = "Frühstück", gross = "107.00", rate = "7.00"),
                    line(name = "Zweite 19% Position", gross = "238.00", rate = "19.00"),
                ),
            )

        assertEquals(2, content.vatRows.size)
        val at19 = content.vatRows.first { it.vatRatePercent.compareTo(BigDecimal("19.00")) == 0 }
        assertEquals(0, BigDecimal("300.00").compareTo(at19.taxableAmount), "100 + 200 net at 19%")
        assertEquals(0, BigDecimal("57.00").compareTo(at19.vatAmount), "19 + 38 vat at 19%")
        Unit
    }

    @Test
    fun `totals are derived, never client-supplied`() {
        val content =
            StandaloneInvoiceMapper.toContent(
                listOf(line(gross = "119.00", rate = "19.00"), line(name = "B", gross = "107.00", rate = "7.00")),
            )

        assertEquals(0, BigDecimal("200.00").compareTo(content.lineNetTotal), "100 net @19 + 100 net @7")
        assertEquals(0, BigDecimal("26.00").compareTo(content.taxTotal), "19 + 7")
        assertEquals(0, BigDecimal("226.00").compareTo(content.taxInclusiveTotal), "119 + 107")
        Unit
    }

    @Test
    fun `a zero-rate line gets net equal to gross and no vat`() {
        val content = StandaloneInvoiceMapper.toContent(listOf(line(gross = "50.00", rate = "0.00")))

        val l = content.lines.single()
        assertEquals(0, BigDecimal("50.00").compareTo(l.lineNetAmount))
        assertEquals(0, BigDecimal.ZERO.compareTo(l.vatAmount))
        assertEquals("Z", l.vatCategoryCode)
        Unit
    }

    @Test
    fun `an explicit AE or K category is kept as given with a zero rate`() {
        val content =
            StandaloneInvoiceMapper.toContent(
                listOf(
                    SalesInvoiceDto.StandaloneLineInputDto(
                        itemName = "EU-Dienstleistung",
                        quantity = "1",
                        grossUnitPrice = "1000.00",
                        vatRate = "0.00",
                        vatCategoryCode = "AE",
                    ),
                ),
            )

        val l = content.lines.single()
        assertEquals("AE", l.vatCategoryCode)
        assertEquals(0, BigDecimal.ZERO.compareTo(l.vatAmount))
        Unit
    }

    @Test
    fun `an empty line list is refused`() {
        val error = assertFailsWith<PosException> { StandaloneInvoiceMapper.toContent(emptyList()) }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `a rate of exactly -100 is refused rather than dividing by zero`() {
        val error =
            assertFailsWith<PosException> {
                StandaloneInvoiceMapper.toContent(listOf(line(gross = "119.00", rate = "-100.00")))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }

    @Test
    fun `a rate below -100 is refused rather than inventing a negative divisor`() {
        val error =
            assertFailsWith<PosException> {
                StandaloneInvoiceMapper.toContent(listOf(line(gross = "119.00", rate = "-150.00")))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_NOT_ISSUABLE, error.code)
        Unit
    }
}
