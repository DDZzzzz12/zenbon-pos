package org.example.project.core.utils

import org.example.project.core.domain.enums.tse.TseLateSigningReason

/**
 * The German label of a Nachsignierung reason code, as it appears on fiscal documents — the printed
 * Beleg and the DSFinV-K `TSE_TA_FEHLER` field. One definition so the two can never word the same
 * outage differently. An unknown code (a newer server) reads as "nicht erfasst", never the raw code.
 */
fun lateSigningReasonLabelDe(reasonCode: String): String =
    when (TseLateSigningReason.entries.firstOrNull { it.name == reasonCode }) {
        TseLateSigningReason.TSE_UNAVAILABLE -> "TSE nicht erreichbar"
        TseLateSigningReason.TSE_ERROR -> "TSE-Fehler"
        TseLateSigningReason.TSE_NOT_OPERATIONAL -> "TSE nicht betriebsbereit"
        TseLateSigningReason.PENDING_SWEEP -> "Signierung ausstehend"
        TseLateSigningReason.SIMULATED_WHILE_NORMAL -> "Signatur wiederholt"
        TseLateSigningReason.UNKNOWN_HISTORIC, null -> "nicht erfasst"
    }
