/**
 * Implementation of ProductExtrasAssignmentService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.product.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.repository.ProductExtrasAssignmentRepository
import org.example.project.features.product.domain.service.ProductExtrasAssignmentService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

class ProductExtrasAssignmentServiceImpl(
    private val repository: ProductExtrasAssignmentRepository,
) : ProductExtrasAssignmentService {
    private val logger = LoggerFactory.getLogger(ProductExtrasAssignmentServiceImpl::class.java)

    override suspend fun getAllAssignments(includeInactive: Boolean?): ProductExtrasAssignmentDto.ListResponseDto =
        dbQuery {
            val assignments = repository.getAll(includeInactive)
            val items =
                assignments.map { assignment ->
                    ProductExtrasAssignmentDto.ResponseDto(
                        productId = assignment.productId,
                        extrasId = assignment.extrasId,
                        updatedAt = "",
                    )
                }
            ProductExtrasAssignmentDto.ListResponseDto(
                items = items,
                totalCount = items.size,
            )
        }

    override suspend fun getAssignmentsForProduct(productId: Long): List<ProductExtrasAssignmentDto.ResponseDto> =
        dbQuery {
            repository.getByProductId(productId).map { assignment ->
                ProductExtrasAssignmentDto.ResponseDto(
                    productId = assignment.productId,
                    extrasId = assignment.extrasId,
                    updatedAt = "",
                )
            }
        }

    override suspend fun assignExtrasToProduct(
        employeeId: String,
        productId: Long,
        extrasIds: List<Int>,
    ): List<ProductExtrasAssignmentDto.ResponseDto> {
        logger.info("assignExtrasToProduct: productId={}, extrasCount={}, employeeId={}", productId, extrasIds.size, employeeId)
        return ExceptionConverter.catchingThrows(
            context = "assignExtrasToProduct(productId=$productId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { _ ->
                        EntryLogData(
                            productId.toString(),
                            extrasIds.joinToString(";"),
                            "Assigned ${extrasIds.size} extras to product #$productId",
                        )
                    },
                ),
        ) {
            dbQuery {
                val created =
                    extrasIds.mapNotNull { extrasId ->
                        repository.createIfNotExists(productId, extrasId)
                    }
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    DataChangeOperation.CREATED,
                    entityId = productId.toString(),
                    excludeEmployeeId = employeeId,
                )
                created.map { assignment ->
                    ProductExtrasAssignmentDto.ResponseDto(
                        productId = assignment.productId,
                        extrasId = assignment.extrasId,
                        updatedAt = "",
                    )
                }
            }
        }
    }

    override suspend fun bulkAssignExtrasToCategory(
        employeeId: String,
        categoryId: Int,
        extrasIds: List<Int>,
    ): ProductExtrasAssignmentDto.BulkAssignResultDto {
        logger.info("bulkAssignExtrasToCategory: categoryId={}, extrasCount={}, employeeId={}", categoryId, extrasIds.size, employeeId)
        return ExceptionConverter.catchingThrows(
            context = "bulkAssignExtrasToCategory(categoryId=$categoryId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { _ ->
                        EntryLogData(
                            categoryId.toString(),
                            extrasIds.joinToString(";"),
                            "Bulk assigned ${extrasIds.size} extras to all products in category #$categoryId",
                        )
                    },
                ),
        ) {
            dbQuery {
                val productIds =
                    ProductTable
                        .selectAll()
                        .where { ProductTable.categoryId eq categoryId }
                        .map { it[ProductTable.id].value }

                var assigned = 0
                var skipped = 0

                for (productId in productIds) {
                    for (extrasId in extrasIds) {
                        val result = repository.createIfNotExists(productId, extrasId)
                        if (result != null) assigned++ else skipped++
                    }
                }

                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    DataChangeOperation.CREATED,
                    entityId = categoryId.toString(),
                    excludeEmployeeId = employeeId,
                )
                ProductExtrasAssignmentDto.BulkAssignResultDto(
                    assigned = assigned,
                    skipped = skipped,
                    totalProducts = productIds.size,
                )
            }
        }
    }

    override suspend fun removeExtrasFromProduct(
        employeeId: String,
        productId: Long,
        extrasId: Int,
    ) {
        logger.info("removeExtrasFromProduct: productId={}, extrasId={}, employeeId={}", productId, extrasId, employeeId)
        ExceptionConverter.catchingThrows(
            context = "removeExtrasFromProduct(productId=$productId, extrasId=$extrasId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _: Unit ->
                        EntryLogData(
                            productId.toString(),
                            extrasId.toString(),
                            "Removed extras #$extrasId from product #$productId",
                        )
                    },
                ),
        ) {
            dbQuery {
                if (!repository.delete(productId, extrasId)) throw assignmentNotFound(productId, extrasId)
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    DataChangeOperation.DELETED,
                    entityId = productId.toString(),
                    excludeEmployeeId = employeeId,
                )
            }
        }
    }

    override suspend fun bulkRemoveExtrasFromCategory(
        employeeId: String,
        categoryId: Int,
        extrasId: Int,
    ) {
        logger.info("bulkRemoveExtrasFromCategory: categoryId={}, extrasId={}, employeeId={}", categoryId, extrasId, employeeId)
        ExceptionConverter.catchingThrows(
            context = "bulkRemoveExtrasFromCategory(categoryId=$categoryId, extrasId=$extrasId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _: Unit ->
                        EntryLogData(
                            categoryId.toString(),
                            extrasId.toString(),
                            "Removed extras #$extrasId from all products in category #$categoryId",
                        )
                    },
                ),
        ) {
            dbQuery {
                val productIds =
                    ProductTable
                        .selectAll()
                        .where { ProductTable.categoryId eq categoryId }
                        .map { it[ProductTable.id].value }

                repository.deleteByProductIds(productIds, extrasId)
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_EXTRAS_ASSIGNMENT,
                    DataChangeOperation.DELETED,
                    entityId = categoryId.toString(),
                    excludeEmployeeId = employeeId,
                )
            }
        }
    }

    override suspend fun getAssignmentsUpdatedAfter(lastSyncTime: String): ProductExtrasAssignmentDto.ListResponseDto {
        val localDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("lastSyncTime must be ISO-8601 datetime")
            }
        return dbQuery {
            val assignments = repository.getUpdatedAfter(localDateTime)
            val items =
                assignments.map { assignment ->
                    ProductExtrasAssignmentDto.ResponseDto(
                        productId = assignment.productId,
                        extrasId = assignment.extrasId,
                        updatedAt = "",
                    )
                }
            ProductExtrasAssignmentDto.ListResponseDto(
                items = items,
                totalCount = items.size,
            )
        }
    }

    // emu20 FAIL-A: an unknown pair used to escape as NoSuchElementException → 500 + ERROR log.
    private fun assignmentNotFound(
        productId: Long,
        extrasId: Int,
    ) = PosException(
        PosErrorCode.RESOURCE_NOT_FOUND,
        HttpStatusCode.NotFound,
        "Extra #$extrasId is not assigned to product #$productId",
    )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
