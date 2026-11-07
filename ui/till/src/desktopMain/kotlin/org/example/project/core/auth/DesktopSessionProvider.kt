package org.example.project.core.auth

import org.example.project.core.device.DeviceInfoProvider

/**
 * Desktop implementation of [SessionProvider] for bundled server mode.
 *
 * Retrieves the current employee ID from [PermissionManager] (which
 * reads from the local JWT token) and the device ID from the
 * platform [DeviceInfoProvider] object.
 *
 * @property permissionManager Manages authentication state and permissions
 * @since 1.0.0
 */
class DesktopSessionProvider(
    private val permissionManager: PermissionManager,
) : SessionProvider {
    /**
     * Returns the currently authenticated employee's UUID string.
     *
     * Falls back to empty string if no employee is authenticated,
     * which should not occur in normal operation since the desktop
     * app requires login before accessing any features.
     */
    override fun getEmployeeId(): String = permissionManager.getEmployeeId() ?: ""

    /**
     * Returns this device's persistent unique identifier.
     *
     * Uses the platform-specific [DeviceInfoProvider] object which
     * generates and persists a UUID on first access.
     */
    override fun getDeviceId(): String = DeviceInfoProvider.getDeviceId()
}
