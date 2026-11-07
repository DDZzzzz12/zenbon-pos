package org.example.project.features.settings.presentation.components.datev

import androidx.compose.runtime.Composable
import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.kassenbuch.kindLabel

/** The localized name of a special-booking slot, as the mapping screen labels it. */
internal fun specialLabel(slot: KassenbuchSpecialBooking): StringKey =
    when (slot) {
        KassenbuchSpecialBooking.GELDTRANSIT -> DatevSettingsKeys.specialGeldtransit
        KassenbuchSpecialBooking.CASH_DEPOSIT -> DatevSettingsKeys.specialCashDeposit
        KassenbuchSpecialBooking.PRIVATE_DEPOSIT -> DatevSettingsKeys.specialPrivateDeposit
        KassenbuchSpecialBooking.CASH_WITHDRAWAL -> DatevSettingsKeys.specialCashWithdrawal
        KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL -> DatevSettingsKeys.specialPrivateWithdrawal
        KassenbuchSpecialBooking.CASH_SURPLUS -> DatevSettingsKeys.specialCashSurplus
        KassenbuchSpecialBooking.CASH_SHORTAGE -> DatevSettingsKeys.specialCashShortage
        KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD -> DatevSettingsKeys.specialBalanceCarryForward
        KassenbuchSpecialBooking.EMPLOYEE_TIP -> DatevSettingsKeys.specialEmployeeTip
    }

/**
 * Names a mapping cell the way the mapping screen shows it — so the export notice listing the
 * bookings left without a contra account reads like the screen the operator fixes them on.
 */
@Composable
fun datevCellLabel(ref: CellRef): String {
    ref.special?.let { return str(specialLabel(it)) }
    ref.paymentTypeId?.let { id -> return ref.paymentTypeName ?: "#$id" }
    val side = if (ref.side == DatevMappingSide.EXPENSE) DatevSettingsKeys.expenseSide else DatevSettingsKeys.incomeSide
    val kind = ref.kind?.let { kindLabel(it) }.orEmpty()
    return "${str(side)} · $kind · ${ref.rate.orEmpty()} %"
}

/** The expense side's name for a line kind: what the shop buys, not what it sells. */
@Composable
internal fun expenseKindLabel(kind: KassenbuchLineKind): String =
    when (kind) {
        KassenbuchLineKind.STANDARD -> str(DatevSettingsKeys.kindExpenseStandard)
        KassenbuchLineKind.PFAND -> str(DatevSettingsKeys.kindExpensePfand)
        KassenbuchLineKind.GUTSCHEIN -> str(DatevSettingsKeys.kindExpenseGutschein)
        else -> kindLabel(kind)
    }
