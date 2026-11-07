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
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A Bewirtungsbeleg prints only for a standing sale (CLAUDE.md rule 8, server half): the REAL
 * `BillServiceImpl.printBill` (the handler of `POST /api/v1/bills/{id}/print`) refuses a Storno bill
 * and the cancelled original with 409 `BILL_NOT_A_STANDING_SALE`. Only the printer hardware is
 * replaced: a recording [PrinterService] keeps what the print path hands over.
 */
@DisplayName("Bewirtungsbeleg only for a standing sale")
class BillBewirtungsbelegStandingSaleTest {
    private val fixture = RebookFixture("bewirtung-standing-sale-device")

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    /** Records every bill the print path sends to the printer; nothing else is printed here. */
    private class RecordingPrinter : PrinterService {
        val printed = mutableListOf<BillDto.BillResponseDto>()
        val bewirtungsbelege = mutableListOf<Long>()

        private fun ok() = PrinterDto.PrintResponseDto(success = true, message = "recorded", timestamp = "now")

        override suspend fun printBill(
            bill: BillDto.BillResponseDto,
            employee: Employee,
            printerId: Long?,
            bewirtungsbeleg: Boolean,
        ): PrinterDto.PrintResponseDto {
            printed += bill
            if (bewirtungsbeleg) bewirtungsbelege += bill.billId
            return ok()
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

    private fun assertBewirtungsbelegRefused(
        printer: RecordingPrinter,
        billId: Long,
        closerId: String,
        what: String,
    ) {
        val error =
            assertFailsWith<PosException>(what) {
                runBlocking { billServiceWith(printer).printBill(billId.toString(), closerId, bewirtungsbeleg = true) }
            }
        assertEquals(PosErrorCode.BILL_NOT_A_STANDING_SALE, error.code, what)
        assertEquals(HttpStatusCode.Conflict, error.httpStatus, what)
    }

    @Test
    fun `a Bewirtungsbeleg of a standing sale prints`() =
        testApp { _ ->
            val printer = RecordingPrinter()
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

                runBlocking { billServiceWith(printer).printBill(sale.billId.toString(), sale.closerId, bewirtungsbeleg = true) }
                assertEquals(listOf(sale.billId), printer.bewirtungsbelege, "the sale's Bewirtungsbeleg prints")
            }
        }

    @Test
    fun `a Bewirtungsbeleg of a Storno bill or of the cancelled original is refused`() =
        testApp { _ ->
            val printer = RecordingPrinter()
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
                fixture.storno(sale)
                val stornoId =
                    transaction {
                        Bill
                            .find { BillTable.deletedBillId eq sale.billId }
                            .single()
                            .id.value
                    }

                assertBewirtungsbelegRefused(printer, stornoId, sale.closerId, "Storno bill")
                assertBewirtungsbelegRefused(printer, sale.billId, sale.closerId, "cancelled original")
                assertTrue(printer.printed.isEmpty())
            }
        }
}
