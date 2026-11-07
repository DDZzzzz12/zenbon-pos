package org.example.project.features.reports.presentation.state

import org.example.project.money.DecimalSeparator
import org.example.project.money.MoneyFormat
import org.example.project.money.MoneyRounding

/** The top-up prefill (F59): like the payout field, but rounded UP so it always covers the shortfall. */
val TOP_UP_PREFILL: MoneyFormat = MoneyFormat(separator = DecimalSeparator.COMMA, rounding = MoneyRounding.UP)

/** The payout field's prefill format: "3,00", never "3.00" or "3,00 €" (F54 #1). */
val TIP_PAYOUT_PREFILL: MoneyFormat = MoneyFormat(separator = DecimalSeparator.COMMA, rounding = MoneyRounding.DOWN)
