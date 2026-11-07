package org.example.project.money.conformance

import org.example.project.money.Decimal
import org.example.project.money.MoneyException
import org.example.project.money.MoneyRounding
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays every golden vector on the current target and asserts the result is byte-identical to
 * what java.math.BigDecimal produced. On the JVM this re-verifies the oracle, catching generator
 * bugs; on wasmJs and iOS it is the parity proof.
 */
class ConformanceTest {
    private fun mode(name: String) = MoneyRounding.valueOf(name)

    @Test
    fun everyGoldenVectorMatchesTheJvmOracle() {
        val failures = mutableListOf<String>()
        var executed = 0

        for (raw in GOLDEN_VECTORS.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split('|')
            if (parts.size != 7) fail("Malformed golden vector: $line")

            val (id, op, a, b, scaleText, modeText, outcome) = Septuple(parts)
            executed++

            val actual: String =
                try {
                    val left = Decimal.parse(a)
                    val scale = scaleText.toInt()
                    val result =
                        when (op) {
                            "add" -> left + Decimal.parse(b)
                            "subtract" -> left - Decimal.parse(b)
                            "multiply" -> left * Decimal.parse(b)
                            "divide" -> left.divide(Decimal.parse(b), scale, mode(modeText))
                            "setScale" -> left.setScale(scale, mode(modeText))
                            else -> fail("Unknown op \"$op\" in vector $id")
                        }
                    "=" + result.toPlainString()
                } catch (e: MoneyException) {
                    "!" + (e::class.simpleName ?: "MoneyException")
                }

            if (actual != outcome) {
                failures += "vector $id ($op $a $b scale=$scaleText $modeText): expected $outcome, got $actual"
            }
        }

        assertTrue(executed > 5000, "expected a substantial corpus, executed only $executed")
        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of $executed vectors diverged from the JVM oracle:\n" +
                    failures.take(25).joinToString("\n"),
            )
        }
    }

    private class Septuple(
        private val p: List<String>,
    ) {
        operator fun component1() = p[0]

        operator fun component2() = p[1]

        operator fun component3() = p[2]

        operator fun component4() = p[3]

        operator fun component5() = p[4]

        operator fun component6() = p[5]

        operator fun component7() = p[6]
    }
}
