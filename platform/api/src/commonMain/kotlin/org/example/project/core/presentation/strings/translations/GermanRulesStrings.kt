package org.example.project.core.presentation.strings.translations

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.RulesKeys

object GermanRulesStrings {
    val all: Map<StringKey, String> =
        mapOf(
            RulesKeys.roundRobinAssignment to "Reihum-Mitarbeiterzuweisung",
            RulesKeys.roundRobinAssignmentDescription to
                "Beim Öffnen eines freien Tisches wird automatisch der nächste Mitarbeiter aus dem Rotationspool zugewiesen",
            RulesKeys.rotationPoolHeader to "Rotationspool",
            RulesKeys.rotationPoolDescription to "Mitarbeiter, die in die Reihum-Rotation einbezogen werden. Nur für Manager.",
            RulesKeys.rotationPoolEmptyToast to "Kein Mitarbeiter im Rotationspool. Bitte unter Reihum-Mitarbeiterzuweisung Mitarbeiter hinzufügen.",
            RulesKeys.rotationNextLabel to "Als Nächstes: {0}",
            RulesKeys.rotationNextContentDescription to "Der nächste Tisch geht an {0}",
            RulesKeys.rotationShiftedToast to "Rotation hat sich verschoben — Tisch für {0} geöffnet",
            RulesKeys.stationTableDeniedTitle to "Tisch kann nicht geöffnet werden",
            RulesKeys.stationTableRequiresRotation to
                "Eine Bestellstation öffnet freie Tische nur, wenn die Reihum-Mitarbeiterzuweisung eingeschaltet ist. Bitte den Tisch an der Kasse öffnen.",
            RulesKeys.stationCannotOpenVirtualTable to
                "Eine Bestellstation kann keinen Abhol- oder virtuellen Tisch öffnen. Bitte an der Kasse öffnen.",
            RulesKeys.managerOnly to "Nur für Manager",
        )
}
