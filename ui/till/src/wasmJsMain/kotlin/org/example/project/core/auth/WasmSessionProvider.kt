package org.example.project.core.auth

import org.example.project.core.device.DeviceInfoProvider

/**
 * Browser implementation of [SessionProvider].
 *
 * Mirrors `DesktopSessionProvider`: the employee comes from [PermissionManager] (which reads the
 * local JWT) and the device id from the platform [DeviceInfoProvider], which persists a prefixed
 * UUID in `localStorage`. That persistence matters — the server treats the device id as an
 * identity, so a fresh id per reload would look like a new terminal on every refresh.
 */
class WasmSessionProvider(
    private val permissionManager: PermissionManager,
) : SessionProvider {
    override fun getEmployeeId(): String = permissionManager.getEmployeeId() ?: ""

    override fun getDeviceId(): String = DeviceInfoProvider.getDeviceId()
}
