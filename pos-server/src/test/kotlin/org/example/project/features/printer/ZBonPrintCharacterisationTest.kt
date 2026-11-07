package org.example.project.features.printer

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.printer.data.repository.PrintJobHistoryRepositoryImpl
import org.example.project.features.printer.data.repository.PrinterConfigurationRepositoryImpl
import org.example.project.features.printer.data.service.PrinterServiceImpl
import org.example.project.features.printer.domain.models.PrinterSetup
import org.example.project.features.printer.domain.service.ReceiptPdfRenderer
import org.example.project.features.setup.domain.service.ShopInfoService
import org.example.project.infrastructure.errors.PosException
import org.example.project.printer.core.data.service.NetworkPrintServiceImpl
import org.example.project.printer.core.data.service.PrintFormatterServiceImpl
import org.example.project.printer.core.data.service.UsbPrintServiceImpl
import org.example.project.printer.core.domain.factory.PrinterServiceFactory
import org.example.project.printer.core.formatter.BillFormatter
import org.example.project.printer.core.formatter.OrderFormatter
import org.example.project.printer.core.formatter.ReferencePrintFormatter
import org.example.project.printer.core.formatter.ZBonFormatter
import org.example.project.printer.ipp.data.service.IppPrinterServiceImpl
import org.example.project.testutils.Snapshots
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Z-Bon closing print path end to end, from [ZBonDto.ZBonResponseDto] to the bytes a
 * network printer receives: the real [PrinterServiceImpl], the real [ZBonFormatter], the real
 * [NetworkPrintServiceImpl], and a loopback socket standing in for the printer. Only the
 * collaborators `printZBon` never touches are stubs.
 *
 * Written on the code before Z-Bon formatting left the printer module (B2-2 Task A6), so the
 * snapshots `zbon-print-<n>.bin.hex` are what the closing print sent then; the refactor must
 * leave them byte-identical, together with the log lines and both failure paths.
 */
