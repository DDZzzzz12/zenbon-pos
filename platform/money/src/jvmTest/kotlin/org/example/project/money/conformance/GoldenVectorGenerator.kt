package org.example.project.money.conformance

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test

/**
 * Emits the golden vectors that every target is asserted against, using real
 * java.math.BigDecimal as the oracle.
 *
 * Output is a generated Kotlin source file rather than a test resource: wasmJs test-resource
 * I/O is awkward, whereas a .kt compiles and runs on every target with zero I/O. Vectors are
 * stored as one multi-line string constant because a string literal is far cheaper for the
 * compiler than tens of thousands of object constructions.
 *
 * Line format: id|op|a|b|scale|mode|outcome
 * outcome is `=<plainString>` for a value or `!<ExceptionSimpleName>` for an expected throw.
 *
 * Run: ./gradlew :platform-money:jvmTest --tests "*GoldenVectorGenerator*"
 * Then confirm the file is up to date with: git diff --exit-code
 */
class GoldenVectorGenerator {
    private val modes = listOf("HALF_UP", "HALF_EVEN", "DOWN", "UP", "FLOOR", "CEILING")

    private fun javaMode(name: String) =
        when (name) {
            "HALF_UP" -> RoundingMode.HALF_UP
            "HALF_EVEN" -> RoundingMode.HALF_EVEN
            "DOWN" -> RoundingMode.DOWN
            "UP" -> RoundingMode.UP
            "FLOOR" -> RoundingMode.FLOOR
            else -> RoundingMode.CEILING
        }

    private val curatedTies =
        listOf(
            "2.5",
            "-2.5",
            "0.5",
            "-0.5",
            "3.5",
            "-3.5",
            "0.125",
            "-0.125",
            "0.135",
            "1.005",
            "9.99",
            "0.99",
            "99.95",
            "9.5",
            "0.05",
            "9.999999",
            "16.9",
            "1.15",
            "8.3",
            "20.9",
            "0.7",
            // Values that round TO zero from the negative side. BigDecimal drops the sign
            // ("0.00"); decimal.js keeps it ("-0.00"). Verified divergence — these vectors
            // are what stop it regressing.
            "-0.004",
            "-0.001",
            "-0.0004",
            "-0.005",
            "-0.01",
        )

    private val curatedPairs =
        listOf(
            "1.5" to "1.25",
            "1" to "3",
            "10" to "4",
            "1" to "8",
            "2" to "7",
            "0.01" to "3",
            "119.00" to "1.19",
            "-10.00" to "3",
            "0" to "5",
            "0.1" to "0.2",
            "16.90" to "0.00",
            "1000000" to "7",
            // Products and quotients that land on zero with a negative operand.
            "0.00" to "-1",
            "-1" to "0.00",
            "0.00" to "-5",
            "-0.004" to "1",
        )

    @Test
    fun generate() {
        val lines = mutableListOf<String>()
        var id = 0

        // setScale across every mode, both signs, several scales
        for (v in curatedTies) {
            for (mode in modes) {
                for (scale in listOf(0, 1, 2, 4)) {
                    val outcome =
                        try {
                            "=" + BigDecimal(v).setScale(scale, javaMode(mode)).toPlainString()
                        } catch (e: ArithmeticException) {
                            "!DecimalArithmeticException"
                        }
                    lines += "${id++}|setScale|$v||$scale|$mode|$outcome"
                }
            }
        }

        // add / subtract / multiply — scale rules, no mode involved
        for ((a, b) in curatedPairs) {
            lines += "${id++}|add|$a|$b|0|HALF_UP|=" + BigDecimal(a).add(BigDecimal(b)).toPlainString()
            lines += "${id++}|subtract|$a|$b|0|HALF_UP|=" + BigDecimal(a).subtract(BigDecimal(b)).toPlainString()
            val product = BigDecimal(a).multiply(BigDecimal(b))
            val outcome =
                if (product.scale() > 12) {
                    "!DecimalEnvelopeException"
                } else {
                    "=" + product.toPlainString()
                }
            lines += "${id++}|multiply|$a|$b|0|HALF_UP|$outcome"
        }

        // divide — explicit scale and mode, including divide by zero
        for ((a, b) in curatedPairs) {
            for (mode in modes) {
                for (scale in listOf(2, 4)) {
                    val outcome =
                        if (BigDecimal(b).signum() == 0) {
                            "!DecimalArithmeticException"
                        } else {
                            "=" + BigDecimal(a).divide(BigDecimal(b), scale, javaMode(mode)).toPlainString()
                        }
                    lines += "${id++}|divide|$a|$b|$scale|$mode|$outcome"
                }
            }
        }

        // Seeded pseudo-random corpus. Fixed seed so the corpus is reproducible and reviewable.
        val random = Random(20260730)
        repeat(6000) {
            val a = randomDecimal(random)
            val b = randomDecimal(random)
            val mode = modes[random.nextInt(modes.size)]
            val scale = random.nextInt(0, 5)
            when (random.nextInt(4)) {
                0 ->
                    lines += "${id++}|add|$a|$b|0|HALF_UP|=" +
                        BigDecimal(a).add(BigDecimal(b)).toPlainString()
                1 -> {
                    val product = BigDecimal(a).multiply(BigDecimal(b))
                    val outcome =
                        if (product.scale() > 12) {
                            "!DecimalEnvelopeException"
                        } else {
                            "=" + product.toPlainString()
                        }
                    lines += "${id++}|multiply|$a|$b|0|HALF_UP|$outcome"
                }
                2 ->
                    lines += "${id++}|setScale|$a||$scale|$mode|=" +
                        BigDecimal(a).setScale(scale, javaMode(mode)).toPlainString()
                else -> {
                    val outcome =
                        if (BigDecimal(b).signum() == 0) {
                            "!DecimalArithmeticException"
                        } else {
                            "=" + BigDecimal(a).divide(BigDecimal(b), scale, javaMode(mode)).toPlainString()
                        }
                    lines += "${id++}|divide|$a|$b|$scale|$mode|$outcome"
                }
            }
        }

        writeFile(lines)
        println("Generated ${lines.size} golden vectors")
    }

