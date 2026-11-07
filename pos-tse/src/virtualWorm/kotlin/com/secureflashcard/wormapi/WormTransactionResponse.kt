package com.secureflashcard.wormapi

import java.io.Closeable

/**
 * Virtual test double replacing the SWIG-generated response class from `lib/WormAPI.jar`.
 * Immutable snapshot produced by the virtual device for transaction_start/update/finish.
 */
class WormTransactionResponse internal constructor(
    private val logTimeValue: Long,
    private val serialNumberValue: ByteArray,
    private val signatureCounterValue: Long,
    private val signatureValue: ByteArray,
    private val transactionNumberValue: Long,
) : Closeable {

    fun logTime(): Long = logTimeValue

    fun serialNumber(): ByteArray = serialNumberValue.copyOf()

    fun signatureCounter(): Long = signatureCounterValue

    fun signature(): ByteArray = signatureValue.copyOf()

    fun transactionNumber(): Long = transactionNumberValue

    fun delete() {}

    override fun close() {}
}
