package com.secureflashcard.wormapi

import java.io.Closeable

/**
 * Virtual test double replacing the SWIG-generated flash-health holder from `lib/WormAPI.jar`.
 * The virtual device always reports a healthy flash.
 */
class WormFlashHealthSummary internal constructor(
    private val uncorrectableEccErrorsValue: Long,
    private val percentageRemainingSpareBlocksValue: Short,
    private val percentageRemainingEraseCountsValue: Short,
    private val percentageRemainingTenYearsDataRetentionValue: Short,
    private val needsReplacementValue: Boolean,
) : Closeable {

    fun uncorrectableEccErrors(): Long = uncorrectableEccErrorsValue

    fun percentageRemainingSpareBlocks(): Short = percentageRemainingSpareBlocksValue

    fun percentageRemainingEraseCounts(): Short = percentageRemainingEraseCountsValue

    fun percentageRemainingTenYearsDataRetention(): Short = percentageRemainingTenYearsDataRetentionValue

    fun needsReplacement(): Boolean = needsReplacementValue

    fun delete() {}

    override fun close() {}
}
