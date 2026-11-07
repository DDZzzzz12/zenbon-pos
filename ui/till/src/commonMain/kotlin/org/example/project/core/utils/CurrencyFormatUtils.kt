/**
 * Currency formatting utilities that depend on Compose and on app state.
 *
 * The two `toFormattedCurrency` overloads moved to `shared-ui` as `DisplayCurrency.kt`, reading a
 * `LocalDisplayCurrency` string this module provides — see `SettingProvider`. Same package, so no
 * call site changed; a different filename, so the two files do not collide on one JVM facade class. The pure, state-free half of this file
 * (the [String.toDecimalOrZero]/[String.toCanonicalMoneyOrNull] parsing helpers and
 * [Double.discountToPercentage]) lives in `shared-ui` as `MoneyFormat.kt`, because `PosTextField`
 * needs it and `composeApp` depends on `shared-ui`, not the other way round.
 *
 * Both halves keep the same package, so call sites resolve either half without an import change.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import androidx.compose.runtime.Composable
import org.example.project.money.Decimal

@Composable
fun Decimal.toSignedDeltaCurrency(): String {
    val formattedAbs = this.abs().toFormattedCurrency()
    return if (this.isNegative) "-$formattedAbs" else "+$formattedAbs"
}
