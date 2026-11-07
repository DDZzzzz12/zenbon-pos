package com.secureflashcard.wormapi

import java.io.Closeable
import org.example.project.tse.virtualworm.VirtualWormDevice

/**
 * Virtual test double replacing the SWIG-generated log-entry cursor from `lib/WormAPI.jar`.
 *
 * A cursor over the virtual device's in-memory log-entry list. [isValid] is false when
 * positioned outside the list (empty store, or advanced past the last entry via [next]),
 * matching how the real API signals iteration end.
 */
class WormEntry internal constructor(
    private val device: VirtualWormDevice,
    private var position: Int,
) : Closeable {

    fun isValid(): Boolean = device.entryAt(position) != null

    fun id(): Long = required().id

    fun type(): WormEntryType = required().type

    fun logMessageLength(): Long = required().logMessage.size.toLong()

    fun readLogMessage(): ByteArray = required().logMessage.copyOf()

    fun processDataLength(): Long = required().processData.size.toLong()

    fun readProcessData(): ByteArray = required().processData.copyOf()

    fun next() {
        position += 1
    }

    fun delete() {}

    override fun close() {}

    private fun required(): VirtualWormDevice.LogEntry =
        device.entryAt(position)
            ?: throw WormException(WormError.WORM_ERROR_INVALID_STATE.swigValue())
}
