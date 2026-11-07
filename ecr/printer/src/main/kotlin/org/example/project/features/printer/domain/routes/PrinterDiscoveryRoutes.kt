/**
 * Ktor routes for IPP printer discovery and status API endpoints.
 *
 * Provides REST API endpoints for discovering printers on the local network
 * via CUPS/IPP protocol, checking printer status, and querying supported formats.
 *
 * All routes are protected with JWT authentication and return printer data
 * as DiscoveredPrinterDto for client consumption.
 *
 * @since 1.0.0
 */
package org.example.project.features.printer.domain.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.IppDocumentFormat
import org.example.project.features.printer.domain.service.PrinterLocationService
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.printer.ipp.domain.service.IppPrinterService
import org.koin.ktor.ext.inject
import java.time.LocalDateTime

/**
 * Configures all printer discovery-related routes.
 *
 * IMPORTANT: All routes are wrapped in a SINGLE authenticate block to prevent
 * "Default authentication configuration was not found" runtime errors.
 */
fun Route.printerDiscoveryRoutes() {
    val ippPrinterService by inject<IppPrinterService>()
    val printerLocationService by inject<PrinterLocationService>()
    val printerService by inject<PrinterService>()

    // CRITICAL: Single authenticate wrapper for all routes
    authenticate("auth-jwt") {
        route("/api/printer-discovery") {
            /**
             * Discover printers on the local network.
             *
             * Description: Queries CUPS over IPP for reachable printers and returns each with its URI, model, state and supported document formats. Discovery is a live network sweep, so it is slower than the configured printer-location list.
             *
             * Tag: printer-discovery
             * Response: 401 Missing or invalid bearer token.
             * Response: 503 The local CUPS service could not be reached.
             */
            get("/discover") {
                // IppPrinterService throws on failure; StatusPages handles errors.
                val printers = ippPrinterService.discoverPrinters()
                val dtos =
                    printers.map { printer ->
                        PrinterDto.DiscoveredPrinterDto(
                            name = printer.name,
                            uri = printer.uri,
                            makeAndModel = printer.makeAndModel,
                            location = printer.location,
                            state = printer.state,
                            connectionType = printer.connectionType,
                            supportedFormats = printer.supportedFormats,
                        )
                    }
                call.respond(HttpStatusCode.OK, dtos)
            }

            /**
             * Get a printer's status.
             *
             * Description: Asks one printer for its current state. The `uri` query parameter is required and is the IPP URI returned by discovery.
             *
             * Tag: printer-discovery
             * Response: 400 The uri query parameter is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 503 The printer did not answer.
             */
            get("/status") {
                val uri =
                    call.request.queryParameters["uri"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing required parameter: uri")

                val printer = ippPrinterService.getPrinterStatus(uri)
                val dto =
                    PrinterDto.DiscoveredPrinterDto(
                        name = printer.name,
                        uri = printer.uri,
                        makeAndModel = printer.makeAndModel,
                        location = printer.location,
                        state = printer.state,
                        connectionType = printer.connectionType,
                        supportedFormats = printer.supportedFormats,
                    )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List a printer's supported document formats.
             *
             * Description: The MIME types the printer accepts, which decides whether it can be sent a PDF or must be driven with ESC/POS. The `uri` query parameter is required.
             *
             * Tag: printer-discovery
             * Response: 400 The uri query parameter is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 503 The printer did not answer.
             */
            get("/formats") {
                val uri =
                    call.request.queryParameters["uri"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing required parameter: uri")

                val formats = ippPrinterService.getSupportedFormats(uri)
                call.respond(HttpStatusCode.OK, formats)
            }

            /**
             * Print a PDF to an IPP printer.
             *
             * Description: Send the document as a multipart upload with a `printerLocationId` form field and a `file` part. The target must be a configured printer location whose setup is an IPP printer declaring PDF support and carrying a URI — each of those is checked before anything is sent.
             *
             * Tag: printer-discovery
             * Response: 400 printerLocationId or file is missing, the PDF is empty, or the location is not a PDF-capable IPP printer with a URI.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No printer location with that id.
             * Response: 503 The printer did not answer.
             */
            post("/print-pdf") {
                val multipart = call.receiveMultipart()

                var pdfBytes: ByteArray? = null
                var printerLocationId: Long? = null

                // Parse multipart parts
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == "printerLocationId") {
                                printerLocationId = part.value.toLongOrNull()
                            }
                        }
                        is PartData.FileItem -> {
                            if (part.name == "file") {
                                pdfBytes = part.provider().readRemaining().readByteArray()
                            }
                        }
                        else -> {}
                    }
                    part.release()
                }

                // Validate required fields
                val locationId =
                    printerLocationId
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing required field: printerLocationId")
                val fileBytes =
                    pdfBytes
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing required file: file")

                if (fileBytes.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "PDF file is empty")
                }

                // Look up the printer location and verify it's an IPP+PDF printer
                // PrinterLocationService now throws PosException; StatusPages handles errors.
                val location = printerLocationService.getPrinterLocationById(locationId)
                val printerSetup =
                    location.printerSetup
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "Printer location has no printer setup configured",
                        )

                if (printerSetup.printerType != "IPP") {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "Printer is not an IPP printer (type: ${printerSetup.printerType})",
                    )
                }

                if (printerSetup.ippDocumentFormat != IppDocumentFormat.PDF.name) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "Printer does not support PDF format (format: ${printerSetup.ippDocumentFormat})",
                    )
                }

                val printerUri =
                    printerSetup.printerIp
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "Printer has no IPP URI configured",
                        )

                // Send the PDF to the IPP printer (throws on failure; StatusPages handles)
                val printMessage =
                    ippPrinterService.printViaIpp(
                        data = fileBytes,
                        printerUri = printerUri,
                        documentFormat = IppDocumentFormat.PDF,
                    )
                val timestamp = LocalDateTime.now().toString()
                call.respond(
                    HttpStatusCode.OK,
                    PrinterDto.PrintResponseDto(
                        success = true,
                        message = printMessage,
                        timestamp = timestamp,
                    ),
                )
            }

            /**
             * Send a test page to a printer.
             *
             * Description: Detects the printer's type and generates a test page in the right format, so an operator can confirm a newly configured printer works. The page names the employee who triggered it, taken from the JWT.
             *
             * Tag: printer-discovery
             * Path: printerLocationId Printer location identifier.
             * Response: 400 printerLocationId is missing, or is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No printer location with that id.
             * Response: 503 The printer did not answer.
             */
            post("/test-print/{printerLocationId}") {
                val printerLocationId =
                    call.parameters["printerLocationId"]?.toLongOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "Invalid or missing printerLocationId",
                        )

                val principal = call.principal<JWTPrincipal>()
                val employeeName = principal?.payload?.getClaim("name")?.asString() ?: "Unknown"

                // PrinterService now throws PosException; StatusPages handles errors.
                val testResult = printerService.testPrint(printerLocationId, employeeName)
                call.respond(HttpStatusCode.OK, testResult)
            }
        }
    }
}
