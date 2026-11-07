package org.example.project.features.payment.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.payment.domain.repository.PaymentTypeRepository
import org.example.project.features.payment.domain.service.PaymentTypeService
import org.example.project.features.payment.mapper.toListResponse
import org.example.project.features.payment.mapper.toResponse
import org.example.project.features.payment.mapper.toSummaryList
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.slf4j.LoggerFactory

/**
 * Concrete implementation of PaymentTypeService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, DataError> in Stage B Batch 1.
 */
class PaymentTypeServiceImpl(
    private val paymentTypeRepository: PaymentTypeRepository,
) : PaymentTypeService {
    private val logger = LoggerFactory.getLogger(PaymentTypeServiceImpl::class.java)

    override suspend fun getAllPaymentTypes(includeInactive: Boolean): PaymentTypeDto.PaymentTypeListResponseDto =
        dbQuery {
            val paymentTypes = paymentTypeRepository.getAllPaymentTypes(includeInactive)
            logger.debug("getAllPaymentTypes: retrieved {} payment types", paymentTypes.size)
            paymentTypes.toListResponse()
        }

    override suspend fun getPaymentTypeById(id: Long): PaymentTypeDto.PaymentTypeResponseDto =
        dbQuery {
            val paymentType = paymentTypeRepository.getPaymentTypeById(id) ?: throw paymentTypeNotFound(id)
            paymentType.toResponse()
        }

    override suspend fun getPaymentTypeByName(name: String): PaymentTypeDto.PaymentTypeResponseDto =
        dbQuery {
            val paymentType =
                paymentTypeRepository.getPaymentTypeByName(name)
                    ?: throw paymentTypeNotFoundByName(name)
            paymentType.toResponse()
        }

    override suspend fun createPaymentType(
        employeeId: String,
        request: PaymentTypeDto.CreatePaymentTypeRequestDto,
    ): PaymentTypeDto.PaymentTypeResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createPaymentType(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PAYMENT_TYPE,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Created payment type '${dto.name}'")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                if (paymentTypeRepository.existsByName(request.name)) {
                    throw paymentTypeNameTaken(request.name)
                }

                val paymentType =
                    paymentTypeRepository.createPaymentType(
                        name = request.name,
                        en = request.en,
                        ch = request.ch,
                        isCash = request.isCash,
                        isActive = request.isActive,
                        createsReceivable = request.createsReceivable,
                    )

                val responseDto = paymentType.toResponse()
                NotificationService.notifyDataChange(
                    ChangeEntityType.PAYMENT_TYPE,
                    DataChangeOperation.CREATED,
                    paymentType.id.value,
                    excludeEmployeeId = employeeId,
                )
                responseDto
            }
        }

    override suspend fun updatePaymentType(
        employeeId: String,
        id: Long,
        request: PaymentTypeDto.UpdatePaymentTypeRequestDto,
    ): PaymentTypeDto.PaymentTypeResponseDto =
        ExceptionConverter.catchingThrows(
            context = "updatePaymentType(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PAYMENT_TYPE,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Updated payment type '${dto.name}'")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                if (!request.hasUpdates()) {
                    throw validationFailed("At least one field to update is required")
                }

                val paymentType =
                    paymentTypeRepository.updatePaymentType(
                        id = id,
                        name = request.name,
                        en = request.en,
                        ch = request.ch,
                        isCash = request.isCash,
                        isActive = request.isActive,
                        createsReceivable = request.createsReceivable,
                    ) ?: throw paymentTypeNotFound(id)

                val responseDto = paymentType.toResponse()
                NotificationService.notifyDataChange(
                    ChangeEntityType.PAYMENT_TYPE,
                    DataChangeOperation.UPDATED,
                    id,
                    excludeEmployeeId = employeeId,
                )
                responseDto
            }
        }

    override suspend fun deletePaymentType(
        employeeId: String,
        id: Long,
    ) {
        ExceptionConverter.catchingThrows(
            context = "deletePaymentType(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig<Unit>(
                    logType = ChangeEntityType.PAYMENT_TYPE,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _ ->
                        EntryLogData(id.toString(), "PaymentType#$id", "Deleted payment type $id")
                    },
                ),
        ) {
            dbQuery {
                // Checked here so an unknown id answers 404; the repository's NoSuchElementException
                // reached StatusPages unmapped and became a 500 (QA admin19 NOTE-b).
                paymentTypeRepository.getPaymentTypeById(id) ?: throw paymentTypeNotFound(id)
                paymentTypeRepository.deletePaymentType(id)
                NotificationService.notifyDataChange(
                    ChangeEntityType.PAYMENT_TYPE,
                    DataChangeOperation.DELETED,
                    id,
                    excludeEmployeeId = employeeId,
                )
            }
        }
    }

    override suspend fun getActivePaymentTypes(): List<PaymentTypeDto.PaymentTypeSummaryDto> =
        dbQuery {
            val activePaymentTypes = paymentTypeRepository.getActivePaymentTypes()
            logger.debug("getActivePaymentTypes: retrieved {} active payment types", activePaymentTypes.size)
            activePaymentTypes.toSummaryList()
        }

    override suspend fun isNameAvailable(
        name: String,
        excludeId: Long?,
    ): Boolean =
        dbQuery {
            !paymentTypeRepository.existsByName(name, excludeId)
        }

    override suspend fun getPaymentTypeStatistics(): PaymentTypeDto.PaymentTypeStatisticsDto =
        dbQuery {
            val totalCount = paymentTypeRepository.countPaymentTypes(activeOnly = false)
            val activeCount = paymentTypeRepository.countPaymentTypes(activeOnly = true)
            val inactiveCount = totalCount - activeCount
            PaymentTypeDto.PaymentTypeStatisticsDto(
                totalCount = totalCount,
                activeCount = activeCount,
                inactiveCount = inactiveCount,
                activePercentage = if (totalCount > 0) ((activeCount * 100.0) / totalCount) else 0.0,
            )
        }

    override suspend fun getPaymentTypesUpdatedAfter(lastSyncTime: String): List<PaymentTypeDto.PaymentTypeResponseDto> =
        dbQuery {
            paymentTypeRepository.getPaymentTypesUpdatedAfter(lastSyncTime).map { it.toResponse() }
        }

    // --- PosException helpers ---

    private fun paymentTypeNotFound(id: Long) =
        PosException(
            PosErrorCode.PAYMENT_TYPE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "PaymentType with id=$id not found",
        )

    private fun paymentTypeNotFoundByName(name: String) =
        PosException(
            PosErrorCode.PAYMENT_TYPE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "PaymentType with name='$name' not found",
        )

    private fun paymentTypeNameTaken(name: String) =
        PosException(
            PosErrorCode.PAYMENT_TYPE_NAME_ALREADY_TAKEN,
            HttpStatusCode.Conflict,
            "PaymentType name '$name' already taken",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
