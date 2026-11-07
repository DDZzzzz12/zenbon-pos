/**
 * Desktop (JVM) implementation of DeviceInfoProvider.
 *
 * Uses Java system properties and preferences to provide device
 * identification and information for terminal registration.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.device

import java.net.InetAddress
import java.util.UUID
import java.util.prefs.Preferences

/**
 * Desktop JVM implementation of DeviceInfoProvider.
 *
 * Uses Java Preferences API for persistent device ID storage
 * and system properties for platform information.
 */
actual object DeviceInfoProvider {
    private const val PREFS_NODE = "org/example/project/pos"
    private const val DEVICE_ID_KEY = "device_id"

    private var cachedDeviceId: String? = null
    private val prefs: Preferences by lazy {
        Preferences.userRoot().node(PREFS_NODE)
    }

    /**
     * Gets the unique device identifier.
     * Generates and persists a UUID on first access.
     */
    actual fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        val existingId = prefs.get(DEVICE_ID_KEY, null)
        val deviceId =
            existingId ?: UUID.randomUUID().toString().also { uuid ->
                prefs.put(DEVICE_ID_KEY, uuid)
                prefs.flush()
            }

        cachedDeviceId = deviceId
        return deviceId
    }

    /**
     * Gets the local hostname as the device name.
     */
    actual fun getDeviceName(): String =
        try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            System.getProperty("user.name", "Desktop")
        }

    /**
     * Gets the OS version string.
     */
    actual fun getOsVersion(): String {
        val osName = System.getProperty("os.name", "Unknown")
        val osVersion = System.getProperty("os.version", "")
        val osArch = System.getProperty("os.arch", "")
        return "$osName $osVersion ($osArch)"
    }

    /**
     * Returns the platform brand based on OS.
     * Returns "Windows", "macOS", or "Linux".
     */
    actual fun getPlatformBrand(): String {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            osName.contains("win") -> "Windows"
            osName.contains("mac") -> "macOS"
            osName.contains("nix") || osName.contains("nux") -> "Linux"
            else -> "Desktop"
        }
    }

    /**
     * Gets the device model information.
     * Attempts to read actual hardware model from system.
     */
    actual fun getDeviceModel(): String =
        try {
            when {
                isLinux() -> getLinuxModel()
                isWindows() -> getWindowsModel()
                isMacOS() -> getMacModel()
                else -> fallbackModel()
            }
        } catch (e: Exception) {
            fallbackModel()
        }

    private fun isLinux(): Boolean =
        System.getProperty("os.name", "").lowercase().let {
            it.contains("nix") || it.contains("nux")
        }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    private fun isMacOS(): Boolean = System.getProperty("os.name", "").lowercase().contains("mac")

    private fun fallbackModel(): String {
        val osName = System.getProperty("os.name", "Unknown OS")
        return "$osName Desktop"
    }

    /**
     * Reads hardware model from Linux DMI files.
     */
    private fun getLinuxModel(): String {
        val vendor = readFile("/sys/class/dmi/id/sys_vendor")?.trim()
        val productName = readFile("/sys/class/dmi/id/product_name")?.trim()

        return when {
            vendor != null && productName != null -> "$vendor $productName"
            productName != null -> productName
            vendor != null -> vendor
            else -> fallbackModel()
        }
    }

    /**
     * Gets hardware model on Windows using PowerShell.
     */
    private fun getWindowsModel(): String =
        try {
            val process =
                ProcessBuilder(
                    "powershell",
                    "-Command",
                    "(Get-CimInstance -ClassName Win32_ComputerSystem).Manufacturer + ' ' + (Get-CimInstance -ClassName Win32_ComputerSystem).Model",
                ).redirectErrorStream(true).start()

            val result =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor()

            if (result.isNotBlank()) result else fallbackModel()
        } catch (e: Exception) {
            fallbackModel()
        }

    /**
     * Gets hardware model on macOS using sysctl.
     */
    private fun getMacModel(): String =
        try {
            val process =
                ProcessBuilder("sysctl", "-n", "hw.model")
                    .redirectErrorStream(true)
                    .start()

            val result =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor()

            if (result.isNotBlank()) "Apple $result" else fallbackModel()
        } catch (e: Exception) {
            fallbackModel()
        }

    private fun readFile(path: String): String? =
        try {
            java.io
                .File(path)
                .takeIf { it.exists() && it.canRead() }
                ?.readText()
        } catch (e: Exception) {
            null
        }
}
