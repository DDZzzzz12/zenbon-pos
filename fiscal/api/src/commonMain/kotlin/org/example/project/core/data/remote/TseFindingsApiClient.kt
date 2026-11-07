package org.example.project.core.data.remote

import org.example.project.core.domain.dto.tse.TseExplanationDto

/**
 * The TSE transaction numbers the POS has **no ordinary record of**, and the append-only register
 * that explains them (AEAO § 146a Nr. 1.15.1).
 *
 * These findings are computed against our own till data. They are deliberately **not** the findings
 * of "Export prüfen", which verifies a TSE export file and answers a different question — a
 * transaction number issued before this device's first POS row is a finding there and no gap here.
 * The two lists may legitimately disagree and must never be merged.
 *
 * Both endpoints require `Capability.TSE_ADMIN` server-side.
 */
interface TseFindingsApiClient {
    /** Findings over the whole history, each carrying the explanation that currently covers it. */
    suspend fun findings(): TseExplanationDto.FindingsResponseDto

    /**
     * Records why a range of transaction numbers has no POS record. Nothing is ever changed or
     * deleted: a correction is a new entry naming the one it replaces via `supersedesId`.
     */
    suspend fun explain(request: TseExplanationDto.CreateRequestDto): TseExplanationDto.ExplanationDto
}
