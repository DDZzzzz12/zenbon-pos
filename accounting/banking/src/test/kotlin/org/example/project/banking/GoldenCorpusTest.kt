package org.example.project.banking

import org.example.project.banking.model.BankStatement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs every corpus file through the full parser and checks it against a committed expectation.
 *
 * Adding a bank's file to `corpus/` is then all it takes to extend coverage, and a regression in the
 * `:86:` decoder surfaces as a diff naming the exact bank rather than as a unit test nobody
 * connected to a real institution.
 *
 * ### The harness refuses to pass vacuously
 * Three guards, each closing a way a golden test can look green while asserting nothing:
 * 1. An empty corpus fails — otherwise every loop below is a no-op.
 * 2. A fixture with no `.expected.json` fails — an unasserted file is a file nobody checked.
 * 3. An expectation claiming `Success` must state format, transaction count and both balances
 *    ([REQUIRED_FOR_SUCCESS]). `{"outcome": "Success"}` alone would pass against a statement whose
 *    every field was wrong, which is precisely the failure mode this corpus exists to catch.
 *
 * A key the harness does not know is an error rather than a skip: a typo in an expectation would
 * otherwise silently remove an assertion, and nothing would ever say so.
 */
class GoldenCorpusTest {
    private val parser = DefaultBankStatementParser()

