/**
 * Which side of the DATEV account mapping a Kassenbuch entry type resolves
 * against (spec §3.4). Null side (no mapping) is expressed by a nullable
 * property on KassenbuchEntryType, not by an enum value here.
 */
package org.example.project.core.domain.enums.datev

/** Account-mapping side for kind x rate lookups. */
enum class DatevMappingSide {
    INCOME,
    EXPENSE,
}
