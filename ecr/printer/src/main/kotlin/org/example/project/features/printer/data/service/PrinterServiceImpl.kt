/**
 * Main printer service orchestrating print operations.
 *
 * Coordinates between printer configuration, formatter service, factory,
 * and audit logging to execute complete print workflows. Handles high-level
 * print operations for bills, orders, and Z-Bons.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on configuration /
 * not-found errors; the global StatusPages handler converts to application/problem+json.
 * Hardware-I/O failures populate `success=false` on the response DTO so the caller can
 * decide whether to surface the message or retry. Migrated from Result<T, Error> in
 * Stage B Batch 5.
 *
 * @since 1.0.0
 */
package org.example.project.features.printer.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.IppDocumentFormat
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.printer.domain.models.PrinterLocation
import org.example.project.features.printer.domain.repository.PrintJobHistoryRepository
import org.example.project.features.printer.domain.repository.PrinterConfigurationRepository
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.printer.domain.service.ReceiptPdfRenderer
import org.example.project.features.setup.domain.service.ShopInfoService
import org.example.project.infrastructure.errors.PosException
import org.example.project.printer.core.domain.factory.PrinterServiceFactory
import org.example.project.printer.core.domain.service.PrintFormatterService
import org.example.project.printer.core.emitter.EscPosEmitter
import org.example.project.printer.core.enums.Alignment
import org.example.project.printer.core.enums.CutType
import org.example.project.printer.core.enums.FontSize
import org.example.project.printer.ipp.domain.service.IppPrinterService
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Printer service implementation.
 *
 * Main orchestrator for print operations handling the complete workflow:
 * configuration -> formatting -> printing -> audit logging.
 */
