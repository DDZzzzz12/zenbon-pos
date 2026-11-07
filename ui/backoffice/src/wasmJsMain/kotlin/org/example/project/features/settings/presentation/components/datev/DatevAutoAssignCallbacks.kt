package org.example.project.features.settings.presentation.components.datev

import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind

/**
 * The „Konto automatisch zuordnen" switch callbacks of [DatevAccountMappingSection], grouped so the
 * section's parameter list does not grow by four. Off = the cell's lines are exported with an empty
 * contra account and the Steuerberater completes it in DATEV.
 */
data class DatevAutoAssignCallbacks(
    val onRateCell: (isIncome: Boolean, kind: KassenbuchLineKind, rate: String, autoAssign: Boolean) -> Unit,
    val onPaymentCell: (paymentTypeId: Long, autoAssign: Boolean) -> Unit,
    val onSpecialCell: (slot: KassenbuchSpecialBooking, autoAssign: Boolean) -> Unit,
    /** „Alle Sonderbuchungen ohne Zuordnung". */
    val onSwitchOffAllSpecial: () -> Unit,
)