    @Test
    fun `every corpus file parses to its expected outcome`() {
        val fixtures = corpusFixtures()
        // Guard the guard: an empty corpus would make every assertion below vacuous.
        assertTrue(fixtures.isNotEmpty(), "no corpus fixtures found under ${corpusRoot().absolutePath}")

        val failures = fixtures.flatMap { fixture -> check(fixture) }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * Both readers must stay exercised. A corpus that drifted to one format would still pass the
     * test above while leaving the other parser unguarded by any real file.
     */
    @Test
    fun `the corpus covers both statement formats`() {
        val declared =
            corpusFixtures()
                .mapNotNull { File(it.parentFile, "${it.name}.expected.json").takeIf(File::isFile) }
                .mapNotNull { parseExpectation(it.readText())["format"] }
                .toSet()
        assertTrue("MT940" in declared, "no MT940 fixture in the corpus, declared formats: $declared")
        assertTrue("CAMT053" in declared, "no CAMT053 fixture in the corpus, declared formats: $declared")
    }

    /** Returns one message per mismatch, so a broken fixture reports all of its problems at once. */
    private fun check(fixture: File): List<String> {
        val expectedFile = File(fixture.parentFile, "${fixture.name}.expected.json")
        if (!expectedFile.isFile) {
            return listOf("${fixture.name}: no .expected.json — an unasserted fixture is not coverage")
        }

        val expected = parseExpectation(expectedFile.readText())
        val outcome =
            expected["outcome"]
                ?: return listOf("${fixture.name}: expectation states no outcome")
        val result = parser.parse(fixture.readBytes(), mediaTypeOf(fixture))

        val actualOutcome =
            when (result) {
                is BankStatementParseResult.Success -> "Success"
                BankStatementParseResult.NoStatementFound -> "NoStatementFound"
                is BankStatementParseResult.Failure -> "Failure(${result.reason})"
            }
        if (!actualOutcome.startsWith(outcome)) {
            return listOf("${fixture.name}: expected $outcome, got $actualOutcome")
        }
        if (result !is BankStatementParseResult.Success) return unusedFieldViolations(fixture, expected)

        return missingRequirements(fixture, expected) + fieldViolations(fixture, expected, result.statement)
    }

    /**
     * A `NoStatementFound`/`Failure` expectation carrying statement fields is a contradiction: those
     * fields can never be checked, so the file would look better covered than it is.
     */
    private fun unusedFieldViolations(
        fixture: File,
        expected: Map<String, String?>,
    ): List<String> =
        expected.keys
            .filter { it != "outcome" && !it.startsWith("_") }
            .map { "${fixture.name}: \"$it\" cannot be checked — the file is not expected to parse" }

    private fun missingRequirements(
        fixture: File,
        expected: Map<String, String?>,
    ): List<String> =
        REQUIRED_FOR_SUCCESS
            .filterNot { it in expected }
            .map { "${fixture.name}: a Success expectation must state \"$it\"" }

    private fun fieldViolations(
        fixture: File,
        expected: Map<String, String?>,
        statement: BankStatement,
    ): List<String> {
        val actual = actualFields(statement)
        return expected.entries.mapNotNull { (key, value) ->
            when {
                key == "outcome" || key.startsWith("_") -> null
                key !in actual -> "${fixture.name}: \"$key\" is not a field this harness checks"
                actual[key] != value ->
                    "${fixture.name}: expected $key ${render(value)}, got ${render(actual[key])}"
                else -> null
            }
        }
    }

    /**
     * Everything an expectation may assert, flattened to strings. `null` means the parser produced
     * no value, and an expectation may state a literal `null` to require exactly that — a field the
     * parser must *not* invent is as much a guarantee as one it must read.
     */
    private fun actualFields(statement: BankStatement): Map<String, String?> {
        val first = statement.transactions.firstOrNull()
        val last = statement.transactions.lastOrNull()
        return mapOf(
            "format" to statement.format.name,
            "transactionCount" to statement.transactions.size.toString(),
            "currency" to statement.currency,
            "openingBalance" to statement.openingBalance?.toPlainString(),
            "closingBalance" to statement.closingBalance?.toPlainString(),
            "accountIban" to statement.accountIban,
            "accountNumber" to statement.accountNumber,
            "accountBic" to statement.accountBic,
            "bankName" to statement.bankName,
            "statementNumber" to statement.statementNumber,
            "legalSequenceNumber" to statement.legalSequenceNumber,
            "periodStart" to statement.periodStart?.toString(),
            "periodEnd" to statement.periodEnd?.toString(),
            "firstAmount" to first?.amount?.toPlainString(),
            "firstCreditDebit" to first?.creditDebit?.name,
            "firstBookingDate" to first?.bookingDate?.toString(),
            "firstValueDate" to first?.valueDate?.toString(),
            "firstCounterpartyName" to first?.counterpartyName,
            "firstCounterpartyIban" to first?.counterpartyIban,
            "firstCounterpartyBic" to first?.counterpartyBic,
            "firstRemittance" to first?.remittanceUnstructured,
            "firstEndToEndId" to first?.endToEndId,
            "firstMandateId" to first?.mandateId,
            "firstCreditorId" to first?.creditorId,
            "firstAccountServicerReference" to first?.accountServicerReference,
            "lastAmount" to last?.amount?.toPlainString(),
            "lastCounterpartyName" to last?.counterpartyName,
        )
    }

    private fun render(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    /**
     * A deliberately tiny reader for a flat string map — `:accounting-banking` has no JSON dependency and must
     * not gain one for a test fixture. Quoted values keep their spaces; a bare `null` reads as the
     * absence of a value rather than as the four-letter string.
     */
    private fun parseExpectation(json: String): Map<String, String?> =
        ENTRY
            .findAll(json)
            .associate { match ->
                val quoted = match.groups[2]
                match.groupValues[1] to (quoted?.value ?: match.groupValues[3].takeUnless { it == "null" })
            }

    private fun mediaTypeOf(file: File): String =
        when (file.extension.lowercase()) {
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            else -> "application/x-swift-mt940"
        }

    private fun corpusRoot(): File = File("src/test/resources/corpus")

    private fun corpusFixtures(): List<File> =
        corpusRoot()
            .walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".expected.json") && it.extension != "md" }
            .sortedBy { it.name }
            .toList()

    private companion object {
        /** Without these a `Success` expectation asserts nothing beyond "it did not throw". */
        val REQUIRED_FOR_SUCCESS = listOf("format", "transactionCount", "openingBalance", "closingBalance")

        val ENTRY = Regex(""""(_?[A-Za-z][A-Za-z0-9]*)"\s*:\s*(?:"([^"]*)"|(null|-?[0-9.]+))""")
    }
}
