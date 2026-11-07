/**
 * Android-specific implementation of DeviceInfoProvider.
 *
 * Uses Android APIs to provide device identification and information
 * for terminal registration in the multi-client POS architecture.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import java.util.UUID

/**
 * Android implementation of DeviceInfoProvider.
 *
 * Uses Android Build and Settings APIs to gather device information.
 * Requires application context to be initialized before use.
 */
actual object DeviceInfoProvider {
    private var applicationContext: Context? = null
    private var cachedDeviceId: String? = null

    /**
     * Initializes the provider with application context.
     * Must be called during application startup (e.g., in Application.onCreate).
     *
     * @param context Application context
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Gets the unique device identifier using ANDROID_ID.
     * Falls back to a generated UUID if ANDROID_ID is unavailable.
     */
    @SuppressLint("HardwareIds")
    actual fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        val context =
            applicationContext
                ?: throw IllegalStateException("DeviceInfoProvider not initialized. Call initialize() first.")

        val androidId =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            )

        val deviceId =
            if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") {
                // ANDROID_ID unavailable or is the known emulator ID
                // Generate a persistent UUID using SharedPreferences
                val prefs = context.getSharedPreferences("pos_device_prefs", Context.MODE_PRIVATE)
                prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { uuid ->
                    prefs.edit { putString("device_id", uuid) }
                }
            } else {
                androidId
            }

        cachedDeviceId = deviceId
        return deviceId
    }

    /**
     * Gets the device name from Build.DEVICE.
     */
    actual fun getDeviceName(): String = Build.DEVICE ?: "Unknown"

    /**
     * Gets the device model with manufacturer.
     * Returns format: "Manufacturer Model" (e.g., "Samsung SM-S928B")
     */
    actual fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER ?: "Unknown"
        val model = Build.MODEL ?: "Device"
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Gets the Android version string.
     * Returns format: "Android X.X (SDK YY)" (e.g., "Android 14 (SDK 34)")
     */
    actual fun getOsVersion(): String = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    /**
     * Returns "Android" as the platform brand.
     */
    actual fun getPlatformBrand(): String = "Android"
}
