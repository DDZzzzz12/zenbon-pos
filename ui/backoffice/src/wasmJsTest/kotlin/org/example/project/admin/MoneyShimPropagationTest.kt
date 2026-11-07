package org.example.project.admin

import org.example.project.money.Currency
import org.example.project.money.Decimal
import org.example.project.money.Money
import org.example.project.money.MoneyFormat
import org.example.project.money.MoneyRounding
import org.example.project.money.Percentage
import org.example.project.money.format
import org.example.project.money.vatSplitFromGross
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Answers the one question the `:platform-money` design could not settle in isolation: when a CONSUMER
 * module depends on `:platform-money`, does its wasm decimal engine still work at runtime?
 *
 * It originally did NOT. The engine reached decimal.js through a checked-in
 * `moneyDecimalShim.mjs` **resource**, imported relatively. `:ui-backoffice:compileKotlinWasmJs`
 * passed, but this suite failed with `Can't resolve './moneyDecimalShim.mjs'`: the Kotlin Gradle
 * plugin does not copy a dependency project's `wasmJsMain/resources` into a consumer's
 * webpack/karma directory. npm dependencies do propagate, so the engine now imports decimal.js by
 * bare specifier and inlines the shim body into the generated glue — no resource to copy.
 *
 * This suite is the standing guard on that. Compiling adminApp against `:platform-money` only proves the
 * klib resolves; every assertion below deliberately routes through a shim call — `setScale`,
 * `divide`, `multiply`, `subtract` — rather than merely constructing values, because
 * `fromPlainString` and `toPlainString` are pure Kotlin on the wasm engine and would pass even
 * with the shim entirely missing.
 *
 * If the engine's JS ever stops resolving from a consumer, these fail with a module-resolution
 * error, not a wrong value.
 */
class MoneyShimPropagationTest {
    @Test
    fun setScaleReachesTheShimFromAConsumerModule() {
        // setScale -> shimSetScale. Pure-Kotlin paths cannot satisfy this.
        assertEquals("16.90", Decimal.parse("16.895").setScale(2, MoneyRounding.HALF_UP).toPlainString())
    }

    @Test
    fun arithmeticReachesTheShim() {
        // plus -> shimAdd, times -> shimMultiply, divide -> shimDivide
        assertEquals("2.75", (Decimal.parse("1.5") + Decimal.parse("1.25")).toPlainString())
        assertEquals("1.875", (Decimal.parse("1.5") * Decimal.parse("1.25")).toPlainString())
        assertEquals("0.3333", Decimal.parse("1").divide(Decimal.parse("3"), 4).toPlainString())
    }

    @Test
    fun theOriginalTruncationDefectIsFixedInAdminApp() {
        // adminApp's formatEuro renders 16.9 as "16.89 €" because (16.9 * 100).toLong() is 1689.
        // This is the same value through :platform-money, in the same runtime.
        assertEquals("16.90", Decimal.parse("16.9").format())
        assertEquals("1.15", Decimal.parse("1.15").format())
        assertEquals("16,90 €", Money(Decimal.parse("16.9"), Currency.EUR).format(MoneyFormat.DE_EUR))
    }

    @Test
    fun vatSplitWorksInAdminAppAndKeepsItsInvariant() {
        // Exercises divide + subtract through the shim, and the fiscal invariant.
        val split = Money(Decimal.parse("119.00"), Currency.EUR).vatSplitFromGross(Percentage.parse("19"))
        assertEquals("100.00", split.net.toPlainString())
        assertEquals("19.00", split.vat.toPlainString())
        assertEquals(split.gross, split.net + split.vat)
    }

    @Test
    fun negativeZeroIsStillNormalisedInAConsumerModule() {
        // The shim's norm() guard — decimal.js would render "-0.00" here, BigDecimal gives "0.00".
        assertEquals("0.00", Decimal.parse("-0.004").setScale(2, MoneyRounding.HALF_UP).toPlainString())
        // ...and must not over-reach: FLOOR keeps the sign.
        assertEquals("-0.01", Decimal.parse("-0.004").setScale(2, MoneyRounding.FLOOR).toPlainString())
    }
}
