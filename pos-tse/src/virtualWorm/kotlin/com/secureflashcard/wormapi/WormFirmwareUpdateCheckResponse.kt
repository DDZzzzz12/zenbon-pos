package com.secureflashcard.wormapi

import java.io.Closeable

/**
 * Virtual test double replacing the SWIG-generated firmware-check response from `lib/WormAPI.jar`.
 * The virtual device never has firmware updates available.
 */
class WormFirmwareUpdateCheckResponse internal constructor(
    private val isAvailableValue: Boolean,
    private val downloadUrlValue: String,
) : Closeable {

    fun isAvailable(): Boolean = isAvailableValue

    fun downloadUrl(): String = downloadUrlValue

    fun delete() {}

    override fun close() {}
}
