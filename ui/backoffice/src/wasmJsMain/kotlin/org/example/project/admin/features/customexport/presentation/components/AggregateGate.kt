package org.example.project.admin.features.customexport.presentation.components

import org.example.project.core.domain.dto.customexport.CustomExportDto

/**
 * Which offered columns the picker must refuse to tick, given what is already chosen.
 *
 * A summary column is a correlated subquery run once per exported row, so the server caps how many
 * of them one definition may carry — and refuses both the save and the run past that cap. Letting an
 * operator tick a sixth one and discover the refusal on *Speichern* is the failure this exists to
 * prevent; the checkbox that cannot be honoured should not be tickable.
 *
 * Three rules, and each one is a way of getting this wrong:
 *
 * - **Only unchosen aggregates lock.** Disabling a chosen one at the cap would leave the operator at
 *   the limit with no way to get back under it: the only control that unticks a column would be the
 *   one that is greyed out.
 * - **Ordinary columns never lock.** They are read off a row the query was fetching anyway and cost
 *   nothing per row; the cheap kind of width stays free at any cap.
 * - **[maxAggregateColumns] is the server's number**, published on
 *   [CustomExportDto.SubjectDto.maxAggregateColumns], never a constant of the client's own that would
 *   drift from it.
 *
 * A key is an aggregate because [CustomExportDto.FieldDto.isAggregate] says so — never because of the
 * `~` in it, which is a server-side encoding the client must not read.
 *
 * Pure and free of Compose on purpose, exactly as [groupFields] is: the decision is the part that has
 * to be right, and the wasm test leg can assert on a set where it cannot assert on a canvas.
 *
 * @param available every column the subject offers.
 * @param chosenKeys the field keys currently ticked.
 * @param maxAggregateColumns the server's cap on summary columns.
 * @return the keys whose checkbox must be disabled. Empty while there is room left.
 */
fun lockedAggregateKeys(
    available: List<CustomExportDto.FieldDto>,
    chosenKeys: Set<String>,
    maxAggregateColumns: Int,
): Set<String> {
    val aggregates = available.filter { it.isAggregate }
    val chosenAggregates = aggregates.count { it.key in chosenKeys }
    if (chosenAggregates < maxAggregateColumns) return emptySet()
    return aggregates.filter { it.key !in chosenKeys }.map { it.key }.toSet()
}

/** How many of [chosenKeys] are summary columns — the number the picker's hint reports. */
fun chosenAggregateCount(
    available: List<CustomExportDto.FieldDto>,
    chosenKeys: Set<String>,
): Int = available.count { it.isAggregate && it.key in chosenKeys }
