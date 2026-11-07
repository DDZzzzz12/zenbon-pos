package org.example.project.utils.formatter

import org.example.project.features.tse.domain.models.TseTransaction

/**
 * The transaction's TSE serial in the one form allowed for identifying a TSE, uppercase hex
 * (TR-03153-1 §9.3.2 — a Base64 representation is explicitly not allowed „im Rahmen der
 * Identifizierung und Zuordnung der TSE"). Rows written before V82 fall back to normalizing the
 * raw column, so one device never appears under two serials when compared or grouped.
 */
fun TseTransaction.serialHex(): String? = tseSerialHex ?: TseSerialFormat.normalizeToHex(tseSerialNumber)
