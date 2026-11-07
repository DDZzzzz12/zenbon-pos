package org.example.project.core.data.models

import org.example.project.database.Local_r_tables

/**
 * Builds an [RTable] from its SQLDelight row.
 *
 * Split out of `RTable.kt` when the model moved to `shared` so the table-management UI could be
 * shared with the web admin: the model itself is pure, but SQLDelight's generated types are
 * composeApp-only and do not exist on wasm. Same package, different filename — a same-package
 * split MUST NOT reuse the filename, or both modules emit the same facade class.
 */
fun Local_r_tables.toRTable(): RTable =
    RTable(
        id = this.id,
        tableNumber = this.table_number,
        seats = this.seats,
        locationTypeId = this.location_type_id,
        locationTypeName = this.location_type_name,
        isVirtual = this.is_virtual == 1L,
        isTakeaway = this.is_takeaway == 1L,
        tableStatus = this.table_status,
        currentSessionId = this.current_session_id,
        currentEmployeeNumber = this.current_employee_number,
        gridX = this.grid_x?.toInt(),
        gridY = this.grid_y?.toInt(),
        rotation = this.rotation?.toFloat(),
        gridWidth = this.grid_width?.toInt(),
        gridHeight = this.grid_height?.toInt(),
        isTraining = this.is_training == 1L,
        isActive = this.is_active == 1L,
        assignmentPriority = this.assignment_priority.toInt(),
        openAmount = this.open_amount,
    )
