@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.money

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Typed view of the object [createDecimalShim] builds. Nothing else may construct one — the
 * object's identity is its captured, isolated decimal.js clone.
 */
internal external interface DecimalShim : JsAny {
    fun add(
        a: String,
        b: String,
        scale: Int,
    ): String

    fun subtract(
        a: String,
        b: String,
        scale: Int,
    ): String

    fun multiply(
        a: String,
        b: String,
        scale: Int,
    ): String

    fun divide(
        a: String,
        b: String,
        scale: Int,
        mode: Int,
    ): String

    fun setScale(
        a: String,
        scale: Int,
        mode: Int,
    ): String

    fun compare(
        a: String,
        b: String,
    ): Int

    fun signum(a: String): Int
}

/**
 * Builds the shim over the decimal.js constructor passed in. The body is emitted verbatim into
 * the generated Kotlin/Wasm JS glue module, so there is no separate resource file to propagate.
 *
 * Isolated clone: no other script can mutate our rounding config. `precision: 40` is load-bearing
 * — decimal.js defaults to 20 significant digits and `div` reads the constructor's precision, so
 * dropping it would silently halve division accuracy.
 *
 * Every export below formats through `toFixed()`, which is inherently non-exponential —
 * decimal.js's `P.toFixed` calls `finiteToString` with `isExp` hard-coded and never consults
 * `toExpNeg`/`toExpPos`; only `P.toString` reads those. The extreme thresholds below are a second
 * line of defence, not the reason `toFixed` is safe: they keep `toString()`/`valueOf()` plain
 * too, in case either is ever called on a D here, or a future edit slips from toFixed to toString.
 *
 * `MODES` maps a `MoneyRounding` ordinal to a decimal.js rounding constant:
 * 0 HALF_UP=4, 1 HALF_EVEN=6, 2 DOWN=1, 3 UP=0, 4 FLOOR=3, 5 CEILING=2.
 *
 * `norm` covers a VERIFIED DIVERGENCE: `java.math.BigDecimal` never emits negative zero —
 * `new BigDecimal("-0.004").setScale(2, HALF_UP).toPlainString() == "0.00"` — but decimal.js keeps
 * the sign: `new D("-0.004").toFixed(2, 4) == "-0.00"`. Strip the sign whenever every digit is
 * zero. This matters for sub-cent negative amounts, which occur in storno and refund VAT paths.
 */
private fun createDecimalShim(ctor: JsAny): DecimalShim =
    js(
        """{
    const D = ctor.clone({
        precision: 40,
        rounding: 4,          // ROUND_HALF_UP
        toExpNeg: -9e15,
        toExpPos: 9e15,
    });

    const MODES = [4, 6, 1, 0, 3, 2];

    function reject(d) {
        if (!d.isFinite() || d.isNaN()) {
            throw new Error('non-finite decimal');
        }
        return d;
    }

    function norm(text) {
        if (text.charCodeAt(0) === 45 && !/[1-9]/.test(text)) {
            return text.slice(1);
        }
        return text;
    }

    return {
        add: function (a, b, scale) {
            return norm(reject(new D(a).plus(new D(b))).toFixed(scale));
        },
        subtract: function (a, b, scale) {
            return norm(reject(new D(a).minus(new D(b))).toFixed(scale));
        },
        multiply: function (a, b, scale) {
            return norm(reject(new D(a).times(new D(b))).toFixed(scale));
        },
        divide: function (a, b, scale, mode) {
            return norm(reject(new D(a).div(new D(b))).toFixed(scale, MODES[mode]));
        },
        setScale: function (a, scale, mode) {
            return norm(reject(new D(a)).toFixed(scale, MODES[mode]));
        },
        compare: function (a, b) {
            return new D(a).comparedTo(new D(b));
        },
        signum: function (a) {
            const d = new D(a);
            return d.isZero() ? 0 : (d.isNegative() ? -1 : 1);
        },
    };
}""",
    )

private val shim: DecimalShim by lazy(LazyThreadSafetyMode.NONE) { createDecimalShim(DecimalCtor) }

internal fun shimAdd(
    a: String,
    b: String,
    scale: Int,
): String = shim.add(a, b, scale)

internal fun shimSubtract(
    a: String,
    b: String,
    scale: Int,
): String = shim.subtract(a, b, scale)

internal fun shimMultiply(
    a: String,
    b: String,
    scale: Int,
): String = shim.multiply(a, b, scale)

internal fun shimDivide(
    a: String,
    b: String,
    scale: Int,
    mode: Int,
): String = shim.divide(a, b, scale, mode)

internal fun shimSetScale(
    a: String,
    scale: Int,
    mode: Int,
): String = shim.setScale(a, scale, mode)

internal fun shimCompare(
    a: String,
    b: String,
): Int = shim.compare(a, b)

internal fun shimSignum(a: String): Int = shim.signum(a)
