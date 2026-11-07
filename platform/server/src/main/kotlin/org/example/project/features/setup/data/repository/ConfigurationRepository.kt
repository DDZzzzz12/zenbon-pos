/**
 * Repository for managing shop configuration settings using key-value storage.
 *
 * Provides type-safe access to configuration settings stored as key-value pairs
 * in the database. Handles parsing and validation of different value types.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.setup.data.repository

import kotlinx.serialization.json.*
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.repository.ConfigurationContributor
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory

/**
 * Repository implementation for configuration key-value operations.
 *
 * Provides methods to get, set, and manage configuration settings with
 * proper type parsing and fallback to defaults.
 */
class ConfigurationRepository(
    private val contributors: List<ConfigurationContributor> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(ConfigurationRepository::class.java)

    /**
     * Gets a raw configuration value as JsonElement.
     *
     * Returns the JSONB value as JsonElement for proper type handling:
     * - Preserves JSON structure (objects, arrays, primitives)
     * - Allows type-safe access to nested values
     * - No lossy string conversions
     */
    suspend fun getSetting(
        shopId: Long,
        key: String,
    ): JsonElement? =
        ShopConfigurationTable
            .selectAll()
            .where((ShopConfigurationTable.shopId eq shopId) and (ShopConfigurationTable.key eq key))
            .singleOrNull()
            ?.get(ShopConfigurationTable.value)

    /**
     * Reads one setting and row-locks it (`SELECT … FOR UPDATE`) for a read-modify-write inside the
     * caller's transaction — the round-robin cursor. Null when the row does not exist.
     */
    suspend fun getSettingForUpdate(
        shopId: Long,
        key: String,
    ): JsonElement? =
        ShopConfigurationTable
            .selectAll()
            .where((ShopConfigurationTable.shopId eq shopId) and (ShopConfigurationTable.key eq key))
            .forUpdate()
            .singleOrNull()
            ?.get(ShopConfigurationTable.value)

    /**
     * Gets a raw configuration value as string (for backward compatibility).
     *
     * Extracts the string content from the JSONB value:
     * - For JSON objects/arrays: returns the JSON string representation
     * - For JSON primitives: returns the unwrapped string value
     */
    suspend fun getSettingAsString(
        shopId: Long,
        key: String,
    ): String? {
        val jsonElement = getSetting(shopId, key)

        // Convert JsonElement to string based on type
        return jsonElement?.let { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                is JsonObject, is JsonArray -> element.toString()
            }
        }
    }

    /**
     * Gets a string configuration value.
     *
     * For JSON objects/arrays, returns the JSON string representation.
     * For primitives, returns the string content.
     */
    suspend fun getStringSetting(
        shopId: Long,
        configKey: ConfigKey,
    ): String =
        getSetting(shopId, configKey.key)
            ?.let { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull ?: element.toString()
                    is JsonObject, is JsonArray -> element.toString()
                }
            }
            ?: configKey.defaultValue

    /**
     * Gets a JSON object configuration value.
     *
     * Returns null if the value is not a valid JSON object.
     */
    suspend fun getJsonObjectSetting(
        shopId: Long,
        configKey: ConfigKey,
    ): JsonObject? = getSetting(shopId, configKey.key) as? JsonObject

    /**
     * Gets a JSON array configuration value.
     *
     * Returns null if the value is not a valid JSON array.
     */
    suspend fun getJsonArraySetting(
        shopId: Long,
        configKey: ConfigKey,
    ): JsonArray? = getSetting(shopId, configKey.key) as? JsonArray

    /**
     * Sets a configuration value (upsert operation).
     *
     * Automatically converts the value to appropriate JsonElement:
     * - Strings starting with { or [: parsed as JSON
     * - Numbers: stored as JSON numbers
     * - Booleans: stored as JSON booleans
     * - Other: stored as JSON strings
     */
    suspend fun setSetting(
        shopId: Long,
        configKey: ConfigKey,
        value: Any,
        description: String? = null,
    ) {
        val jsonElement = valueToJsonElement(value)

        contributors.forEach { it.onSettingWritten(configKey, value.toString()) }

        ShopConfigurationTable.upsert {
            it[ShopConfigurationTable.shopId] = shopId
            it[ShopConfigurationTable.key] = configKey.key
            it[ShopConfigurationTable.value] = jsonElement
            it[ShopConfigurationTable.description] = description ?: configKey.description
            it[ShopConfigurationTable.updatedAt] = currentTimeStamp()
        }
        logger.debug("Set configuration: shopId=$shopId, key=${configKey.key}, value=$value")
    }

    /**
     * Converts a value to JsonElement with automatic type detection.
     */
    private fun valueToJsonElement(value: Any): JsonElement =
        when (value) {
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> {
                // Try to parse as JSON if it looks like JSON
                val trimmed = value.trim()
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        Json.parseToJsonElement(trimmed)
                    } catch (e: Exception) {
                        logger.debug("Not valid JSON, storing as string: ${e.message}")
                        JsonPrimitive(value)
                    }
                } else {
                    JsonPrimitive(value)
                }
            }

            else -> JsonPrimitive(value.toString())
        }

    /**
     * Gets all settings for a shop as a map of JsonElements.
     *
     * Returns the raw JsonElement values for type-safe access.
     * Use this to preserve JSON structure and avoid string conversions.
     */
    suspend fun getAllSettings(shopId: Long): Map<String, JsonElement> =
        ShopConfigurationTable
            .selectAll()
            .where { ShopConfigurationTable.shopId eq shopId }
            .associate { row ->
                val key = row[ShopConfigurationTable.key]
                val jsonElement = row[ShopConfigurationTable.value]
                key to jsonElement
            }

    /**
     * Gets all settings for a shop as a map of strings (for backward compatibility).
     *
     * Converts JsonElement values to strings:
     * - Primitives: unwrapped string value
     * - Objects/arrays: JSON string representation
     */
    suspend fun getAllSettingsAsStrings(shopId: Long): Map<String, String> =
        ShopConfigurationTable
            .selectAll()
            .where { ShopConfigurationTable.shopId eq shopId }
            .associate { row ->
                val key = row[ShopConfigurationTable.key]
                val jsonElement = row[ShopConfigurationTable.value]
                val valueString =
                    when (jsonElement) {
                        is JsonPrimitive -> jsonElement.contentOrNull ?: jsonElement.toString()
                        is JsonObject, is JsonArray -> jsonElement.toString()
                    }
                key to valueString
            }

    /**
     * Initializes default settings for a shop if they don't exist.
     *
     * Converts default values to JsonElement for JSONB storage.
     */
    suspend fun initializeDefaults(shopId: Long) {
        ConfigKey.entries.forEach { configKey ->
            // Only insert if not exists
            val exists =
                ShopConfigurationTable
                    .selectAll()
                    .where((ShopConfigurationTable.shopId eq shopId) and (ShopConfigurationTable.key eq configKey.key))
                    .count() > 0

            if (!exists) {
                ShopConfigurationTable.insert {
                    it[ShopConfigurationTable.shopId] = shopId
                    it[ShopConfigurationTable.key] = configKey.key
                    it[ShopConfigurationTable.value] = valueToJsonElement(configKey.defaultValue)
                    it[ShopConfigurationTable.description] = configKey.description
                    it[ShopConfigurationTable.updatedAt] = currentTimeStamp()
                }
            }
        }
        logger.info("Initialized default configuration for shopId=$shopId")

        contributors.forEach { it.afterDefaultsInitialized(shopId) }
    }

    /**
     * Deletes a specific setting.
     */
    suspend fun deleteSetting(
        shopId: Long,
        key: String,
    ): Int =
        ShopConfigurationTable.deleteWhere {
            (ShopConfigurationTable.shopId eq shopId) and (ShopConfigurationTable.key eq key)
        }

    /**
     * Deletes all settings for a shop.
     */
    suspend fun deleteAllSettings(shopId: Long): Int =
        ShopConfigurationTable.deleteWhere {
            ShopConfigurationTable.shopId eq shopId
        }
}
