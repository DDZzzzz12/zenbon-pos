package com.secureflashcard.wormapi

import java.io.Closeable

/**
 * Virtual test double replacing the SWIG director class from `lib/WormAPI.jar`.
 *
 * pos-tse's incremental export subclasses this and overrides [onNewData]
 * (data chunk, processed blocks, total blocks); returning 0 continues the export.
 */
open class WormExportTarIncrementalCallback : Closeable {

    open fun onNewData(data: ByteArray, processedBlocks: Long, totalBlocks: Long): Int = 0

    fun delete() {}

    fun swigReleaseOwnership() {}

    fun swigTakeOwnership() {}

    override fun close() {}
}