    /** Values inside the envelope: at most 6 integer digits and scale 0..4. */
    private fun randomDecimal(random: Random): String {
        val negative = random.nextBoolean()
        val intDigits = random.nextInt(1, 7)
        val scale = random.nextInt(0, 5)
        val intPart =
            buildString {
                append(random.nextInt(1, 10))
                repeat(intDigits - 1) { append(random.nextInt(0, 10)) }
            }
        val frac = buildString { repeat(scale) { append(random.nextInt(0, 10)) } }
        val body = if (scale == 0) intPart else "$intPart.$frac"
        return if (negative) "-$body" else body
    }

    private fun writeFile(lines: List<String>) {
        val target =
            File(
                "src/commonTest/kotlin/org/example/project/money/conformance/GoldenVectors.kt",
            )
        target.parentFile.mkdirs()
        val chunks = chunk(lines)
        target.writeText(
            buildString {
                appendLine("package org.example.project.money.conformance")
                appendLine()
                appendLine("// GENERATED by GoldenVectorGenerator from java.math.BigDecimal.")
                appendLine("// Do not hand-edit. Regenerate with:")
                appendLine("//   ./gradlew :platform-money:jvmTest --tests \"*GoldenVectorGenerator*\"")
                appendLine("// Line format: id|op|a|b|scale|mode|outcome")
                appendLine("// outcome: =<plainString> or !<ExceptionSimpleName>")
                appendLine("//")
                appendLine("// The corpus is ~300 KB, so it cannot be one literal: a JVM class-file string")
                appendLine("// constant is capped at 65535 UTF-8 bytes and the compiler rejects anything")
                appendLine("// larger with \"java.lang.IllegalArgumentException: UTF8 string too large\".")
                appendLine("// It is still literals rather than object construction, which is what keeps the")
                appendLine("// wasm compile cheap. The blank line at each seam is skipped by the runner.")
                appendLine()
                chunks.forEachIndexed { index, chunkLines ->
                    appendLine("private const val CHUNK_$index: String = \"\"\"")
                    chunkLines.forEach { appendLine(it) }
                    appendLine("\"\"\"")
                    appendLine()
                }
                appendLine("internal val GOLDEN_VECTORS: String = buildString {")
                chunks.indices.forEach { appendLine("    append(CHUNK_$it)") }
                appendLine("}")
            },
        )
        println(
            "Golden vectors written to ${target.absolutePath} " +
                "(${lines.size} vectors in ${chunks.size} chunks)",
        )
    }

    /** Split on line boundaries so no single literal approaches the 65535-byte constant limit. */
    private fun chunk(lines: List<String>): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (line in lines) {
            if (size + line.length + 1 > MAX_CHUNK_CHARS && current.isNotEmpty()) {
                chunks += current
                current = mutableListOf()
                size = 0
            }
            current += line
            size += line.length + 1
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private companion object {
        /** Comfortably below the 65535-byte JVM limit; the corpus is pure ASCII, 1 byte per char. */
        const val MAX_CHUNK_CHARS = 50_000
    }
}
