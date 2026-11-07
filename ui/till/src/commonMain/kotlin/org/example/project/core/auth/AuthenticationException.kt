package org.example.project.core.auth

/**
 * Custom exception for authentication failures.
 *
 * Used to replace javax.security.sasl.AuthenticationException which is not available on Android.
 */
class AuthenticationException(
    message: String? = "Authentication failed",
    cause: Throwable? = null,
) : Exception(message, cause)
