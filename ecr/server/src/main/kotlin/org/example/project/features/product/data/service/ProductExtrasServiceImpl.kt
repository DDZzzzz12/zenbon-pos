/**
 * Implementation of ProductExtrasService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.product.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.features.product.domain.repository.ProductExtrasRepository
import org.example.project.features.product.domain.service.ProductExtrasService
import org.example.project.features.product.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.builder.CsvReader
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory

class ProductExtrasServiceImpl(
    private val repository: ProductExtrasRepository,
    private val configManager: IShopConfigurationManager,
    private val choiceGroupRepository: ChoiceGroupRepository,
) : ProductExtrasService {
    private val logger = LoggerFactory.getLogger(ProductExtrasServiceImpl::class.java)

    override suspend fun getAllExtras(includeInactive: Boolean): List<ProductExtrasDto.ProductExtrasResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getAllExtras(includeInactive).map { it.toResponse(currency) }
        }
    }

    override suspend fun getExtraById(id: Int): ProductExtrasDto.ProductExtrasResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val extra = repository.getExtraById(id) ?: throw extraNotFound(id.toLong())
            extra.toResponse(currency)
        }
    }

    override suspend fun getActiveExtras(): List<ProductExtrasDto.ProductExtrasResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getActiveExtras().map { it.toResponse(currency) }
        }
    }

    override suspend fun getExtrasByCategory(categoryId: Int): List<ProductExtrasDto.ProductExtrasResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getExtrasByCategory(categoryId).map { it.toResponse(currency) }
        }
    }

    override suspend fun createExtra(
        employeeId: String,
        request: ProductExtrasDto.CreateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createExtra(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_EXTRA,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Created product extra '${dto.name}'")
                    },
                ),
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                val price = requireValidPrice(request.price)
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                val extra =
                    repository.createExtra(
                        name = request.name.trim(),
                        price = price,
                        isActive = request.isActive,
                    )

                val response = extra.toResponse(currency)
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_EXTRA,
                    DataChangeOperation.CREATED,
                    extra.id.value.toLong(),
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun updateExtra(
        employeeId: String,
        id: Int,
        request: ProductExtrasDto.UpdateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto =
        ExceptionConverter.catchingThrows(
            context = "updateExtra(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_EXTRA,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Updated product extra '${dto.name}'")
                    },
                ),
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                val price = requireValidPrice(request.price)
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }
                val existing = repository.getExtraById(id) ?: throw extraNotFound(id.toLong())
                val wasActive = existing.isActive
                if (wasActive && !request.isActive) requireNoRequiredGroupNeeds(id, existing.name)

                val extra =
                    repository.updateExtra(
                        id = id,
                        name = request.name.trim(),
                        price = price,
                        isActive = request.isActive,
                        enName = request.en,
                        chName = request.ch,
                    ) ?: throw extraNotFound(id.toLong())

                val response = extra.toResponse(currency)
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_EXTRA,
                    DataChangeOperation.UPDATED,
                    id.toLong(),
                    excludeEmployeeId = employeeId,
                )
                if (wasActive != request.isActive) refreshGroupsPlacing(id, employeeId)
                response
            }
        }

    override suspend fun deleteExtra(
        employeeId: String,
        id: Int,
    ): String =
        ExceptionConverter.catchingThrows(
            context = "deleteExtra(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_EXTRA,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { name ->
                        EntryLogData(id.toString(), name, "Deleted product extra '$name'")
                    },
                ),
        ) {
            dbQuery {
                val existing = repository.getExtraById(id) ?: throw extraNotFound(id.toLong())
                if (existing.isActive) requireNoRequiredGroupNeeds(id, existing.name)
                val name = repository.deleteExtra(id)
                refreshGroupsPlacing(id, employeeId)

                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_EXTRA,
                    DataChangeOperation.DELETED,
                    id.toLong(),
                    excludeEmployeeId = employeeId,
                )
                name
            }
        }

    override suspend fun getExtrasUpdatedAfter(lastSyncTime: String): List<ProductExtrasDto.ProductExtrasResponseDto> {
        val parsedDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("lastSyncTime must be ISO datetime (e.g., 2024-12-01T10:30:00)")
            }
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getExtrasUpdatedAfter(parsedDateTime).map { it.toResponse(currency) }
        }
    }

    override suspend fun importExtrasCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto {
        logger.info("importExtrasCsv: starting CSV import for product extras")

        val csvData = CsvReader.parseCsvBytes(csvContent, delimiter = ';', hasHeader = true)
        if (csvData.isEmpty()) throw validationFailed("CSV file is empty or could not be parsed")

        var successCount = 0
        var errorCount = 0
        val errors = mutableListOf<String>()

        csvData.forEachIndexed { index, rowMap ->
            val rowNumber = index + 2
            try {
                importExtrasRow(rowMap, overwrite)?.let { error ->
                    errors.add("Row $rowNumber: $error")
                    errorCount++
                } ?: run { successCount++ }
            } catch (e: Exception) {
                logger.error("importExtrasCsv: error processing row $rowNumber: ${e.message}", e)
                errors.add("Row $rowNumber: ${e.message ?: "Unknown error"}")
                errorCount++
            }
        }

        try {
            dbQuery {
                TransactionManager.current().exec(
                    "SELECT setval('product_extras_id_seq', COALESCE((SELECT MAX(id) FROM product_extras), 1), true)",
                )
            }
            logger.info("importExtrasCsv: reset sequence for EXTRAS after CSV import")
        } catch (e: Exception) {
            logger.warn("importExtrasCsv: failed to reset sequence for EXTRAS: ${e.message}")
        }

        val skippedCount = csvData.size - successCount - errorCount
        return InitialSetupDto.CsvImportResultDto(
            totalRows = csvData.size,
            successCount = successCount,
            errorCount = errorCount,
            skippedCount = skippedCount,
            errors = errors,
            dataType = "EXTRAS",
            success = errorCount == 0,
            message = buildImportMessage(successCount, errorCount, skippedCount),
        )
    }

    private suspend fun importExtrasRow(
        rowMap: Map<String, String>,
        overwrite: Boolean,
    ): String? {
        return try {
            val id = rowMap["id"]?.toIntOrNull()
            val name = rowMap["name"] ?: return "Missing 'name' column"
            val enName = rowMap["en_name"]?.takeIf { it.isNotBlank() }
            val chName = rowMap["ch_name"]?.takeIf { it.isNotBlank() }
            val price = rowMap["price"]?.toBigDecimalOrNull() ?: return "Invalid 'price' value"
            val isActive = rowMap["is_active"]?.toBoolean() ?: true

            dbQuery {
                if (overwrite) {
                    repository.updateExtra(
                        id = id ?: throw IllegalArgumentException("Missing 'id' column"),
                        name = name,
                        enName = enName,
                        chName = chName,
                        price = price,
                        isActive = isActive,
                    )
                } else {
                    repository.createExtra(
                        name = name,
                        enName = enName,
                        chName = chName,
                        price = price,
                        isActive = isActive,
                    )
                }
            }
            null
        } catch (e: IllegalStateException) {
            if (e.message?.contains("Duplicate") == true) {
                logger.info("importExtrasRow: extra already exists, skipping: ${e.message}")
                null
            } else {
                e.message
            }
        } catch (e: Exception) {
            e.message
        }
    }

    private fun buildImportMessage(
        successCount: Int,
        errorCount: Int,
        skippedCount: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (successCount > 0) parts.add("$successCount imported")
        if (skippedCount > 0) parts.add("$skippedCount skipped (duplicates)")
        if (errorCount > 0) parts.add("$errorCount failed")
        return if (parts.isEmpty()) "No extras processed" else "EXTRAS: ${parts.joinToString(", ")}"
    }

    /** Parses a wire price, refusing anything that is not a non-negative decimal with [PosErrorCode.PRICE_INVALID]. */
    private fun requireValidPrice(raw: String): java.math.BigDecimal {
        val price = raw.trim().toBigDecimalOrNull()
        if (price == null || price.signum() < 0) {
            throw PosException(PosErrorCode.PRICE_INVALID, HttpStatusCode.BadRequest, "Price must be a non-negative decimal number")
        }
        return price
    }

    /**
     * Server half of the NOTE-c21 guard: an extra that a required choice group cannot do without
     * must not stop being offered. Must run inside dbQuery.
     */
    private fun requireNoRequiredGroupNeeds(
        id: Int,
        name: String,
    ) {
        val broken = choiceGroupRepository.groupsBrokenByWithdrawingOption(id)
        if (broken.isEmpty()) return
        throw PosException(
            PosErrorCode.CHOICE_GROUP_OPTION_REQUIRED,
            HttpStatusCode.Conflict,
            "Extra '$name' is needed by required choice group(s) " +
                broken.joinToString { "'${it.name}'" } +
                ": without it they offer fewer options than their minimum",
        )
    }

    /**
     * The groups placing extra [id] now offer one option more or fewer: bump them for delta sync and
     * tell connected clients. Must run inside dbQuery.
     */
    private suspend fun refreshGroupsPlacing(
        id: Int,
        employeeId: String,
    ) {
        choiceGroupRepository.touchGroupsContainingOption(id).forEach { groupId ->
            NotificationService.notifyDataChange(
                ChangeEntityType.CHOICE_GROUP,
                DataChangeOperation.UPDATED,
                groupId.toLong(),
                excludeEmployeeId = employeeId,
            )
        }
    }

    private fun extraNotFound(id: Long) =
        PosException(
            PosErrorCode.EXTRA_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Extra $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
