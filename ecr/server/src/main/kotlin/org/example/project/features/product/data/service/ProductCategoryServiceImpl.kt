/**
 * Implementation of ProductCategoryService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.product.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.SystemRecords
import org.example.project.core.websocket.NotificationService
import org.example.project.features.product.domain.repository.ProductCategoryRepository
import org.example.project.features.product.domain.service.ProductCategoryService
import org.example.project.features.product.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.SystemRecordGuard
import org.example.project.utils.builder.CsvReader
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.toBigDecimal
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory

class ProductCategoryServiceImpl(
    private val repository: ProductCategoryRepository,
) : ProductCategoryService {
    private val logger = LoggerFactory.getLogger(ProductCategoryServiceImpl::class.java)

    override suspend fun getAllCategories(includeInactive: Boolean): List<ProductCategoryDto.ProductCategoryResponseDto> =
        dbQuery {
            repository.getAllCategories(includeInactive).map { it.toResponse() }
        }

    override suspend fun getCategoriesByVisibility(visibilityScopes: List<String>): List<ProductCategoryDto.ProductCategoryResponseDto> {
        logger.info("getCategoriesByVisibility: scopes=$visibilityScopes")
        return dbQuery {
            repository.getCategoriesByVisibility(visibilityScopes).map { it.toResponse() }
        }
    }

    override suspend fun getCategoryById(id: Int): ProductCategoryDto.ProductCategoryResponseDto =
        dbQuery {
            val category =
                repository.getCategoryById(id)
                    ?: throw categoryNotFound(id.toLong())
            category.toResponse()
        }

    override suspend fun getActiveCategories(): List<ProductCategoryDto.ProductCategoryResponseDto> =
        dbQuery {
            repository.getActiveCategories().map { it.toResponse() }
        }

    override suspend fun createCategory(
        employeeId: String,
        request: ProductCategoryDto.CreateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createCategory(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.CATEGORY,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Created category '${dto.name}'")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                val category =
                    repository.createCategory(
                        name = request.name.trim(),
                        description = request.description?.trim(),
                        isActive = request.isActive,
                        visibility = request.visibility,
                    )

                val response = category.toResponse()
                NotificationService.notifyDataChange(
                    ChangeEntityType.CATEGORY,
                    DataChangeOperation.CREATED,
                    category.id.value.toLong(),
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun updateCategory(
        employeeId: String,
        id: Int,
        request: ProductCategoryDto.UpdateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto =
        ExceptionConverter.catchingThrows(
            context = "updateCategory(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.CATEGORY,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Updated category '${dto.name}'")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                requireNotSystemCategory(id, "changed")
                val category =
                    repository.updateCategory(
                        id = id,
                        name = request.name.trim(),
                        enName = request.en,
                        chName = request.ch,
                        description = request.description?.trim(),
                        isActive = request.isActive,
                        visibility = request.visibility,
                    ) ?: throw categoryNotFound(id.toLong())

                val response = category.toResponse()
                NotificationService.notifyDataChange(
                    ChangeEntityType.CATEGORY,
                    DataChangeOperation.UPDATED,
                    id.toLong(),
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun deleteCategory(
        employeeId: String,
        id: Int,
    ) {
        ExceptionConverter.catchingThrows(
            context = "deleteCategory(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.CATEGORY,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _: Unit ->
                        EntryLogData(id.toString(), "Deleted category", "Deleted category #$id")
                    },
                ),
        ) {
            dbQuery {
                requireNotSystemCategory(id, "deleted")
                requireNoActiveProducts(id)
                repository.deleteCategory(id)
                NotificationService.notifyDataChange(
                    ChangeEntityType.CATEGORY,
                    DataChangeOperation.DELETED,
                    id.toLong(),
                    excludeEmployeeId = employeeId,
                )
            }
        }
    }

    override suspend fun getCategoriesUpdatedAfter(lastSyncTime: String): List<ProductCategoryDto.ProductCategoryResponseDto> {
        val parsedDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("lastSyncTime must be ISO datetime (e.g., 2024-12-01T10:30:00)")
            }
        return dbQuery {
            repository
                .getCategoriesUpdatedAfter(parsedDateTime)
                .filter { it.name != "VOUCHER" }
                .map { it.toResponse() }
        }
    }

    override suspend fun updateCategoryPosition(
        categoryId: Long,
        newSortPosition: Double,
        employeeId: String,
    ) {
        dbQuery {
            requireNotSystemCategory(categoryId.toInt(), "moved")
            repository.updateCategorySortPosition(categoryId, newSortPosition)
                ?: throw categoryNotFound(categoryId)
            NotificationService.notifyDataChange(
                ChangeEntityType.CATEGORY,
                DataChangeOperation.UPDATED,
                categoryId,
                excludeEmployeeId = employeeId,
            )
        }
    }

    override suspend fun importCategoriesCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto {
        logger.info("importCategoriesCsv: starting CSV import for categories")

        val csvData = CsvReader.parseCsvBytes(csvContent, delimiter = ';', hasHeader = true)
        if (csvData.isEmpty()) throw validationFailed("CSV file is empty or could not be parsed")

        var successCount = 0
        var errorCount = 0
        var systemSkipped = 0
        val errors = mutableListOf<String>()

        csvData.forEachIndexed { index, rowMap ->
            val rowNumber = index + 2
            try {
                val skippedBefore = systemSkipped
                importCategoryRow(rowMap, overwrite) { systemSkipped++ }?.let { error ->
                    errors.add("Row $rowNumber: $error")
                    errorCount++
                } ?: run { if (systemSkipped == skippedBefore) successCount++ }
            } catch (e: Exception) {
                logger.error("importCategoriesCsv: error processing row $rowNumber: ${e.message}", e)
                errors.add("Row $rowNumber: ${e.message ?: "Unknown error"}")
                errorCount++
            }
        }

        // Reset PostgreSQL sequence after CSV import to prevent duplicate key violations
        try {
            dbQuery {
                TransactionManager.current().exec(
                    "SELECT setval('product_category_id_seq', COALESCE((SELECT MAX(id) FROM product_category), 1), true)",
                )
            }
            logger.info("importCategoriesCsv: reset sequence for CATEGORY after CSV import")
        } catch (e: Exception) {
            logger.warn("importCategoriesCsv: failed to reset sequence for CATEGORY: ${e.message}")
        }

        val skippedCount = csvData.size - successCount - errorCount
        return InitialSetupDto.CsvImportResultDto(
            totalRows = csvData.size,
            successCount = successCount,
            errorCount = errorCount,
            skippedCount = skippedCount,
            errors = errors,
            dataType = "CATEGORY",
            success = errorCount == 0,
            message = buildImportMessage(successCount, errorCount, skippedCount),
        )
    }

    /** [onSystemSkip] is called when an overwrite row targets a system category, which is left untouched and counts as skipped. */
    private suspend fun importCategoryRow(
        rowMap: Map<String, String>,
        overwrite: Boolean,
        onSystemSkip: () -> Unit,
    ): String? {
        return try {
            val id = rowMap["id"]?.toIntOrNull() ?: return "Missing 'id' column"
            val name = rowMap["name"] ?: return "Missing 'name' column"
            val enName = rowMap["en_name"]?.takeIf { it.isNotBlank() }
            val chName = rowMap["ch_name"]?.takeIf { it.isNotBlank() }
            val description = rowMap["description"]?.takeIf { it.isNotBlank() }
            val sortOrder = rowMap["sort_position"]?.toDoubleOrNull()
            val isActive = rowMap["is_active"]?.toBoolean() ?: true

            dbQuery {
                if (overwrite) {
                    val existing = repository.getCategoryById(id)
                    if (existing != null && SystemRecords.isSystemCategory(existing.name)) {
                        logger.info("importCategoryRow: skipping system category id=$id")
                        onSystemSkip()
                        return@dbQuery
                    }
                    repository.updateCategory(
                        id = id,
                        name = name,
                        enName = enName,
                        chName = chName,
                        description = description,
                        isActive = isActive,
                        sortOrder = sortOrder?.let { toBigDecimal(it) },
                    )
                } else {
                    repository.createCategory(
                        id = id,
                        name = name,
                        enName = enName,
                        chName = chName,
                        description = description,
                        isActive = isActive,
                        sortOrder = sortOrder?.let { toBigDecimal(it) },
                    )
                }
                Unit
            }
            null
        } catch (e: IllegalStateException) {
            if (e.message?.contains("Duplicate") == true) {
                logger.info("importCategoryRow: category already exists, skipping: ${e.message}")
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
        return if (parts.isEmpty()) "No categories processed" else "CATEGORY: ${parts.joinToString(", ")}"
    }

    /**
     * Refuses to rename, deactivate or delete the PFAND and VOUCHER categories ([SystemRecords]).
     * Deposits and voucher sales resolve them by name; the Pfand type service and the voucher
     * bootstrap provision them through the repository, not through this service. An unknown id still
     * answers 404.
     */
    private fun requireNotSystemCategory(
        id: Int,
        action: String,
    ) {
        val existing = repository.getCategoryById(id) ?: throw categoryNotFound(id.toLong())
        if (SystemRecords.isSystemCategory(existing.name)) {
            throw SystemRecordGuard.protectedRecord("Category", existing.name, action)
        }
    }

    /**
     * Refuses to delete a category that still holds active products (QA MD-02).
     *
     * The delete is a soft delete: it only flips the category's `is_active`. Its products stayed
     * active, so they disappeared from every till grid (the grid is keyed on active categories)
     * while still counting as sellable everywhere else — orphaned, not deactivated. The operator
     * must move or deactivate them first; the message names how many. adminApp disables the
     * delete for the same condition. An unknown id still answers 404, not 409.
     */
    private fun requireNoActiveProducts(id: Int) {
        repository.getCategoryById(id) ?: throw categoryNotFound(id.toLong())
        val activeProducts = repository.countActiveProducts(id)
        if (activeProducts > 0) {
            throw PosException(
                PosErrorCode.DELETE_HAS_REFERENCES,
                HttpStatusCode.Conflict,
                "Category $id still contains $activeProducts active product(s); move or deactivate them before deleting the category",
            )
        }
    }

    private fun categoryNotFound(id: Long) =
        PosException(
            PosErrorCode.CATEGORY_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Category $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
