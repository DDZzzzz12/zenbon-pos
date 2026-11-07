/**
 * Implementation of ChoiceGroupService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Mirrors ProductExtrasServiceImpl (throws pattern).
 */
package org.example.project.features.product.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.domain.models.ChoiceGroup
import org.example.project.features.product.domain.models.ProductExtras
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.features.product.domain.service.ChoiceGroupService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData

class ChoiceGroupServiceImpl(
    private val repository: ChoiceGroupRepository,
    private val configManager: IShopConfigurationManager,
) : ChoiceGroupService {
    override suspend fun getAll(includeInactive: Boolean): ChoiceGroupDto.ListResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val groups = repository.getAll(includeInactive).map { mapGroup(it, currency) }
            ChoiceGroupDto.ListResponseDto(items = groups, totalCount = groups.size)
        }
    }

    override suspend fun getById(id: Int): ChoiceGroupDto.ChoiceGroupResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val group = repository.getById(id) ?: throw groupNotFound(id)
            mapGroup(group, currency)
        }
    }

    override suspend fun create(
        employeeId: String,
        request: ChoiceGroupDto.CreateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createChoiceGroup(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.CHOICE_GROUP,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Created choice group '${dto.name}'")
                    },
                ),
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                val group =
                    repository.create(
                        name = request.name.trim(),
                        enName = request.en,
                        chName = request.ch,
                        minSelections = request.minSelections,
                        maxSelections = request.maxSelections,
                        isActive = request.isActive,
                    )
                repository.replaceOptions(
                    group.id.value,
                    request.options.map {
                        ChoiceGroupRepository.OptionRow(it.optionId, it.priceDelta.toBigDecimal(), it.sortPosition, it.isDefault)
                    },
                )

                val response = mapGroup(group, currency)
                NotificationService.notifyDataChange(
                    ChangeEntityType.CHOICE_GROUP,
                    DataChangeOperation.CREATED,
                    group.id.value.toLong(),
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun update(
        employeeId: String,
        id: Int,
        request: ChoiceGroupDto.UpdateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto =
        ExceptionConverter.catchingThrows(
            context = "updateChoiceGroup(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.CHOICE_GROUP,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Updated choice group '${dto.name}'")
                    },
                ),
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                val group =
                    repository.update(
                        id = id,
                        name = request.name.trim(),
                        enName = request.en,
                        chName = request.ch,
                        minSelections = request.minSelections,
                        maxSelections = request.maxSelections,
                        isActive = request.isActive,
                    ) ?: throw groupNotFound(id)
                repository.replaceOptions(
                    group.id.value,
                    request.options.map {
                        ChoiceGroupRepository.OptionRow(it.optionId, it.priceDelta.toBigDecimal(), it.sortPosition, it.isDefault)
                    },
                )

                val response = mapGroup(group, currency)
                NotificationService.notifyDataChange(
                    ChangeEntityType.CHOICE_GROUP,
                    DataChangeOperation.UPDATED,
                    id.toLong(),
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun delete(
        employeeId: String,
        id: Int,
    ): String =
        ExceptionConverter.catchingThrows(
            context = "deleteChoiceGroup(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.CHOICE_GROUP,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { name ->
                        EntryLogData(id.toString(), name, "Deleted choice group '$name'")
                    },
                ),
        ) {
            dbQuery {
                repository.getById(id) ?: throw groupNotFound(id)
                val name = repository.softDelete(id)
                NotificationService.notifyDataChange(
                    ChangeEntityType.CHOICE_GROUP,
                    DataChangeOperation.DELETED,
                    id.toLong(),
                    excludeEmployeeId = employeeId,
                )
                name
            }
        }

    override suspend fun getUpdatedAfter(lastSyncTime: String): ChoiceGroupDto.ListResponseDto {
        val parsedDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("lastSyncTime must be ISO datetime (e.g., 2024-12-01T10:30:00)")
            }
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val groups = repository.getUpdatedAfter(parsedDateTime).map { mapGroup(it, currency) }
            ChoiceGroupDto.ListResponseDto(items = groups, totalCount = groups.size)
        }
    }

    /**
     * Maps a ChoiceGroup entity to its response DTO, resolving option placements.
     * Must run inside dbQuery. [currency] is resolved once per request by the caller.
     */
    private fun mapGroup(
        group: ChoiceGroup,
        currency: String,
    ): ChoiceGroupDto.ChoiceGroupResponseDto {
        val options =
            repository.getOptions(group.id.value).mapNotNull { row ->
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

    private fun groupNotFound(id: Int) =
        PosException(
            PosErrorCode.CHOICE_GROUP_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Choice group $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
