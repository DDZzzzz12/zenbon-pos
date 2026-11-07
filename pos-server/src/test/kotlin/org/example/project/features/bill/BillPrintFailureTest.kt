package org.example.project.features.bill

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.BillServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.testApp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A printer that reports failure must surface as 502 `PRINTER_UNAVAILABLE` from the REAL
 * `BillServiceImpl.printBill` (the handler of `POST /api/v1/bills/{id}/print`) instead of a silent
 * 200. A successful print stays silent. Only the printer hardware is replaced.
 */
@DisplayName("Bill print failure reaches the caller")
class BillPrintFailureTest {
    private val fixture = RebookFixture("bill-print-failure-device")

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    /** Prints nothing; answers with the configured outcome. */
    private class StubPrinter(
        private val success: Boolean,
    ) : PrinterService {
        var calls = 0

        override suspend fun printBill(
            bill: BillDto.BillResponseDto,
            employee: Employee,
            printerId: Long?,
            bewirtungsbeleg: Boolean,
        ): PrinterDto.PrintResponseDto {
            calls++
            return PrinterDto.PrintResponseDto(
                success = success,
                message = if (success) "recorded" else "paper out",
                timestamp = "now",
            )
        }

        override suspend fun printOrder(
            order: OrderDto.OrderResponseDto,
            printerId: Long?,
            language: Language?,
            takeAway: Boolean,
        ) = error("not printed in this test")

        override suspend fun printZBon(
            format: suspend () -> ByteArray,
            printerId: Long?,
        ) = error("not printed in this test")

        override suspend fun printReferencePrint(
            shopHeader: ShopHeaderData,
            tableNumber: String,
            employeeNumber: String,
            dateTime: String,
            items: List<OrderItemDto.OrderItemResponseDto>,
            printerId: Long?,
        ) = error("not printed in this test")

        override suspend fun testPrint(
            printerLocationId: Long,
            printedBy: String,
        ) = error("not printed in this test")
    }

    /** The shop's config with printing switched on — everything else is the real manager. */
    private object PrintingEnabled : IShopConfigurationManager by ShopConfigurationManager {
        override suspend fun isPrinterSettingsEnabled(): Boolean = true
    }

    private fun billServiceWith(printer: PrinterService): BillServiceImpl {
        val koin = GlobalContext.get()
        return BillServiceImpl(
            repository = koin.get(),
            tableSessionRepository = koin.get(),
            printerService = printer,
            saleSigning = TseSaleSigningGateway(koin.get(), koin.get(), PrintingEnabled, koin.get()),
            terminalRepository = koin.get(),
            configManager = PrintingEnabled,
            tableRepository = koin.get(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )
    }

    @Test
    fun `a printer that reports failure makes the print answer 502 PRINTER_UNAVAILABLE`() =
        testApp { _ ->
            val printer = StubPrinter(success = false)
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

                val error =
                    assertFailsWith<PosException> {
                        runBlocking {
                            billServiceWith(printer).printBill(sale.billId.toString(), sale.closerId, bewirtungsbeleg = false)
                        }
                    }
                assertEquals(PosErrorCode.PRINTER_UNAVAILABLE, error.code)
                assertEquals(HttpStatusCode.BadGateway, error.httpStatus)
                assertTrue(error.message.orEmpty().contains("paper out"), "the printer's reason reaches the caller")
                assertEquals(1, printer.calls)
            }
        }

    @Test
    fun `a successful print is still silent`() =
        testApp { _ ->
            val printer = StubPrinter(success = true)
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

                runBlocking {
                    billServiceWith(printer).printBill(sale.billId.toString(), sale.closerId, bewirtungsbeleg = false)
                }
                assertEquals(1, printer.calls)
            }
        }
}
