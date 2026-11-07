package org.example.project.features.tse.domain.models

/**
 * Where a Vorgang's TSE transaction stands (E2E R2-7, design 2026-09-22 §4.1).
 *
 * The TANR is recorded as [STARTED] before the device signs anything, so a failed write can no
 * longer throw a signature away; a retry finishes the same TANR.
 */
enum class TseTransactionState {
    /** Started on the device and recorded here; not finished (or its finish was not recorded). */
    STARTED,

    /** Finished and signed. */
    FINISHED,

    /**
     * The device had already closed this TANR when a retry tried to finish it. Either its finish went
     * through and the reply was lost, or maintenance aborted it. It is replaced by a new TANR, and
     * exported as an extra, unsigned TSE_Transaktionen row on the same Bon (design D2, TR-03153-1 §9.4).
     */
    SUPERSEDED,
}
