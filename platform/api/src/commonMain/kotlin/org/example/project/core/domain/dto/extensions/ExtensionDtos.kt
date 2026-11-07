package org.example.project.core.domain.dto.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object ExtensionDto {
    @Serializable
    data class SchemaFieldResponseDto(
        val id: Long,
        val moduleName: String,
        val entityType: String,
        val fieldName: String,
        val fieldType: String,
        val isRequired: Boolean,
        val defaultValue: String?,
        val maxLength: Int?,
        val validationRules: JsonElement?,
        val uiHints: JsonElement?,
        val referenceEntity: String?,
        val referenceModule: String?,
        val onDelete: String?,
        val isPromoted: Boolean,
        val isDeprecated: Boolean,
        val sortOrder: Int,
    )

    @Serializable
    data class CreateSchemaFieldRequestDto(
        val moduleName: String,
        val entityType: String,
        val fieldName: String,
        val fieldType: String,
        val isRequired: Boolean = false,
        val defaultValue: String? = null,
        val maxLength: Int? = null,
        val validationRules: JsonElement? = null,
        val uiHints: JsonElement? = null,
        val referenceEntity: String? = null,
        val referenceModule: String? = null,
        val onDelete: String? = null,
        val isPromoted: Boolean = false,
        val sortOrder: Int = 0,
    )

    @Serializable
    data class UpdateSchemaFieldRequestDto(
        val isRequired: Boolean? = null,
        val defaultValue: String? = null,
        val maxLength: Int? = null,
        val validationRules: JsonElement? = null,
        val uiHints: JsonElement? = null,
        val sortOrder: Int? = null,
    )

    @Serializable
    data class ExtensionDataResponseDto(
        val entityType: String,
        val entityId: String,
        val extensions: JsonElement,
    )

    @Serializable
    data class SetExtensionDataRequestDto(
        val moduleName: String,
        val fields: Map<String, JsonElement?>,
    )

    @Serializable
    data class DynamicEntityTypeResponseDto(
        val id: Long,
        val moduleName: String,
        val entityName: String,
        val schema: JsonElement,
        val indexes: JsonElement?,
    )

    @Serializable
    data class CreateDynamicEntityTypeRequestDto(
        val moduleName: String,
        val entityName: String,
        val schema: JsonElement,
        val indexes: JsonElement? = null,
    )

    @Serializable
    data class DynamicEntityRowResponseDto(
        val id: Long,
        val data: JsonElement,
    )

    @Serializable
    data class CreateDynamicEntityRowRequestDto(
        val data: JsonElement,
    )
}