@DisplayName("Z-Bon print path (characterisation)")
class ZBonPrintCharacterisationTest {
    private val logCapture = ListAppender<ILoggingEvent>()
    private val serviceLogger get() = LoggerFactory.getLogger(PrinterServiceImpl::class.java) as Logger

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        logCapture.start()
        serviceLogger.addAppender(logCapture)
    }

    @AfterEach
    fun tearDown() {
        serviceLogger.detachAppender(logCapture)
        logCapture.stop()
        TestDatabase.teardown()
    }

    @Test
    fun `Z-Bon print sends the same ESC-POS bytes to the printer`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { printer ->
                val identifier = "127.0.0.1:${printer.localPort}"
                configureNetworkPrinter(printer.localPort)
                listOf(1 to zBonWithoutKassenbuch(), 2 to zBonWithKassenbuch()).forEach { (n, zBon) ->
                    logCapture.list.clear()
                    val received = CompletableFuture.supplyAsync { printer.accept().use { it.getInputStream().readBytes() } }

                    val result = dbQuery { printZBon(zBon) }

                    assertTrue(result.success, result.message)
                    assertEquals("Print job sent successfully to network printer: $identifier", result.message)
                    assertEquals(null, result.printJobId)
                    Snapshots.assertMatches("zbon-print-$n.bin.hex", received.get(10, TimeUnit.SECONDS).toHexLines())
                    assertEquals(
                        listOf(
                            "PrinterServiceImpl.printZBon: printing Z-Bon, printerId=null",
                            "PrinterServiceImpl.printZBon: successfully printed Z-Bon",
                        ),
                        logLines(),
                    )
                }
            }
        }

    @Test
    fun `Z-Bon print with no printer configured throws the same PosException`() =
        runBlocking {
            var formatted = 0
            val thrown =
                assertFailsWith<PosException> {
                    dbQuery {
                        printZBon(zBonWithoutKassenbuch()) {
                            formatted++
                            ByteArray(0)
                        }
                    }
                }
            assertEquals(PosErrorCode.PRINTER_NOT_FOUND, thrown.code)
            assertEquals(HttpStatusCode.NotFound, thrown.httpStatus)
            assertEquals("No printer configured. Please set up a printer first.", thrown.message)
            assertEquals(0, formatted, "nothing is formatted before a printer is found")
            assertEquals(listOf("PrinterServiceImpl.printZBon: printing Z-Bon, printerId=null"), logLines())
        }

    @Test
    fun `Z-Bon formatter failure returns success=false with the formatter's message`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { printer ->
                configureNetworkPrinter(printer.localPort)

                val result = dbQuery { printZBon(zBonWithKassenbuch()) { error("formatter exploded") } }

                assertFalse(result.success)
                assertEquals("formatter exploded", result.message)
                assertEquals(null, result.printJobId)
                assertEquals(
                    listOf(
                        "PrinterServiceImpl.printZBon: printing Z-Bon, printerId=null",
                        "PrinterServiceImpl.printZBon: failed to print Z-Bon, error=formatter exploded",
                    ),
                    logLines(),
                )
            }
        }

    // ---- the one place that knows how the service is asked to print a Z-Bon ----

    /**
     * Prints [zBon] through a real [PrinterServiceImpl], with [formatZBon] as the Z-Bon formatter
     * (the real [ZBonFormatter] unless a test replaces it) — the way ZBonRoutes asks for it.
     */
    private suspend fun printZBon(
        zBon: ZBonDto.ZBonResponseDto,
        formatZBon: suspend (ZBonDto.ZBonResponseDto) -> ByteArray = { ZBonFormatter().format(it, Language.GERMAN) },
    ): PrinterDto.PrintResponseDto =
        printerService(PrintFormatterServiceImpl(BillFormatter(), OrderFormatter(), ReferencePrintFormatter()))
            .printZBon({ formatZBon(zBon) })

    private fun printerService(formatter: PrintFormatterServiceImpl) =
        PrinterServiceImpl(
            printerConfigRepository = PrinterConfigurationRepositoryImpl(),
            printJobHistoryRepository = PrintJobHistoryRepositoryImpl(),
            printFormatterService = formatter,
            printerServiceFactory = PrinterServiceFactory(UsbPrintServiceImpl(), NetworkPrintServiceImpl(), IppPrinterServiceImpl()),
            shopInfoService = unused<ShopInfoService>(),
            ippPrinterService = IppPrinterServiceImpl(),
            pdfGenerationService = unused<ReceiptPdfRenderer>(),
        )

    // ---- fixtures ----

    private suspend fun configureNetworkPrinter(port: Int) =
        dbQuery {
            PrinterSetup.new {
                printerType = PrinterType.NETWORK
                printerName = "Bon"
                printerIp = "127.0.0.1"
                printerPort = port
                defaultPrinter = true
            }
        }

    private fun logLines() = logCapture.list.map { it.formattedMessage }

    private fun zBonWithoutKassenbuch() =
        ZBonDto.ZBonResponseDto(
            id = 7,
            zbonNummer = 42,
            shopInfoId = 1,
            shopName = "Testladen",
            shopAddress = "Hauptstraße 1",
            shopCity = "München",
            shopZipCode = "80331",
            shopCountry = "DE",
            shopTaxNumber = "143/123/45678",
            employeeNumber = "M001",
            startTime = "2026-09-30T08:00:00",
            endTime = "2026-09-30T23:00:00",
            cancelledAmount = "4.50",
            refundAmount = "170.00",
            stornoBillCount = 8,
            rebookStornoCount = 7,
            rebookStornoAmount = "158.50",
            totalDiscounts = "1.20",
            totalPromotions = "2.00",
            promotionBreakdown = listOf(ZBonDto.PromotionBreakdownDto(1, "Happy Hour", "PERCENTAGE", 2, "2.00")),
            adminTip = "4.00",
            totalRevenue = "298.10",
            totalReceivableCleared = "12.00",
            openingBalance = "100.00",
            expenses = "15.00",
            closingBalance = "279.50",
            createdAt = "2026-09-30T23:00:00",
            vatGroups =
                listOf(
                    ZBonDto.VatGroupResponseDto("19.00", "A", "168.07", "31.93", "200.00"),
                    ZBonDto.VatGroupResponseDto("7.00", "B", "91.68", "6.42", "98.10"),
                ),
            paymentSummary =
                listOf(
                    ZBonDto.PaymentsSummaryResponseDto("Bar", "EUR", "179.50"),
                    ZBonDto.PaymentsSummaryResponseDto("Karte", "EUR", "118.60"),
                ),
            employeeSummaries =
                listOf(
                    ZBonDto.EmployeeZBonSummaryResponseDto(employeeNumber = "M001", role = "MANAGER", amount = "4.00", totalRevenue = "179.10"),
                    ZBonDto.EmployeeZBonSummaryResponseDto(employeeNumber = "A001", role = "ADMIN", amount = "6.00", totalRevenue = "119.00"),
                ),
            vouchers = listOf(VoucherDto.VoucherSummeryDto(id = 1, voucherNumber = "G1", originalValue = "30.00", employeeNumber = "M001")),
            totalEmployeeTips = "6.00",
            trainingRevenue = "7.40",
            trainingBillCount = 5,
        )

    private fun zBonWithKassenbuch() =
        zBonWithoutKassenbuch().copy(
            kassenbuchSummaryDto =
                listOf(
                    kassenbuch(1, "2026-09-0001", KassenbuchEntryType.CASH_SALE, "ZBon Nr. 42", "179.50"),
                    kassenbuch(2, "2026-09-0002", KassenbuchEntryType.CASH_DEPOSIT, "Wechselgeld", "50.00"),
                    kassenbuch(3, "2026-09-0003", KassenbuchEntryType.CASH_EXPENSE, "Blumen", "15.00"),
                    kassenbuch(4, "2026-09-0004", KassenbuchEntryType.CASH_TRANSFER_OUT, "Bank", "35.00"),
                    kassenbuch(5, "2026-09-0005", KassenbuchEntryType.CASH_SHORTAGE, "Fehlbetrag", "0.50"),
                ),
        )

    private fun kassenbuch(
        headerId: Long,
        belegNr: String,
        type: KassenbuchEntryType,
        text: String,
        amount: String,
    ) = KassenbuchDto.KassenbuchGroupedSummaryDto(
        headerId = headerId,
        cashBookId = 1,
        belegNr = belegNr,
        entryType = type,
        belegtext = text,
        belegDatum = "30.09.2026",
        waehrung = "EUR",
        totalAmount = amount,
        lineItems = emptyList(),
        currentBalance = amount,
        employeeNumber = "M001",
        createdAt = "2026-09-30T22:00:00",
    )

    private fun ByteArray.toHexLines(): List<String> = toList().chunked(HEX_BYTES_PER_LINE).map { line -> line.joinToString("") { "%02x".format(it) } }

    private companion object {
        const val HEX_BYTES_PER_LINE = 32

        /** A stand-in for a collaborator `printZBon` never calls; any call fails the test. */
        inline fun <reified T : Any> unused(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                error("printZBon must not call ${T::class.simpleName}.${method.name}")
            } as T
    }
}
