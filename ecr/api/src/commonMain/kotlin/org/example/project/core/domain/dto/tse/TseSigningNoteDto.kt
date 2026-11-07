package org.example.project.core.domain.dto.tse

import kotlinx.serialization.Serializable

/**
 * The Nachsignierung record of one Vorgang. A null note on a DTO means the Vorgang was signed on
 * time, or the TSE is DISABLED/SIMULATION by configuration.
 *
 * @property pending true while the Vorgang still waits for its signature.
 * @property reasonCode a `TseLateSigningReason` name.
 * @property reasonText operator-readable cause, taken from the failure at queue time.
 * @property queuedAt ISO local date-time the Vorgang went to the queue.
 * @property signedAt ISO local date-time of the late signature, or null.
 * @property abandoned true if an operator cancelled the Nachsignierung.
 */
@Serializable
data class TseSigningNoteDto(
    val pending: Boolean,
    val reasonCode: String,
    val reasonText: String,
    val queuedAt: String,
    val signedAt: String? = null,
    val abandoned: Boolean = false,
)
