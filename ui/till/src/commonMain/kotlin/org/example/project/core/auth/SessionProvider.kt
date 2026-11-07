package org.example.project.core.auth

/**
 * Provides the current authenticated employee's identity context.
 *
 * Used by server accessor classes to supply employeeId and deviceId
 * to service methods that require authentication context, bypassing
 * the HTTP/JWT layer when the server is bundled directly into the
 * desktop application.
 *
 * @since 1.0.0
 */
interface SessionProvider {
    /**
     * Returns the currently authenticated employee's ID.
     *
     * @return Employee UUID string, or empty string if not authenticated
     */
    fun getEmployeeId(): String

    /**
     * Returns the current device's unique identifier.
     *
     * @return Device UUID string
     */
    fun getDeviceId(): String
}
