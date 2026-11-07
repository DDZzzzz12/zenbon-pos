package org.example.project.utils.config

import io.ktor.server.config.*
import io.ktor.server.config.yaml.YamlConfigLoader as KtorYamlConfigLoader

/**
 * Application configuration wrapper using Ktor's native YAML config loader.
 *
 * Provides a simple API for accessing typed configuration values from application.yaml.
 * Supports dot-notation paths and environment variable substitution natively via Ktor.
 */
class AppConfig(
    private val config: ApplicationConfig,
) {
    fun getString(path: String): String = config.property(path).getString()

    fun getInt(path: String): Int = getString(path).toInt()

    fun getLong(path: String): Long = getString(path).toLong()

    fun getBoolean(path: String): Boolean = getString(path).toBoolean()

    fun hasPath(path: String): Boolean = config.propertyOrNull(path) != null

    fun getConfig(path: String): AppConfig = AppConfig(config.config(path))
}

/**
 * Loads application.yaml using Ktor's native YAML config loader.
 *
 * Cached after first load since configuration is immutable at runtime.
 */
object YamlConfigLoader {
    private val cachedConfig: AppConfig by lazy {
        val ktorConfig =
            KtorYamlConfigLoader().load("application.yaml")
                ?: throw IllegalStateException("Could not load application.yaml from classpath")
        AppConfig(ktorConfig)
    }

    fun load(configFile: String = "application.yaml"): AppConfig {
        if (configFile != "application.yaml") {
            val config =
                KtorYamlConfigLoader().load(configFile)
                    ?: throw IllegalStateException("Could not load $configFile from classpath")
            return AppConfig(config)
        }
        return cachedConfig
    }
}
