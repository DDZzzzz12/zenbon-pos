package com.secureflashcard.wormapi

import java.io.Closeable

/**
 * Virtual test double replacing the SWIG-generated credentials holder from `lib/WormAPI.jar`.
 * Returned by `WormStore.user_deriveInitialCredentials(seed)`.
 *
 * SDK 6.0.0 added per-user PUKs and a Logger PIN for TSE 2.0. The accessors exist on
 * both generations, matching the real SDK, which returns the full set regardless and
 * leaves the v2-only values unused on older devices.
 */
class WormInitialCredentials internal constructor(
    private val adminPukValue: ByteArray,
    private val adminPinValue: ByteArray,
    private val timeAdminPinValue: ByteArray,
    private val timeAdminPukValue: ByteArray = ByteArray(0),
    private val loggerPukValue: ByteArray = ByteArray(0),
    private val loggerPinValue: ByteArray = ByteArray(0),
) : Closeable {

    fun adminPuk(): ByteArray = adminPukValue.copyOf()

    fun adminPin(): ByteArray = adminPinValue.copyOf()

    fun timeAdminPuk(): ByteArray = timeAdminPukValue.copyOf()

    fun timeAdminPin(): ByteArray = timeAdminPinValue.copyOf()

    fun loggerPuk(): ByteArray = loggerPukValue.copyOf()

    fun loggerPin(): ByteArray = loggerPinValue.copyOf()

    fun delete() {}

    override fun close() {}
}
