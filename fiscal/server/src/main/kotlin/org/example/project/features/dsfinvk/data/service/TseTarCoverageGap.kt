package org.example.project.features.dsfinvk.data.service

/**
 * One TSE the exported period needs but the shipped TAR does not fully cover (checklist 28.4).
 *
 * @property serialHex the device serial as upper-case hex, exactly as tse.csv carries it
 * @property requiredCount signed transactions of the period this device holds
 * @property missingTanrs the period's TANRs of this device with no log message in the TAR, ascending
 * @property deviceInTar whether the TAR carries ANY log message of this device — false means the
 *   archive is empty or comes from a different device
 */
data class TseTarCoverageGap(
    val serialHex: String,
    val requiredCount: Int,
    val missingTanrs: List<Long>,
    val deviceInTar: Boolean,
)
