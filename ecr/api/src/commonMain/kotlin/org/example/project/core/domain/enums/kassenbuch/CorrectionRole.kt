package org.example.project.core.domain.enums.kassenbuch

/**
 * Role of a Kassenbuch entry within a correction.
 *
 * Ordinary entries have a null role. A correction creates exactly two entries:
 * the [REVERSAL] (Storno) that cancels the original, and the [RE_ENTRY] that
 * re-books the corrected values. Both carry `correctsHeaderId` pointing at the
 * original entry being corrected.
 */
enum class CorrectionRole {
    /** Storno counter-booking that cancels the original entry. */
    REVERSAL,

    /** The new, corrected booking that replaces the reversed original. */
    RE_ENTRY,
}
