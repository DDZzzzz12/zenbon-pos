package org.example.project.infrastructure.plugins

/**
 * Custom exception for unauthorized access.
 */
class UnauthorizedException(
    message: String,
) : Exception(message)
