package org.example.project.banking

import org.example.project.banking.camt.Camt053StatementMapper
import org.example.project.banking.check.BankStatementInvariants
import org.example.project.banking.detect.BankStatementFormatDetector
import org.example.project.banking.model.BankStatement
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.mt940.Mt940StatementParser
import org.example.project.securexml.SecureXml

/**
 * Composes the module's stages into the [BankStatementParser] boundary: sniff the format, parse it,
 * map it, check its arithmetic.
 *
 * **Every outcome is a value.** The bytes come from a bank export the operator downloaded and may be
 * truncated, mis-encoded or not a statement at all — all expected conditions rather than exceptional
 * ones. A caller holding a [BankStatementParseResult] cannot forget to handle them the way it can
 * forget a `catch`. No exception escapes [parse].
 *
 * A class rather than an object because it is registered in DI.
 */
class DefaultBankStatementParser : BankStatementParser {
    override fun parse(
        bytes: ByteArray,
        mediaType: String,
    ): BankStatementParseResult {
        val format =
            attempt { BankStatementFormatDetector.detect(bytes, mediaType) }
                .getOrElse { return failed("Bank statement format could not be detected", it) }
                ?: return BankStatementParseResult.NoStatementFound

        val statement =
            attempt { readStatement(bytes, format) }
                .getOrElse { return failed("${format.name} statement could not be read", it) }

        val violation =
            attempt { BankStatementInvariants.check(statement) }
                .getOrElse { return failed("Bank statement balances could not be checked", it) }
        if (violation != null) {
            // Stored data that is plausibly wrong is worse than none: nobody can tell a misparsed
            // statement from a correct one by looking, and the raw file is still on disk.
            return BankStatementParseResult.Failure(
                reason = "Bank statement does not reconcile: $violation",
                balancesDoNotReconcile = true,
            )
        }

        return BankStatementParseResult.Success(statement)
    }

    private fun readStatement(
        bytes: ByteArray,
        format: BankStatementFormat,
    ): BankStatement =
        when (format) {
            BankStatementFormat.CAMT053 -> Camt053StatementMapper.map(SecureXml.parse(bytes))
            BankStatementFormat.MT940 -> Mt940StatementParser.parse(bytes)
            // The detector never returns these — it recognises CAMT and MT940 only — but the `when`
            // is exhaustive so that adding a format to the enum breaks this build rather than
            // falling through to a silent null.
            BankStatementFormat.PDF, BankStatementFormat.MANUAL ->
                throw UnsupportedOperationException("$format is not a parseable upload format")
        }

    /**
     * Keeps the underlying message — `XmlSecurityException` names what the parser refused,
     * `CamtMappingException` names the missing element. An operator holding a rejected bank file
     * needs exactly that; "parse failed" tells them nothing.
     */
    private fun failed(
        stage: String,
        cause: Throwable,
    ): BankStatementParseResult.Failure = BankStatementParseResult.Failure("$stage: ${cause.message ?: cause::class.simpleName}", cause)

    /**
     * Runs one stage, converting any failure into a value.
     *
     * `Exception` is caught broadly rather than per type: each stage declares its own typed refusal,
     * but the input is third-party, and a library defect on hostile bytes must not become an
     * unhandled exception at the boundary. `StackOverflowError` is caught alongside, and only it —
     * it is the one `Error` a *document* can cause (a pathologically nested DOM walked recursively),
     * so it is malformed input wearing an `Error`'s clothes, and the stack is fully unwound by the
     * time it is caught. Every other `Error` says something about the JVM rather than about this
     * file, and swallowing one would hide a process-level problem.
     */
    private inline fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: Exception) {
            Result.failure(e)
        } catch (e: StackOverflowError) {
            Result.failure(e)
        }
}
