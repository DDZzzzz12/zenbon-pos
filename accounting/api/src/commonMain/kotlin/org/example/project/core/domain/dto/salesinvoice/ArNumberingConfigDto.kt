package org.example.project.core.domain.dto.salesinvoice

import kotlinx.serialization.Serializable

/**
 * Ausgangsrechnung numbering, stored under the `ar_numbering` shop-configuration key.
 *
 * **No property carries a Kotlin default, deliberately.** `setSetting` silently stringifies JSON it
 * cannot parse into the stored shape, and defaults let a malformed payload decode "successfully"
 * into whatever the defaults say — the whole key then reverts to invented values with no error.
 * Absent fields must fail to decode instead.
 */
@Serializable
data class ArNumberingConfigDto(
    val prefix: String,
    val startValue: Long,
    val padding: Int,
)
