/**
 * REST API routes for Voucher operations.
 *
 * Throws PosException directly on validation/auth errors; the global
 * StatusPages plugin emits application/problem+json responses.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.core.security.getTokenClaims
import org.example.project.core.security.heldCapabilities
import org.example.project.features.voucher.domain.service.VoucherService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

/**
 * Configure Voucher routes.
 */
fun Route.voucherRoutes() {
    val voucherService by inject<VoucherService>()

    authenticate("auth-jwt") {
        route("/api/vouchers") {
            /**
             * Get voucher statistics.
             *
             * Description: Issued, redeemed and outstanding voucher totals. Outstanding vouchers are a liability, so this is a figure the operator reconciles rather than a vanity metric. The calling employee is taken from the JWT and scopes what is returned.
             *
             * Tag: vouchers
             * Response: 401 Missing or invalid bearer token.
             */
            get("/statistics") {
                val employeeId = call.getEmployeeIdFromJwt()
                call.respond(HttpStatusCode.OK, voucherService.getVoucherStatistics(employeeId))
            }

            /**
             * List active vouchers.
             *
             * Description: Vouchers that still carry a remaining balance — the outstanding liability, one row per voucher. Paged by the optional `limit` and `offset` query parameters. Backs the web back office's "Aktive Gutscheine" tab.
             *
             * Tag: vouchers
             * Response: 401 Missing or invalid bearer token.
             */
            get("/active") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                call.respond(HttpStatusCode.OK, voucherService.getActiveVouchers(limit, offset))
            }

            /**
             * Search vouchers by number.
             *
             * Description: `q` is required and matched against the voucher number. Paged by the optional `limit` and `offset` query parameters. Backs the web back office's voucher-number search.
             *
             * Tag: vouchers
             * Response: 400 q is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/search") {
                val searchTerm =
                    call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Search term q is required",
                        )
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                call.respond(HttpStatusCode.OK, voucherService.searchVouchersByNumber(searchTerm, limit, offset))
            }

            /**
             * List vouchers issued in a period.
             *
             * Description: Both `startDate` and `endDate` query parameters are required. `employeeId` narrows to vouchers issued by one employee; without it the caller's own scope applies.
             *
             * Tag: vouchers
             * Response: 400 startDate or endDate is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/created-by-date") {
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]

                if (startDate.isNullOrBlank() || endDate.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Start date and end date are required",
                    )
                }

                val employeeId = call.getEmployeeIdFromJwt()
                val filterEmployeeId = call.request.queryParameters["employeeId"]
                // NOT VOUCHER_REPORT, which gates the route: a cashier holds that and would be
                // widened from their own vouchers to the shop's. REPORT_ALL_READ is the capability
                // that means "other people's figures", and these rollups are figures.
                val seesEveryone = Capability.REPORT_ALL_READ in call.heldCapabilities()

                call.respond(
                    HttpStatusCode.OK,
                    voucherService.getVouchersCreatedInRange(startDate, endDate, employeeId, filterEmployeeId, seesEveryone),
                )
            }

            /**
             * List vouchers redeemed in a period.
             *
             * Description: The redemption side of the same ledger — what was actually taken against outstanding vouchers. Both `startDate` and `endDate` query parameters are required, and `employeeId` narrows by employee.
             *
             * Tag: vouchers
             * Response: 400 startDate or endDate is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/redeemed-by-date") {
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]

                if (startDate.isNullOrBlank() || endDate.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Start date and end date are required",
                    )
                }

                val employeeId = call.getEmployeeIdFromJwt()
                val filterEmployeeId = call.request.queryParameters["employeeId"]
                // NOT VOUCHER_REPORT, which gates the route: a cashier holds that and would be
                // widened from their own vouchers to the shop's. REPORT_ALL_READ is the capability
                // that means "other people's figures", and these rollups are figures.
                val seesEveryone = Capability.REPORT_ALL_READ in call.heldCapabilities()

                call.respond(
                    HttpStatusCode.OK,
                    voucherService.getVouchersRedeemedInRange(startDate, endDate, employeeId, filterEmployeeId, seesEveryone),
                )
            }

            /**
             * Create a voucher.
             *
             * Description: Issues a voucher and records the issuing employee and device from the JWT. The value is an exact decimal sent as a string. Issuing is a fiscal event — the voucher becomes an outstanding liability until redeemed or expired.
             *
             * Tag: vouchers
             * Response: 400 The body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 409 A voucher with that code already exists.
             */
            post {
                val request =
                    try {
                        call.receive<VoucherDto.CreateVoucherRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }

                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val response =
                    voucherService.createVoucher(
                        tokenClaims.employeeId,
                        tokenClaims.deviceId,
                        request,
                        call.heldCapabilities(),
                    )
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Get a voucher's redemption history.
             *
             * Description: Lists every redemption booked against one voucher, oldest first, each row carrying the amount taken and the remaining value after it. The remaining value is accumulated from the voucher's original value, so the ledger reads as a running-down balance.
             *
             * Tag: vouchers
             * Path: voucherNumber The human-readable voucher number.
             * Response: 400 voucherNumber is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No voucher with that number.
             */
            get("/number/{voucherNumber}/redemption-history") {
                val voucherNumber = call.parameters["voucherNumber"]
                if (voucherNumber.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Voucher number is required",
                    )
                }

                call.respond(HttpStatusCode.OK, voucherService.getVoucherRedemptionHistory(voucherNumber))
            }

            /**
             * Validate a voucher.
             *
             * Description: Checks a code before the till accepts it, reporting whether it exists, is still valid, and what balance remains. It reserves nothing and changes nothing — redemption happens when the bill is created.
             *
             * Tag: vouchers
             * Response: 400 The body is malformed or the code failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No voucher with that code.
             */
            post("/validate") {
                val request =
                    try {
                        call.receive<VoucherDto.ValidateVoucherRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }

                call.respond(HttpStatusCode.OK, voucherService.validateVoucher(request))
            }
        }
    }
}
