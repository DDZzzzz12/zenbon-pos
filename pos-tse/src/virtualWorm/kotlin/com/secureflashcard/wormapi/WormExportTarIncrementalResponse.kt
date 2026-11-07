package com.secureflashcard.wormapi

import java.io.Closeable

/**
 * Virtual test double replacing the SWIG-generated incremental-export response
 * from `lib/WormAPI.jar`. `newState` is an opaque 16-byte cursor
 * (WORM_EXPORT_TAR_INCREMENTAL_STATE_SIZE) encoding the last exported signature counter.
 */
class WormExportTarIncrementalResponse internal constructor(
    private val newStateValue: ByteArray,
    private val firstSignatureCounterValue: Long,
    private val lastSignatureCounterValue: Long,
    private val allDataExportedValue: Boolean,
) : Closeable {

    fun newState(): ByteArray = newStateValue.copyOf()

    fun firstSignatureCounter(): Long = firstSignatureCounterValue

    fun lastSignatureCounter(): Long = lastSignatureCounterValue

    fun allDataExported(): Boolean = allDataExportedValue

    fun delete() {}

    override fun close() {}
}
