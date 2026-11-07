/**
 * Platform-specific device information provider.
 *
 * Provides device identification and information for terminal registration
 * in the multi-client POS architecture. Each platform implements this
 * to provide unique device identifiers and platform-specific info.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.device

/**
 * Provides platform-specific device information for terminal registration.
 *
 * Used to uniquely identify client devices for DSFinV-K compliance
 * and multi-terminal tracking in the POS system.
 */
expect object DeviceInfoProvider {
    /**
     * Gets the unique device identifier.
     *
     * On Android: Uses ANDROID_ID or generates a UUID if unavailable
     * On Desktop: Generates/retrieves a persistent UUID from local storage
     *
     * @return Unique device identifier string
     */
    fun getDeviceId(): String

    /**
     * Gets the device name/hostname.
     *
     * On Android: Returns the device name from Build.DEVICE
     * On Desktop: Returns the local hostname
     *
     * @return Device name string
     */
    fun getDeviceName(): String

    /**
     * Gets the device model information.
     *
     * On Android: Returns manufacturer and model (e.g., "Samsung Galaxy S24")
     * On Desktop: Returns OS name and "Desktop" (e.g., "Windows Desktop")
     *
     * @return Device model string
     */
    fun getDeviceModel(): String

    /**
     * Gets the operating system version.
     *
     * On Android: Returns "Android X.X" with version number
     * On Desktop: Returns OS name and version
     *
     * @return OS version string
     */
    fun getOsVersion(): String

    /**
     * Gets the device brand/platform identifier.
     *
     * On Android: Returns "Android"
     * On Desktop: Returns "Windows", "macOS", or "Linux"
     *
     * @return Platform brand string
     */
    fun getPlatformBrand(): String
}
