/**
 * DATEV standard chart-of-accounts selector for account-mapping seeds.
 */
package org.example.project.core.domain.enums.datev

import kotlinx.serialization.Serializable

/** DATEV Standardkontenrahmen supported by the account-mapping seed. */
@Serializable
enum class Kontenrahmen(
    /**
     * Value for field 27 of the Buchungsstapel metadata header (`Sachkontenrahmen`).
     *
     * DATEV writes the two-digit chart number, e.g. `"03"` — its own Musterdaten export carries
     * `"03"` there. The header field used to be the hardcoded default `"04"` because no caller ever
     * passed one, so an SKR03 shop shipped a file declaring SKR04.
     */
    val datevCode: String,
) {
    SKR03("03"),
    SKR04("04"),
}
