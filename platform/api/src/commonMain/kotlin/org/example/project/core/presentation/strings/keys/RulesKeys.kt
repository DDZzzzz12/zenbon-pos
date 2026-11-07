package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object RulesKeys {
    val roundRobinAssignment = StringKey("rules.round_robin_assignment")
    val roundRobinAssignmentDescription = StringKey("rules.round_robin_assignment_description")

    val rotationPoolHeader = StringKey("rules.rotation_pool_header")
    val rotationPoolDescription = StringKey("rules.rotation_pool_description")
    val rotationPoolEmptyToast = StringKey("rules.rotation_pool_empty_toast")

    // Dashboard chip naming the next rotation employee, and the toast when the rotation moved on.
    val rotationNextLabel = StringKey("rules.rotation_next_label")
    val rotationNextContentDescription = StringKey("rules.rotation_next_content_description")
    val rotationShiftedToast = StringKey("rules.rotation_shifted_toast")

    // An order station never owns a table session (spec 2026-09-14).
    val stationTableDeniedTitle = StringKey("rules.station_table_denied_title")
    val stationTableRequiresRotation = StringKey("rules.station_table_requires_rotation")
    val stationCannotOpenVirtualTable = StringKey("rules.station_cannot_open_virtual_table")

    val managerOnly = StringKey("rules.manager_only")
}
