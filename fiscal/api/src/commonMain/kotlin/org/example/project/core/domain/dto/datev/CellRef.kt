package org.example.project.core.domain.dto.datev

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind

/**
 * Names one mapping cell, for the export notice that lists the bookings left without a contra
 * account. Exactly one axis is set: [special]; or [paymentTypeId]; or [side] + [kind] + [rate].
 * [paymentTypeName] is display text only, carried because the export screen has no payment-type
 * list to look the id up in.
 */
@Serializable
data class CellRef(
    val side: DatevMappingSide? = null,
    val kind: KassenbuchLineKind? = null,
    val rate: String? = null,
    val paymentTypeId: Long? = null,
    val paymentTypeName: String? = null,
    val special: KassenbuchSpecialBooking? = null,
)
