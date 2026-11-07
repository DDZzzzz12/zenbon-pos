package org.example.project.features.backup.data.service.dto

/**
 * Database connection details extracted from application configuration.
 */
data class DbConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
)
