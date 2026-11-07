/**
 * Koin dependency injection module for serialization utilities.
 *
 * Provides a centralized JSON serializer instance configured for the application.
 * This ensures consistent JSON encoding/decoding across all components.
 *
 * @since 1.0.0
 */
package org.example.project.infrastructure.di

import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Serialization dependency injection module.
 *
 * Provides:
 * - Application-wide JSON serializer with standard configuration
 *
 * Usage:
 * ```kotlin
 * // Inject JSON serializer
 * val json: Json by inject(AppJson)
 *
 * // Encode/decode
 * val jsonString = json.encodeToString(dto)
 * val dto = json.decodeFromString<MyDto>(jsonString)
 * ```
 */
val serializationModule =
    module {
        /**
         * Application-wide JSON serializer.
         *
         * Configured with:
         * - ignoreUnknownKeys: Allows parsing JSON with extra fields
         * - encodeDefaults: Includes default values in output
         * - isLenient: Allows non-strict JSON parsing
         * - prettyPrint: Disabled for compact output (enable for debugging)
         *
         * Singleton instance shared across the application.
         */
        single(AppJson) {
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = true
                prettyPrint = false
            }
        }
    }
