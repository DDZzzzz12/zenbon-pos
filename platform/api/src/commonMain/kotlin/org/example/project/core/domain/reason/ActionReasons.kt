package org.example.project.core.domain.reason

import org.example.project.core.presentation.strings.keys.ActionReasonKeys

/** The reason lists offered for the four till actions. "Other" is always last. */
object ActionReasons {
    const val MAX_LENGTH = 500

    private val other = ActionReason(ActionReasonKeys.other, storedText = "", isOther = true)
    private val guestMovedSeats = ActionReason(ActionReasonKeys.guestMovedSeats, "Gast hat Platz gewechselt")
    private val waiterHandover = ActionReason(ActionReasonKeys.waiterHandover, "Kellnerwechsel")

    val cancelItems: List<ActionReason> =
        listOf(
            ActionReason(ActionReasonKeys.wrongItem, "Falscher Artikel erfasst"),
            ActionReason(ActionReasonKeys.customerChangedMind, "Gast hat es sich anders überlegt"),
            ActionReason(ActionReasonKeys.kitchenError, "Küchenfehler"),
            ActionReason(ActionReasonKeys.outOfStock, "Nicht vorrätig"),
            ActionReason(ActionReasonKeys.duplicateEntry, "Doppelt erfasst"),
            other,
        )

    val cancelTable: List<ActionReason> =
        listOf(
            ActionReason(ActionReasonKeys.wrongTable, "Falscher Tisch"),
            ActionReason(ActionReasonKeys.duplicateSession, "Doppelte Sitzung"),
            ActionReason(ActionReasonKeys.testEntry, "Test- oder Schulungseintrag"),
            other,
        )

    val transferTable: List<ActionReason> =
        listOf(
            guestMovedSeats,
            ActionReason(ActionReasonKeys.partiesMerged, "Gruppen zusammengelegt"),
            ActionReason(ActionReasonKeys.openedOnWrongTable, "Auf falschem Tisch eröffnet"),
            waiterHandover,
            other,
        )

    val transferItems: List<ActionReason> =
        listOf(
            ActionReason(ActionReasonKeys.movedToRightTable, "Auf richtigen Tisch verschoben"),
            ActionReason(ActionReasonKeys.splitBill, "Rechnung getrennt / getrennte Zahlung"),
            guestMovedSeats,
            waiterHandover,
            other,
        )
}