@OptIn(ExperimentalTime::class)
class PrinterServiceImpl(
    private val printerConfigRepository: PrinterConfigurationRepository,
    private val printJobHistoryRepository: PrintJobHistoryRepository,
    private val printFormatterService: PrintFormatterService,
    private val printerServiceFactory: PrinterServiceFactory,
    private val shopInfoService: ShopInfoService,
    private val ippPrinterService: IppPrinterService,
    private val pdfGenerationService: ReceiptPdfRenderer,
) : PrinterService {
    private val logger = LoggerFactory.getLogger(PrinterServiceImpl::class.java)

    /**
     * Build ShopHeaderData from the default shop info record.
     */
    private suspend fun getShopHeader(): ShopHeaderData {
        val shopInfo = shopInfoService.getShopInfoById(1L) ?: error("No default shop found")
        return ShopHeaderData(
            shopName = shopInfo.shopName,
            shopAddress = shopInfo.shopAddress,
            shopCity = shopInfo.shopCity,
            zipCode = shopInfo.zipCode?.toString(),
            shopPhone = shopInfo.shopPhone,
            shopTaxNumber = shopInfo.shopTaxNumber,
        )
    }

    /**
     * Print a bill to a thermal printer.
     */
    override suspend fun printBill(
        bill: BillDto.BillResponseDto,
        employee: Employee,
        printerId: Long?,
        bewirtungsbeleg: Boolean,
    ): PrinterDto.PrintResponseDto =
        dbQuery {
            logger.info(
                "PrinterServiceImpl.printBill: printing bill billId={}, printerId={}, employee={}",
                bill.billId,
                printerId,
                employee.employeeNumber,
            )
            // Step 1: Get printer configuration
            val printer =
                (
                    if (printerId != null) {
                        printerConfigRepository.getPrinterById(printerId)
                    } else {
                        printerConfigRepository.getDefaultPrinter()
                    }
                ) ?: throw printerNotConfigured()

            val location =
                printer.printerLocation
                    ?: throw printerLocationNotConfigured()

            if (!location.printBill) throw billPrintingDisabled()

            // Step 2: Format data and execute print based on printer type + document format
            val shopHeader = getShopHeader()
            val printResult: kotlin.Result<String> =
                runCatching {
                    if (printer.printerType == PrinterType.IPP && printer.ippDocumentFormat == IppDocumentFormat.PDF) {
                        val pdfData = pdfGenerationService.generateBillReceiptPdf(bill, shopHeader)
                        val uri = printer.printerIp ?: throw ippUriNotConfigured()
                        ippPrinterService.printViaIpp(pdfData, uri, IppDocumentFormat.PDF)
                    } else {
                        val formattedData = printFormatterService.formatBill(bill, shopHeader, Language.GERMAN, bewirtungsbeleg)
                        if (printer.printerType == PrinterType.IPP) {
                            val uri = printer.printerIp ?: throw ippUriNotConfigured()
                            ippPrinterService.printViaIpp(formattedData, uri, IppDocumentFormat.ESC_POS)
                        } else {
                            val printService = printerServiceFactory.getPrintService(printer.printerType)
                            val printerIdentifier =
                                buildPrinterIdentifier(printer)
                                    ?: throw printerIdentifierNotConfigured()
                            printService.print(formattedData, printerIdentifier)
                        }
                    }
                }

            // Step 3: Determine status and error message
            val status =
                if (printResult.isSuccess) {
                    PrinterDto.PrintJobStatus.SUCCESS
                } else {
                    PrinterDto.PrintJobStatus.FAILED
                }
            val errorMessage = printResult.exceptionOrNull()?.message

            // Step 4: Log to audit trail
            val jobId =
                try {
                    printJobHistoryRepository.createPrintJob(
                        printType = PrinterDto.PrintType.BILL,
                        printerType = printer.printerType,
                        documentId = bill.billId.toString(),
                        status = status,
                        errorMessage = errorMessage,
                        printedBy = employee.employeeNumber,
                        printerLocationId = location.id.value,
                    )
                } catch (_: Exception) {
                    null
                }

            // Step 5: Build and return response
            if (printResult.isSuccess) {
                logger.info(
                    "PrinterServiceImpl.printBill: successfully printed bill billId={}, jobId={}",
                    bill.billId,
                    jobId,
                )
                PrinterDto.PrintResponseDto(
                    success = true,
                    message = printResult.getOrNull() ?: "OK",
                    printJobId = jobId,
                    timestamp = Clock.System.now().toString(),
                )
            } else {
                logger.warn(
                    "PrinterServiceImpl.printBill: failed to print bill billId={}, error={}",
                    bill.billId,
                    errorMessage,
                )
                PrinterDto.PrintResponseDto(
                    success = false,
                    message = errorMessage ?: "Unknown error occurred",
                    printJobId = jobId,
                    timestamp = Clock.System.now().toString(),
                )
            }
        }

    /**
     * Print an order to a kitchen/bar printer.
     *
     * The DB transaction is held only for the printer-config lookup. Network/IPP/USB I/O
     * runs outside `dbQuery` so a non-responsive printer can't pin a Hikari connection
     * (Exposed entities lazy-load, so all needed fields are extracted into a snapshot first).
     */
    override suspend fun printOrder(
        order: OrderDto.OrderResponseDto,
        printerId: Long?,
        language: Language?,
        takeAway: Boolean,
    ): PrinterDto.PrintResponseDto {
        logger.info("PrinterServiceImpl.printOrder: printing order orderId={}, printerId={}", order.orderId, printerId)

        // Step 1: Snapshot printer config inside dbQuery (no I/O here)
        val snapshot =
            dbQuery {
                val printer =
                    if (printerId != null) {
                        printerConfigRepository.getPrinterById(printerId)
                    } else {
                        printerConfigRepository.getDefaultPrinter()
                    } ?: return@dbQuery null

                PrinterSnapshot(
                    printerType = printer.printerType,
                    printerIp = printer.printerIp,
                    printerPort = printer.printerPort,
                    printerName = printer.printerName,
                    ippDocumentFormat = printer.ippDocumentFormat,
                    language = printer.printerLocation?.language,
                    beepMode = printer.printerLocation?.beepMode ?: BeepMode.DISABLED,
                    printerLocationId = printer.printerLocation?.id?.value,
                )
            } ?: throw printerNotConfigured()

        val effectiveLanguage =
            language ?: snapshot.language
                ?: throw printerLocationLanguageNotConfigured()

        // Step 2: Format and print outside dbQuery — TCP/IPP I/O cannot pin a DB connection
        val printResult: Result<String> =
            runCatching {
                if (snapshot.printerType == PrinterType.IPP && snapshot.ippDocumentFormat == IppDocumentFormat.PDF) {
                    val pdfData = pdfGenerationService.generateOrderReceiptPdf(order, takeAway)
                    val uri = snapshot.printerIp ?: throw ippUriNotConfigured()
                    ippPrinterService.printViaIpp(pdfData, uri, IppDocumentFormat.PDF)
                } else {
                    val formattedData = printFormatterService.formatOrder(order, effectiveLanguage, snapshot.beepMode, takeAway)
                    if (snapshot.printerType == PrinterType.IPP) {
                        val uri = snapshot.printerIp ?: throw ippUriNotConfigured()
                        ippPrinterService.printViaIpp(formattedData, uri, IppDocumentFormat.ESC_POS)
                    } else {
                        val printService = printerServiceFactory.getPrintService(snapshot.printerType)
                        val printerIdentifier =
                            snapshot.buildIdentifier()
                                ?: throw printerIdentifierNotConfigured()
                        printService.print(formattedData, printerIdentifier)
                    }
                }
            }

        // Step 3: Determine status and error message
        val status =
            if (printResult.isSuccess) {
                PrinterDto.PrintJobStatus.SUCCESS
            } else {
                PrinterDto.PrintJobStatus.FAILED
            }
        val errorMessage = printResult.exceptionOrNull()?.message

        // Step 4: Log to audit trail (separate dbQuery)
        val jobId =
            try {
                dbQuery {
                    printJobHistoryRepository.createPrintJob(
                        printType = PrinterDto.PrintType.ORDER,
                        printerType = snapshot.printerType,
                        documentId = order.orderId.toString(),
                        status = status,
                        errorMessage = errorMessage,
                        printedBy = order.employeeId,
                        printerLocationId = snapshot.printerLocationId,
                    )
                }
            } catch (_: Exception) {
                null
            }

        // Step 5: Build and return response
        return if (printResult.isSuccess) {
            logger.info(
                "PrinterServiceImpl.printOrder: successfully printed order orderId={}, jobId={}",
                order.orderId,
                jobId,
            )
            PrinterDto.PrintResponseDto(
                success = true,
                message = printResult.getOrNull() ?: "OK",
                printJobId = jobId,
                timestamp = Clock.System.now().toString(),
            )
        } else {
            logger.warn(
                "PrinterServiceImpl.printOrder: failed to print order orderId={}, error={}",
                order.orderId,
                errorMessage,
            )
            PrinterDto.PrintResponseDto(
                success = false,
                message = errorMessage ?: "Unknown error occurred",
                printJobId = jobId,
                timestamp = Clock.System.now().toString(),
            )
        }
    }

    private data class PrinterSnapshot(
        val printerType: PrinterType,
        val printerIp: String?,
        val printerPort: Int?,
        val printerName: String?,
        val ippDocumentFormat: IppDocumentFormat?,
        val language: Language?,
        val beepMode: BeepMode,
        val printerLocationId: Long?,
    ) {
        fun buildIdentifier(): String? =
            when (printerType) {
                PrinterType.USB -> printerName
                PrinterType.NETWORK -> "$printerIp:${printerPort ?: 9100}"
                PrinterType.IPP -> printerIp
            }
    }

    /**
     * Print a Z-Bon (end-of-day report) to a printer.
     */
    override suspend fun printZBon(
        format: suspend () -> ByteArray,
        printerId: Long?,
    ): PrinterDto.PrintResponseDto {
        logger.info("PrinterServiceImpl.printZBon: printing Z-Bon, printerId={}", printerId)
        // Step 1: Get printer configuration
        val printer =
            dbQuery {
                if (printerId != null) {
                    printerConfigRepository.getPrinterById(printerId)
                } else {
                    printerConfigRepository.getDefaultPrinter()
                }
            } ?: throw printerNotConfigured()

        // Step 2: Format + print
        val printResult: kotlin.Result<String> =
            runCatching {
                val formattedData = format()
                if (printer.printerType == PrinterType.IPP) {
                    val uri = printer.printerIp ?: throw ippUriNotConfigured()
                    ippPrinterService.printViaIpp(formattedData, uri, IppDocumentFormat.ESC_POS)
                } else {
                    val printService = printerServiceFactory.getPrintService(printer.printerType)
                    val printerIdentifier =
                        buildPrinterIdentifier(printer)
                            ?: throw printerIdentifierNotConfigured()
                    printService.print(formattedData, printerIdentifier)
                }
            }

        return if (printResult.isSuccess) {
            logger.info("PrinterServiceImpl.printZBon: successfully printed Z-Bon")
            PrinterDto.PrintResponseDto(
                success = true,
                message = printResult.getOrNull() ?: "OK",
                printJobId = null,
                timestamp = Clock.System.now().toString(),
            )
        } else {
            val zbonError = printResult.exceptionOrNull()?.message ?: "Unknown error"
            logger.warn("PrinterServiceImpl.printZBon: failed to print Z-Bon, error={}", zbonError)
            PrinterDto.PrintResponseDto(
                success = false,
                message = zbonError,
                printJobId = null,
                timestamp = Clock.System.now().toString(),
            )
        }
    }

    /**
     * Print a reference print (Referenzdruck) for a table order.
     */
    override suspend fun printReferencePrint(
        shopHeader: ShopHeaderData,
        tableNumber: String,
        employeeNumber: String,
        dateTime: String,
        items: List<OrderItemDto.OrderItemResponseDto>,
        printerId: Long?,
    ): PrinterDto.PrintResponseDto {
        logger.info("PrinterServiceImpl.printReferencePrint: printing reference for table={}", tableNumber)

        val printer =
            if (printerId != null) {
                printerConfigRepository.getPrinterById(printerId)
            } else {
                printerConfigRepository.getDefaultPrinter()
            } ?: throw printerNotConfigured()

        val printResult: kotlin.Result<String> =
            runCatching {
                if (printer.printerType == PrinterType.IPP && printer.ippDocumentFormat == IppDocumentFormat.PDF) {
                    val pdfData =
                        pdfGenerationService.generateReferencePrintPdf(
                            shopHeader,
                            tableNumber,
                            employeeNumber,
                            dateTime,
                            items,
                        )
                    val uri = printer.printerIp ?: throw ippUriNotConfigured()
                    ippPrinterService.printViaIpp(pdfData, uri, IppDocumentFormat.PDF)
                } else {
                    val formattedData =
                        printFormatterService.formatReferencePrint(
                            shopHeader,
                            tableNumber,
                            employeeNumber,
                            dateTime,
                            items,
                            Language.GERMAN,
                        )
                    if (printer.printerType == PrinterType.IPP) {
                        val uri = printer.printerIp ?: throw ippUriNotConfigured()
                        ippPrinterService.printViaIpp(formattedData, uri, IppDocumentFormat.ESC_POS)
                    } else {
                        val printService = printerServiceFactory.getPrintService(printer.printerType)
                        val printerIdentifier =
                            buildPrinterIdentifier(printer)
                                ?: throw printerIdentifierNotConfigured()
                        printService.print(formattedData, printerIdentifier)
                    }
                }
            }

        val status =
            if (printResult.isSuccess) {
                PrinterDto.PrintJobStatus.SUCCESS
            } else {
                PrinterDto.PrintJobStatus.FAILED
            }
        val errorMessage = printResult.exceptionOrNull()?.message

        val jobId =
            try {
                printJobHistoryRepository.createPrintJob(
                    printType = PrinterDto.PrintType.REFERENCE_PRINT,
                    printerType = printer.printerType,
                    documentId = "table-$tableNumber",
                    status = status,
                    errorMessage = errorMessage,
                    printedBy = employeeNumber,
                    printerLocationId = printer.printerLocation?.id?.value,
                )
            } catch (_: Exception) {
                null
            }

        return if (printResult.isSuccess) {
            logger.info("PrinterServiceImpl.printReferencePrint: success for table={}, jobId={}", tableNumber, jobId)
            PrinterDto.PrintResponseDto(
                success = true,
                message = printResult.getOrNull() ?: "OK",
                printJobId = jobId,
                timestamp = Clock.System.now().toString(),
            )
        } else {
            logger.warn(
                "PrinterServiceImpl.printReferencePrint: failed for table={}, error={}",
                tableNumber,
                errorMessage,
            )
            PrinterDto.PrintResponseDto(
                success = false,
                message = errorMessage ?: "Unknown error occurred",
                printJobId = jobId,
                timestamp = Clock.System.now().toString(),
            )
        }
    }

    /**
     * Build printer identifier string from printer setup entity.
     */
    private fun buildPrinterIdentifier(printer: org.example.project.features.printer.domain.models.PrinterSetup): String? =
        when (printer.printerType) {
            PrinterType.USB -> printer.printerName
            PrinterType.NETWORK -> "${printer.printerIp}:${printer.printerPort ?: 9100}"
            PrinterType.IPP -> printer.printerIp
        }

    /**
     * Send a test print page to the printer configured for the given location.
     */
    override suspend fun testPrint(
        printerLocationId: Long,
        printedBy: String,
    ): PrinterDto.PrintResponseDto {
        logger.info(
            "PrinterServiceImpl.testPrint: sending test print to location {}, requestedBy={}",
            printerLocationId,
            printedBy,
        )
        // Step 1: Get printer location and setup
        val (locationName, printerSetup, printerType, ippFormat) =
            dbQuery {
                val location =
                    PrinterLocation.findById(printerLocationId)
                        ?: throw printerLocationNotFound(printerLocationId)
                val setup = location.printerSetup
                TestPrintConfig(location.printerLocation, setup, setup.printerType, setup.ippDocumentFormat)
            }

        // Step 2: Generate test page content
        val testPageBytes =
            generateTestPage(
                locationName = locationName,
                printerType = printerType,
                printerSetup = printerSetup,
                ippFormat = ippFormat,
            )

        // Step 3: Execute print based on printer type
        val printResult: kotlin.Result<String> =
            runCatching {
                if (printerType == PrinterType.IPP) {
                    val uri = printerSetup.printerIp ?: throw ippUriNotConfigured()
                    val format = ippFormat ?: IppDocumentFormat.ESC_POS
                    ippPrinterService.printViaIpp(testPageBytes, uri, format)
                } else {
                    val identifier =
                        buildPrinterIdentifier(printerSetup)
                            ?: throw printerIdentifierNotConfigured()
                    val printService = printerServiceFactory.getPrintService(printerType)
                    printService.print(testPageBytes, identifier)
                }
            }

        val status =
            if (printResult.isSuccess) {
                PrinterDto.PrintJobStatus.SUCCESS
            } else {
                PrinterDto.PrintJobStatus.FAILED
            }
        val errorMessage = printResult.exceptionOrNull()?.message

        // Step 5: Log to audit trail
        val jobId =
            try {
                dbQuery {
                    printJobHistoryRepository.createPrintJob(
                        printType = PrinterDto.PrintType.TEST_PRINT,
                        printerType = printerType,
                        documentId = "test-print-$printerLocationId",
                        status = status,
                        errorMessage = errorMessage,
                        printedBy = printedBy,
                        printerLocationId = printerLocationId,
                    )
                }
            } catch (_: Exception) {
                null
            }

        // Step 6: Build and return response
        return if (printResult.isSuccess) {
            logger.info(
                "PrinterServiceImpl.testPrint: successfully sent test print to location {}, jobId={}",
                printerLocationId,
                jobId,
            )
            PrinterDto.PrintResponseDto(
                success = true,
                message = "Test print sent successfully",
                printJobId = jobId,
                timestamp = Clock.System.now().toString(),
            )
        } else {
            logger.warn(
                "PrinterServiceImpl.testPrint: failed to send test print to location {}, error={}",
                printerLocationId,
                errorMessage,
            )
            PrinterDto.PrintResponseDto(
                success = false,
                message = errorMessage ?: "Test print failed",
                printJobId = jobId,
                timestamp = Clock.System.now().toString(),
            )
        }
    }

    /**
     * Generate test page bytes based on printer type and document format.
     */
    private suspend fun generateTestPage(
        locationName: String,
        printerType: PrinterType,
        printerSetup: org.example.project.features.printer.domain.models.PrinterSetup,
        ippFormat: IppDocumentFormat?,
    ): ByteArray =
        if (printerType == PrinterType.IPP && ippFormat == IppDocumentFormat.PDF) {
            generatePdfTestPage(locationName, printerSetup)
        } else {
            generateEscPosTestPage(locationName, printerType, printerSetup)
        }

    /**
     * Generate ESC/POS test receipt for thermal printers.
     */
    private fun generateEscPosTestPage(
        locationName: String,
        printerType: PrinterType,
        printerSetup: org.example.project.features.printer.domain.models.PrinterSetup,
    ): ByteArray {
        val printerIdentifier = buildPrinterIdentifier(printerSetup) ?: "Unknown"
        val timestamp =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toString()
                .replace("T", " ")
                .substringBefore(".")

        return EscPosEmitter()
            .initialize()
            .align(Alignment.CENTER)
            .fontSize(FontSize.DOUBLE)
            .bold(true)
            .textLine("TEST PRINT")
            .bold(false)
            .fontSize(FontSize.NORMAL)
            .textLine("")
            .textLine("================================")
            .align(Alignment.LEFT)
            .textLine("Location: $locationName")
            .textLine("Type:     ${printerType.name}")
            .textLine("Printer:  $printerIdentifier")
            .textLine("Date:     $timestamp")
            .textLine("================================")
            .textLine("")
            .align(Alignment.CENTER)
            .bold(true)
            .textLine("Print successful!")
            .bold(false)
            .textLine("")
            .cut(CutType.FULL)
            .build()
    }

    /**
     * Generate PDF test page for IPP printers using PDF format.
     */
    private suspend fun generatePdfTestPage(
        locationName: String,
        printerSetup: org.example.project.features.printer.domain.models.PrinterSetup,
    ): ByteArray {
        val timestamp =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toString()
                .replace("T", " ")
                .substringBefore(".")
        val uri = printerSetup.printerIp ?: "N/A"

        return pdfGenerationService.generateTestPage(
            locationName = locationName,
            printerUri = uri,
            timestamp = timestamp,
        )
    }

    /**
     * Build a failure PrintResponseDto when formatting fails. Logs to audit trail
     * with status=FAILED so the failure is recorded even though the caller does
     * not see a thrown exception.
     */
    private suspend fun buildFailureResponse(
        printerType: PrinterType,
        printerLocationId: Long?,
        printType: PrinterDto.PrintType,
        documentId: String,
        printedBy: String,
        errorMessage: String,
    ): PrinterDto.PrintResponseDto {
        val jobId =
            try {
                dbQuery {
                    printJobHistoryRepository.createPrintJob(
                        printType = printType,
                        printerType = printerType,
                        documentId = documentId,
                        status = PrinterDto.PrintJobStatus.FAILED,
                        errorMessage = errorMessage,
                        printedBy = printedBy,
                        printerLocationId = printerLocationId,
                    )
                }
            } catch (_: Exception) {
                null
            }
        return PrinterDto.PrintResponseDto(
            success = false,
            message = errorMessage,
            printJobId = jobId,
            timestamp = Clock.System.now().toString(),
        )
    }

    /**
     * Data class for extracting printer location config from DB transaction.
     */
    private data class TestPrintConfig(
        val locationName: String,
        val printerSetup: org.example.project.features.printer.domain.models.PrinterSetup,
        val printerType: PrinterType,
        val ippFormat: IppDocumentFormat?,
    )

    // ---- PosException helpers ----

    private fun printerNotConfigured() =
        PosException(
            PosErrorCode.PRINTER_NOT_FOUND,
            HttpStatusCode.NotFound,
            "No printer configured. Please set up a printer first.",
        )

    private fun printerLocationNotConfigured() =
        PosException(
            PosErrorCode.PRINTER_CONNECTION_INVALID,
            HttpStatusCode.BadRequest,
            "Printer location not configured",
        )

    private fun printerLocationLanguageNotConfigured() =
        PosException(
            PosErrorCode.PRINTER_CONNECTION_INVALID,
            HttpStatusCode.BadRequest,
            "Printer location language not configured",
        )

    private fun billPrintingDisabled() =
        PosException(
            PosErrorCode.PRINTER_CONNECTION_INVALID,
            HttpStatusCode.BadRequest,
            "Bill printing is disabled for this printer location.",
        )

    private fun ippUriNotConfigured() =
        PosException(
            PosErrorCode.PRINTER_CONNECTION_INVALID,
            HttpStatusCode.BadRequest,
            "IPP URI not configured",
        )

    private fun printerIdentifierNotConfigured() =
        PosException(
            PosErrorCode.PRINTER_CONNECTION_INVALID,
            HttpStatusCode.BadRequest,
            "Printer identifier not configured",
        )

    private fun printerLocationNotFound(id: Long) =
        PosException(
            PosErrorCode.PRINTER_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Printer location not found: $id",
        )
}
