package com.secureflashcard.wormapi

import java.io.Closeable

/**
 * Virtual test double replacing the SWIG director class from `lib/WormAPI.jar`.
 *
 * pos-tse export operations subclass this and override [onNewData]; returning 0
 * continues the export, any other value aborts it (the virtual device then throws
 * WORM_ERROR_EXPORT_FAILED, mirroring the real SDK contract).
 */
open class WormExportTarCallback : Closeable {

    open fun onNewData(data: ByteArray): Int = 0

    fun delete() {}

    fun swigReleaseOwnership() {}

    fun swigTakeOwnership() {}

    override fun close() {}
}
