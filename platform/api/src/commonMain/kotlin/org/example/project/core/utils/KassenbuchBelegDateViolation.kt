package org.example.project.core.utils

/** Why a Kassenbuch Beleg date is not acceptable — see [KassenbuchBelegDateRule]. */
enum class KassenbuchBelegDateViolation {
    /** Dated after today: a receipt cannot exist before the purchase it documents. */
    FUTURE,

    /** Dated further back than the shop's limit ([KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS] by default). */
    TOO_OLD,
}
