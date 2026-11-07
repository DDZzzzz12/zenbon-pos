/**
 * Implementation of Voucher service with business logic.
 *
 * Provides business operations for Voucher management including
 * validation, data transformation, redemption processing, and coordination
 * with repository layer. Handles gift voucher and store credit functionality.
 *
 * Throws [PosException] on errors; routes catch via StatusPages.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.data.service

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.voucher.domain.repository.VoucherRepository
import org.example.project.features.voucher.domain.service.VoucherService
import org.example.project.features.voucher.mapper.toDto
import org.example.project.features.voucher.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.config.VatMappingUtil
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.toBigDecimal
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.log.AuditEntityRef
import org.example.project.utils.resolveTargetEmployee
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.exposedLogger
import org.jetbrains.exposed.v1.dao.exceptions.EntityNotFoundException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

/**
 * Implementation of VoucherService.
 */
class VoucherServiceImpl(
    private val repository: VoucherRepository,
    private val billService: BillService,
    private val orderService: OrderService,
    private val terminalRepository: TerminalRepository,
    private val tableSessionRepository: TableSessionRepository,
    private val configManager: IShopConfigurationManager,
) : VoucherService {
    private val logger = LoggerFactory.getLogger(VoucherServiceImpl::class.java)

    override suspend fun getAllVouchers(
        limit: Int?,
        offset: Int,
        includeRedeemed: Boolean,
    ): List<VoucherDto.VoucherResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getAllVouchers(limit, offset, includeRedeemed).map { it.toResponse(currency = currency) }
        }
    }

    override suspend fun getVoucherById(id: Long): VoucherDto.VoucherResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val voucher = repository.getVoucherById(id) ?: throw voucherNotFound(id)
            voucher.toResponse(currency = currency)
        }
    }

    override suspend fun getVoucherByNumber(voucherNumber: String): VoucherDto.VoucherResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val voucher =
                repository.getVoucherByNumber(voucherNumber)
                    ?: throw voucherNotFoundByNumber(voucherNumber)
            voucher.toResponse(currency = currency)
        }
    }

    override suspend fun getActiveVouchers(
        limit: Int?,
        offset: Int,
    ): List<VoucherDto.VoucherResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getActiveVouchers(limit, offset).map { it.toResponse(currency = currency) }
        }
    }

    override suspend fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int?,
        offset: Int,
    ): List<VoucherDto.VoucherResponseDto> {
        if (searchTerm.isBlank()) throw validationFailed("searchTerm is required")
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.searchVouchersByNumber(searchTerm, limit, offset).map { it.toResponse(currency = currency) }
        }
    }

    override suspend fun createVoucher(
        employeeId: String,
        deviceId: String,
        request: VoucherDto.CreateVoucherRequestDto,
        actorCapabilities: Set<Capability>,
    ): VoucherDto.VoucherResponseDto {
        // Validate request
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }

        val currency = configManager.getDefaultCurrency()

        return withRetryThrows(
            operationName = if (request.sessionId == null) "Create quick sale voucher" else "Create table session voucher",
            config = RetryConfig.AGGRESSIVE,
        ) {
            ExceptionConverter.catchingThrows(
                context = "createVoucher",
                employeeId = employeeId,
                operation = "CREATE_VOUCHER",
                entityType = "VOUCHER",
                deviceId = deviceId,
                auditEntity = { AuditEntityRef(id = it.id.toString(), name = it.voucherNumber) },
            ) {
                // Track the voucher number to release on transaction completion or failure
                var voucherNumberToRelease: String? = null

                try {
                    val employeeUuid = UUID.fromString(employeeId)
                    val employee =
                        dbQuery {
                            Employee.findById(employeeUuid)
                                ?: throw EntityNotFoundException(EntityID(id = employeeUuid, EmployeeTable), Employee)
                        }

                    // Generate and reserve voucher number with thread-safe tracking
                    val voucherNumber =
                        dbQuery {
                            generateAndReserveVoucherNumber()
                        }
                    voucherNumberToRelease = voucherNumber

                    val voucherValue = toBigDecimal(request.originalValue)

                    requireVoucherFeatureEnabled()

                    // Get voucher product (ProductType.VOUCHER)
                    val voucherProduct =
                        dbQuery {
                            Product.find { ProductTable.productNumber eq "VOU" }.firstOrNull()
                                ?: throw NotFoundException("VOUCHER product not found")
                        }

                    val sessionId = request.sessionId
                    val vat =
                        dbQuery {
                            VatMappingUtil.determineVat(voucherProduct.productType, false, configManager = configManager)
                        }
                    val result =
                        if (sessionId == null) {
                            // COUNTER SALE MODE: no table session — open one on the virtual VOUCHER
                            // table and settle immediately. NOT wrapped in dbQuery: createCounterVoucher
                            // delegates to BillService.createBill, which owns its own retryable
                            // transaction and performs the TSE signing *outside* it.
                            createCounterVoucher(
                                employee = employee,
                                voucherNumber = voucherNumber,
                                voucherValue = voucherValue,
                                voucherProduct = voucherProduct,
                                request = request,
                                vat = vat,
                                deviceId = deviceId,
                                currency = currency,
                            )
                        } else {
                            // TABLE SESSION MODE: Add to existing session
                            dbQuery {
                                createTableSessionVoucher(
                                    employee = employee,
                                    sessionId = sessionId,
                                    voucherNumber = voucherNumber,
                                    voucherValue = voucherValue,
                                    voucherProduct = voucherProduct,
                                    vat = vat,
                                    deviceId = deviceId,
                                    currency = currency,
                                    actorCapabilities = actorCapabilities,
                                )
                            }
                        }

                    // Release voucher number on successful transaction
                    voucherNumberToRelease.let { VoucherNumberTracker.release(it) }
                    voucherNumberToRelease = null

                    result
                } catch (e: Exception) {
                    voucherNumberToRelease?.let { VoucherNumberTracker.release(it) }
                    throw e
                }
            }
        }
    }

    /**
     * Creates and immediately settles a voucher sold over the counter (no table session).
     *
     * Replaces the former two-step quick-sale flow (`startQuickSaleTse` → TSE StartTransaction →
     * `createQuickSaleBill` → TSE FinishTransaction). That flow was the ONLY caller of the TSE
     * two-step API in the whole system, and therefore the only way a Vorgang could be aborted
     * after Transaktionsbeginn — the `AVBelegabbruch` case of DSFinV-K Anhang B p. 46
     * ("alle Vorgänge, die nach Transaktionsbeginn abgebrochen werden"), which the DSFinV-K
     * export had no producer for. Settling through [BillService.createBill] signs the Beleg with
     * the atomic quickSign (StartTransaction + FinishTransaction in one WormAPI sequence), so no
     * transaction can ever be left open by this path.
     *
     * The fiscal shape is deliberately unchanged from the old flow: the voucher line is attached
     * straight to the session with `order = null`, so the sale emits exactly ONE Kassenbeleg-V1
     * Vorgang and no `AVBestellung` — routing it through [OrderService.createOrder] would have
     * added a second, spurious TSE transaction per voucher.
     */
    private suspend fun createCounterVoucher(
        employee: Employee,
        voucherNumber: String,
        voucherValue: BigDecimal,
        voucherProduct: Product,
        request: VoucherDto.CreateVoucherRequestDto,
        vat: org.example.project.features.payment.domain.models.Vat,
        deviceId: String,
        currency: String,
    ): VoucherDto.VoucherResponseDto {
        // 1. Resolve (or open) the session on the virtual VOUCHER table.
        val (sessionId, orderItemId) =
            dbQuery {
                val table =
                    RTable
                        .find {
                            RTableTable.locationTypeId eq WellKnownLocationTypes.VOUCHER
                        }.firstOrNull()
                        ?: throw PosException(
                            PosErrorCode.INTERNAL_ERROR,
                            HttpStatusCode.NotFound,
                            "VOUCHER virtual table not found. Ensure initializeDefaults has been called.",
                        )

                val session = tableSessionRepository.getOrCreateSession(table, employee).second
                session.sessionType = table.locationTypeRef.name

                // 2. Attach the voucher line directly to the session (no parent Order — see KDoc).
                // Money leaves the price computation at 2dp, in parity with the order path
                // (OrderServiceImpl.kt:397), so the VAT split, the signed processData buckets and the
                // printed Beleg agree to the cent.
                val orderItem =
                    OrderItem.new {
                        this.order = null
                        this.session = session
                        this.product = voucherProduct
                        this.unitPrice = voucherValue
                        this.finalPrice = voucherValue.setScale(2, java.math.RoundingMode.HALF_UP)
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat
                        this.discount = BigDecimal.ONE
                        this.customPrice = true
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = "VOUCHER: $voucherNumber"
                    }

                Pair(session.id.value, orderItem.id.value)
            }

        // 3. Settle through the normal bill path — atomic quickSign, session completion, Z-Bon
        // attribution and DSFinV-K export all behave exactly as for a table bill.
        //
        // The tip rides on the bill exactly as a table bill's does: `tips` on the bill, and the one
        // payment covers value + tip (createBill checks payments == billAmount − promotions + tips).
        // It used to be dropped here while the till's dialog showed and collected value + tip, so
        // the drawer came up over by the tip at the cash count and no bill recorded it (T5-3).
        val tip = request.tipAmount?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val bill =
            try {
                billService.createBill(
                    employeeId = employee.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = sessionId,
                            orderItems = listOf(orderItemId),
                            billAmount = voucherValue.toDecimalString(),
                            tips = tip.toDecimalString(),
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(
                                        amount = (voucherValue + tip).toDecimalString(),
                                        paymentTypeId = request.paymentTypeId!!,
                                    ),
                                ),
                            voucherRedeem = emptyList(),
                        ),
                )
            } catch (e: Exception) {
                // Billing failed after the line was attached: close the session so the virtual VOUCHER
                // table is not left holding an OPEN item that would attach itself to the next sale.
                dbQuery {
                    TableSession.findById(sessionId)?.end("Counter voucher sale failed")
                }
                throw e
            }

        // The repository is plain data access and needs an ambient transaction (the old flow got
        // one from the dbQuery this path no longer runs inside — see the call site).
        val voucher =
            dbQuery {
                repository
                    .createVoucherWithOrderItem(
                        voucherNumber = voucherNumber,
                        voucherValue = voucherValue,
                        orderItemId = orderItemId,
                    ).toResponse(currency = currency)
            }

        logger.info("VoucherServiceImpl.createCounterVoucher: bill created successfully billId=${bill.billId}")

        // The till prints the customer's receipt from this bill (F38) — without it the sale is
        // signed and booked but the cashier has nothing to hand over.
        return voucher.copy(billId = bill.billId)
    }

    /**
     * Creates a voucher for an existing table session.
     */
    private suspend fun createTableSessionVoucher(
        employee: Employee,
        sessionId: Long,
        voucherNumber: String,
        voucherValue: BigDecimal,
        voucherProduct: Product,
        vat: org.example.project.features.payment.domain.models.Vat,
        deviceId: String,
        currency: String,
        actorCapabilities: Set<Capability>,
    ): VoucherDto.VoucherResponseDto {
        val order =
            orderService.createOrder(
                request =
                    OrderDto.CreateOrderRequestDto(
                        tableInfoId = sessionId,
                        orderItems =
                            listOf(
                                OrderItemDto.CreateOrderItemRequestDto(
                                    productId = voucherProduct.id.value,
                                    unitPrice = voucherValue.toDecimalString(),
                                    sideNotes = "VOUCHER: $voucherNumber",
                                    customPrice = true,
                                ),
                            ),
                    ),
                employeeId = employee.id.value.toString(),
                deviceId = deviceId,
                actorCapabilities = actorCapabilities,
                allowVoucherProduct = true,
            )

        if (order.orderItems.size != 1) {
            throw IllegalArgumentException("Error starting voucher: ${order.orderItems.size} is not equal to 1")
        }
        val orderItemId =
            order.orderItems
                .firstOrNull()!!
                .orderItemIds
                .firstOrNull()
        val voucher =
            repository.createVoucherWithOrderItem(
                voucherNumber = voucherNumber,
                voucherValue = voucherValue,
                orderItemId = orderItemId!!,
            )

        logger.info("VoucherServiceImpl.createTableSessionVoucher: voucher $voucherNumber added to sessionId=$sessionId")

        return voucher.toResponse(tableSessionId = sessionId, currency = currency)
    }

    override suspend fun voucherNumberExists(
        voucherNumber: String,
        excludeId: Long?,
    ): Boolean {
        if (voucherNumber.isBlank()) throw validationFailed("voucherNumber is required")
        return dbQuery {
            repository.voucherNumberExists(voucherNumber, excludeId)
        }
    }

    override suspend fun getVoucherStatistics(employeeId: String?): VoucherDto.VoucherStatisticsDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getVoucherStatistics(employeeId).toDto(currency = currency)
        }
    }

    override suspend fun getVoucherRedemptionHistory(voucherNumber: String): VoucherDto.VoucherRedemptionHistoryDto {
        if (voucherNumber.isBlank()) throw validationFailed("voucherNumber is required")
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val voucher =
                repository.getVoucherByNumber(voucherNumber)
                    ?: throw voucherNotFoundByNumber(voucherNumber)

            val redemptions = repository.getVoucherRedemptionHistory(voucher.id.value)

            // `remainingValueAfter` is the balance left AFTER each redemption, so it has to be
            // accumulated oldest-first from originalValue. The repository returns redeemedAt DESC,
            // hence the sort.
            //
            // It must NOT be derived from `voucher.remainingValue`: that is the *current* balance
            // and is constant across the loop, so every entry came out wrong — on a 50.00 voucher
            // redeemed 20.00 then 10.00 it reported 40.00 and 30.00 where the true running balance
            // is 30.00 and 20.00.
            var running = voucher.originalValue
            val balanceAfter =
                redemptions
                    .sortedBy { it.redeemedAt }
                    .associate { redemption ->
                        running -= redemption.redeemAmount
                        redemption.id.value to running
                    }

            VoucherDto.VoucherRedemptionHistoryDto(
                voucherId = voucher.id.value,
                voucherNumber = voucher.voucherNumber,
                redemptions =
                    redemptions.map { redemption ->
                        VoucherDto.VoucherRedemptionEntryDto(
                            redemptionId = redemption.id.value,
                            redeemedAmount = redemption.redeemAmount.toString(),
                            redeemedAt = redemption.redeemedAt.toString(),
                            billNumber = redemption.payment.bill.billNumber,
                            employeeNumber = redemption.payment.bill.employeeId.employeeNumber,
                            remainingValueAfter =
                                balanceAfter
                                    .getValue(redemption.id.value)
                                    .toDecimalString(),
                        )
                    },
                currency = currency,
            )
        }
    }

    override suspend fun validateVoucher(request: VoucherDto.ValidateVoucherRequestDto): VoucherDto.ValidateVoucherResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }

        return dbQuery {
            val voucher =
                repository.getVoucherByNumber(request.voucherNumber)
                    ?: throw voucherNotFoundByNumber(request.voucherNumber)

            val remainingValue = voucher.remainingValue
            val originalValue = voucher.originalValue
            val isFullyRedeemed = voucher.isFullyRedeemed
            val isInvalidated = voucher.status == VoucherStatus.INVALIDATED

            val requestedAmount = request.requestedAmount?.let { BigDecimal(it) }
            val canRedeemRequested =
                requestedAmount?.let {
                    it <= remainingValue && !isFullyRedeemed && !isInvalidated
                } ?: true

            val validationMessage =
                when {
                    isInvalidated -> "Voucher has been invalidated"
                    isFullyRedeemed -> "Voucher is fully redeemed"
                    remainingValue <= BigDecimal.ZERO -> "Voucher has no remaining balance"
                    requestedAmount != null && requestedAmount > remainingValue -> "Requested amount exceeds remaining balance"
                    else -> "Voucher is valid and available for redemption"
                }

            VoucherDto.ValidateVoucherResponseDto(
                isValid = !isFullyRedeemed && !isInvalidated && remainingValue > BigDecimal.ZERO,
                voucherNumber = voucher.voucherNumber,
                isActive = !isFullyRedeemed && !isInvalidated,
                remainingValue = remainingValue.toString(),
                originalValue = originalValue.toString(),
                canRedeemRequested = canRedeemRequested,
                validationMessage = validationMessage,
                expiryDate = null,
            )
        }
    }

    override suspend fun getVouchersCreatedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
    ): List<VoucherDto.VoucherResponseDto> {
        val start = parseDateTime(startDate, "startDate")
        val end = parseDateTime(endDate, "endDate")
        val currency = configManager.getDefaultCurrency()

        return dbQuery {
            val targetEmployee = resolveTargetEmployee(employeeId ?: "", filterEmployeeId, seesEveryone)
            repository.getVouchersCreatedInRange(start, end, targetEmployee).map { it.toResponse(currency = currency) }
        }
    }

    override suspend fun getVouchersRedeemedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
    ): List<VoucherDto.VoucherRedemptionSummaryDto> {
        val start = parseDateTime(startDate, "startDate")
        val end = parseDateTime(endDate, "endDate")
        val currency = configManager.getDefaultCurrency()

        return dbQuery {
            val employee = resolveTargetEmployee(employeeId ?: "", filterEmployeeId, seesEveryone)

            val redemptions = repository.getVoucherRedemptionsInRange(start, end, employee)
            redemptions.map { redemption ->
                val voucher = redemption.voucher
                VoucherDto.VoucherRedemptionSummaryDto(
                    voucherNumber = redemption.voucherNumber,
                    originalValue = voucher?.originalValue?.toString() ?: "0.00",
                    redeemedAmount = redemption.redeemAmount.toString(),
                    remainingValue = voucher?.remainingValue?.toString() ?: "0.00",
                    redeemedAt = redemption.redeemedAt.toString(),
                    redeemedBy = redemption.payment.bill.employeeId.employeeNumber,
                    billNumber = redemption.payment.bill.billNumber,
                    currency = currency,
                    reversedByBillNumber = redemption.reversedByBill?.billNumber,
                    reversalReason = redemption.reversedByBill?.stornoReason,
                )
            }
        }
    }

    /**
     * Generates and reserves a unique voucher number in format: G[YEAR]-[NUMBER]
     */
    private suspend fun generateAndReserveVoucherNumber(): String {
        val currentYear = currentTimeStamp().year
        var attempts = 0
        val maxAttempts = 10

        while (attempts < maxAttempts) {
            attempts++

            val currentCount = repository.countVouchersByYear(currentYear)
            val nextNumber = currentCount + 1
            val voucherNumber = "G%s-%04d".format(currentYear.toString(), nextNumber)

            if (VoucherNumberTracker.reserve(voucherNumber)) {
                exposedLogger.info("Successfully generated and reserved voucher number: $voucherNumber (attempt $attempts)")
                return voucherNumber
            }

            exposedLogger.warn("Voucher number $voucherNumber already reserved by concurrent transaction, retrying... (attempt $attempts)")
            delay(50L * attempts)
        }

        throw IllegalStateException(
            "Failed to generate unique voucher number after $maxAttempts attempts. " +
                "This indicates high concurrent load or a system issue. " +
                "Current pending reservations: ${VoucherNumberTracker.getPendingCount()}",
        )
    }

    private fun voucherNotFound(id: Long) =
        PosException(
            PosErrorCode.VOUCHER_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Voucher with id=$id not found",
        )

    private fun voucherNotFoundByNumber(num: String) =
        PosException(
            PosErrorCode.VOUCHER_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Voucher with number=$num not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun parseDateTime(
        value: String,
        field: String,
    ): LocalDateTime =
        try {
            LocalDateTime.parse(value)
        } catch (e: Exception) {
            throw validationFailed("$field is not a valid ISO-8601 date-time: $value")
        }

    /**
     * Refuses a voucher sale while the voucher feature is switched off.
     *
     * Enabling `VOUCHER_CONFIG` is what seeds the voucher category, the `VOU` product and the
     * `GUTSCHEIN` payment type, so a disabled shop reached the product lookup below and failed with
     * "VOUCHER product not found" - the symptom, not the cause, and as a 500 rather than something
     * the operator could act on.
     *
     * Only a *definite* `enabled == false` blocks the sale. A missing or unparseable config falls
     * through to the previous behaviour on purpose: an older install that was set up by hand may
     * have a working `VOU` product without the config key, and this guard must not take voucher
     * sales away from a shop that has them working today.
     */
    private suspend fun requireVoucherFeatureEnabled() {
        val enabled =
            runCatching {
                voucherConfigJson.decodeFromJsonElement<VoucherConfig>(configManager.getVoucherConfig()).enabled
            }.getOrNull()
        if (enabled == false) {
            throw PosException(
                PosErrorCode.VOUCHER_FEATURE_DISABLED,
                HttpStatusCode.Conflict,
                "The voucher feature is not enabled for this shop - switch it on in the shop configuration first",
            )
        }
    }

    private val voucherConfigJson = Json { ignoreUnknownKeys = true }
}
