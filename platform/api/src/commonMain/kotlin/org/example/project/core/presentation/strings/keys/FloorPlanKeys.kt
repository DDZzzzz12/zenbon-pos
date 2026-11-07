package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object FloorPlanKeys {
    val combineMode = StringKey("floor_plan.combine_mode")
    val combineModeTip = StringKey("floor_plan.combine_mode_tip")

    // Table-card labels. Rendered inside shared-ui, which allows no hardcoded strings.
    val seatsCount = StringKey("floor_plan.seats_count")
    val tableLabel = StringKey("floor_plan.table_label")
    val training = StringKey("floor_plan.training")
    val inactive = StringKey("floor_plan.inactive")
    val trainingShort = StringKey("floor_plan.training_short")
    val inactiveShort = StringKey("floor_plan.inactive_short")
    val statusAvailable = StringKey("floor_plan.status_available")
    val statusOccupied = StringKey("floor_plan.status_occupied")
    val statusReserved = StringKey("floor_plan.status_reserved")
}
