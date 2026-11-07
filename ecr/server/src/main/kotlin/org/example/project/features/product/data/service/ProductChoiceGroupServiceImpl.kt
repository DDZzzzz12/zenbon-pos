/**
 * Implementation of ProductChoiceGroupService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Mirrors the throws pattern of ProductExtrasServiceImpl.
 */
package org.example.project.features.product.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.database.tables.ProductChoiceGroupTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.ChoiceGroup
import org.example.project.features.product.domain.models.ProductExtras
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.features.product.domain.repository.ProductChoiceGroupRepository
import org.example.project.features.product.domain.service.ProductChoiceGroupService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

class ProductChoiceGroupServiceImpl(
    private val productChoiceGroupRepository: ProductChoiceGroupRepository,
    private val choiceGroupRepository: ChoiceGroupRepository,
    private val configManager: IShopConfigurationManager,
) : ProductChoiceGroupService {
    override suspend fun getForProduct(productId: Long): ProductChoiceGroupDto.ProductGroupsResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val groupIds = productChoiceGroupRepository.getGroupIdsForProduct(productId)
            val groups =
                groupIds.mapNotNull { gid ->
                    choiceGroupRepository.getById(gid)?.takeIf { it.isActive }?.let { mapGroup(it, currency) }
                }
            ProductChoiceGroupDto.ProductGroupsResponseDto(productId = productId, groups = groups)
        }
    }

    override suspend fun getAll(): ProductChoiceGroupDto.ListResponseDto =
        dbQuery {
            val items = productChoiceGroupRepository.getAll().map { it.toResponse() }
            ProductChoiceGroupDto.ListResponseDto(items = items, totalCount = items.size)
        }

    override suspend fun attach(
        employeeId: String,
        request: ProductChoiceGroupDto.AttachRequestDto,
    ): ProductChoiceGroupDto.ListResponseDto =
        ExceptionConverter.catchingThrows(
            context = "attachProductChoiceGroups(employeeId=$employeeId, productId=${request.productId})",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_CHOICE_GROUP,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(
                            request.productId.toString(),
                            "Product ${request.productId}",
                            "Attached ${dto.totalCount} choice group(s) to product ${request.productId}",
                        )
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                productChoiceGroupRepository.replaceForProduct(request.productId, request.groupIds)

                val items =
                    productChoiceGroupRepository
                        .getAll()
                        .filter { it[ProductChoiceGroupTable.productId].value == request.productId }
                        .map { it.toResponse() }
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_CHOICE_GROUP,
                    DataChangeOperation.UPDATED,
                    request.productId,
                    excludeEmployeeId = employeeId,
                )
                ProductChoiceGroupDto.ListResponseDto(items = items, totalCount = items.size)
            }
        }

    override suspend fun bulkAttachToCategory(
        employeeId: String,
        categoryId: Int,
        groupIds: List<Int>,
    ): ProductChoiceGroupDto.BulkAttachResultDto =
        ExceptionConverter.catchingThrows(
            context = "bulkAttachChoiceGroupsToCategory(categoryId=$categoryId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_CHOICE_GROUP,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { _ ->
                        EntryLogData(
                            categoryId.toString(),
                            groupIds.joinToString(";"),
                            "Bulk attached ${groupIds.size} choice group(s) to all products in category #$categoryId",
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
                    for (groupId in groupIds) {
                        if (productChoiceGroupRepository.attachIfNotExists(productId, groupId)) assigned++ else skipped++
                    }
                }

                // Batch notification: null entityId tells the client to clear+resync all
                // product-choice-group links. Do NOT pass categoryId here (client would treat it as a productId).
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_CHOICE_GROUP,
                    DataChangeOperation.CREATED,
                    entityId = null,
                    excludeEmployeeId = employeeId,
                )
                ProductChoiceGroupDto.BulkAttachResultDto(
                    assigned = assigned,
                    skipped = skipped,
                    totalProducts = productIds.size,
                )
            }
        }

    override suspend fun bulkRemoveFromCategory(
        employeeId: String,
        categoryId: Int,
        groupId: Int,
    ) {
        ExceptionConverter.catchingThrows(
            context = "bulkRemoveChoiceGroupFromCategory(categoryId=$categoryId, groupId=$groupId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT_CHOICE_GROUP,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _: Unit ->
                        EntryLogData(
                            categoryId.toString(),
                            groupId.toString(),
                            "Removed choice group #$groupId from all products in category #$categoryId",
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

                for (productId in productIds) {
                    productChoiceGroupRepository.delete(productId, groupId)
                }

                // Batch notification: null entityId → client clears+resyncs all links.
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT_CHOICE_GROUP,
                    DataChangeOperation.DELETED,
                    entityId = null,
                    excludeEmployeeId = employeeId,
                )
            }
        }
    }

    override suspend fun getUpdatedAfter(lastSyncTime: String): ProductChoiceGroupDto.ListResponseDto {
        val parsedDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("lastSyncTime must be ISO datetime (e.g., 2024-12-01T10:30:00)")
            }
        return dbQuery {
            val items = productChoiceGroupRepository.getUpdatedAfter(parsedDateTime).map { it.toResponse() }
            ProductChoiceGroupDto.ListResponseDto(items = items, totalCount = items.size)
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toResponse(): ProductChoiceGroupDto.ResponseDto =
        ProductChoiceGroupDto.ResponseDto(
            productId = this[ProductChoiceGroupTable.productId].value,
            groupId = this[ProductChoiceGroupTable.groupId].value,
            sortPosition = this[ProductChoiceGroupTable.sortPosition],
            updatedAt = this[ProductChoiceGroupTable.updatedAt].toString(),
        )

    /**
     * Maps a ChoiceGroup entity to its response DTO, resolving option placements.
     * Must run inside dbQuery. [currency] is resolved once per request by the caller.
     */
    private fun mapGroup(
        group: ChoiceGroup,
        currency: String,
    ): ChoiceGroupDto.ChoiceGroupResponseDto {
        val options =
            choiceGroupRepository.getOptions(group.id.value).mapNotNull { row ->
                val optionId = row[ChoiceGroupOptionTable.optionId].value
                // A deleted (soft, is_active=false) extra is no longer offered: it leaves the
                // group's option list here, in the admin editor and in the till configurator
                // alike (admin21 NOTE-c21). The placement row is kept until the group is saved
                // again, so reactivating the extra before that brings the option back.
                val extra = ProductExtras.findById(optionId)?.takeIf { it.isActive } ?: return@mapNotNull null
                ChoiceGroupDto.OptionResponseDto(
                    optionId = optionId,
                    name = extra.name,
                    en = extra.enName,
                    ch = extra.chName,
                    priceDelta = row[ChoiceGroupOptionTable.priceDelta].toPlainString(),
                    sortPosition = row[ChoiceGroupOptionTable.sortPosition],
                    isDefault = row[ChoiceGroupOptionTable.isDefault],
                    linkedProductId = extra.linkedProductId?.value,
                )
            }
        return ChoiceGroupDto.ChoiceGroupResponseDto(
            id = group.id.value,
            name = group.name,
            en = group.enName,
            ch = group.chName,
            minSelections = group.minSelections,
            maxSelections = group.maxSelections,
            isActive = group.isActive,
            updatedAt = group.updatedAt.toString(),
            options = options,
            currency = currency,
        )
    }

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
