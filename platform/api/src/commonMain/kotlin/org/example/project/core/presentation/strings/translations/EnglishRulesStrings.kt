package org.example.project.core.presentation.strings.translations

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.RulesKeys

object EnglishRulesStrings {
    val all: Map<StringKey, String> =
        mapOf(
            RulesKeys.roundRobinAssignment to "Round-Robin Employee Assignment",
            RulesKeys.roundRobinAssignmentDescription to
                "Auto-assign the next employee in turn from the rotation pool when opening a free table",
            RulesKeys.rotationPoolHeader to "Rotation Pool",
            RulesKeys.rotationPoolDescription to "Employees included in the round-robin rotation. Manager-only.",
            RulesKeys.rotationPoolEmptyToast to "No employee is in the rotation pool. Add employees under Round-Robin Employee Assignment.",
            RulesKeys.rotationNextLabel to "Next: {0}",
            RulesKeys.rotationNextContentDescription to "Next table goes to {0}",
            RulesKeys.rotationShiftedToast to "Rotation moved on — table opened for {0}",
            RulesKeys.stationTableDeniedTitle to "Table cannot be opened",
            RulesKeys.stationTableRequiresRotation to
                "An order station can open a free table only when Round-Robin Employee Assignment is on. Please open the table at the till.",
            RulesKeys.stationCannotOpenVirtualTable to
                "An order station cannot open a takeaway or virtual table. Please open it at the till.",
            RulesKeys.managerOnly to "Manager only",
        )
}
