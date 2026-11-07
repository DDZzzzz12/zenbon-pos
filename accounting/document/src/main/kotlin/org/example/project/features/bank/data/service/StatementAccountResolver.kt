package org.example.project.features.bank.data.service

import org.example.project.features.bank.domain.repository.BankAccountRepository
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.domain.models.BankStatement
import org.jetbrains.exposed.v1.core.*
import org.slf4j.LoggerFactory

/**
 * Back-fills `bank_statement.bank_account_id` by matching the bank-asserted `account_iban` against
 * our own account registry.
 *
 * **It never inserts a statement** — `:accounting-banking` owns that, writing on upload with the account
 * unresolved. This class only decides which of our accounts a statement belongs to, which is the
 * bookkeeping half of the same split the model draws between `invoice` and `invoice_booking`.
 *
 * An IBAN matching nothing is **reported, never auto-created**: inventing an account from an
 * unrecognised IBAN turns a typo into permanent master data that then silently absorbs future
 * statements. A statement already carrying an account is left untouched, so a deliberate manual
 * assignment survives the next sweep.
 *
 * Requires an ambient transaction.
 */
class StatementAccountResolver(
    private val accounts: BankAccountRepository,
) {
    private val logger = LoggerFactory.getLogger(StatementAccountResolver::class.java)

    fun resolveUnassigned(): StatementResolutionReport {
        var resolved = 0
        var withoutIban = 0
        val unknown = linkedSetOf<String>()

        // Materialised up front: assigning below flushes the entity cache, which must not happen
        // while a lazy SizedIterable over the same table is still being consumed.
        BankStatement
            .find { BankStatementTable.bankAccountId eq null }
            .toList()
            .forEach { statement ->
                when (val outcome = assign(statement)) {
                    Outcome.Resolved -> resolved++
                    Outcome.WithoutIban -> withoutIban++
                    is Outcome.UnknownIban -> unknown += outcome.iban
                    Outcome.AlreadyAssigned -> Unit
                }
            }

        if (resolved > 0 || unknown.isNotEmpty()) {
            logger.info(
                "Statement account resolution: {} resolved, {} without an IBAN, {} unknown IBAN(s)",
                resolved,
                withoutIban,
                unknown.size,
            )
        }
        return StatementResolutionReport(resolved, withoutIban, unknown.toList())
    }

    /**
     * Resolves ONE statement right after it was imported (T9-17), with the same matching as
     * [resolveUnassigned]. Before this, only the startup sweep and "Zuordnung erneut prüfen" ran it,
     * so a statement for the shop's own registered IBAN sat under "Auszüge ohne Konto" until one of
     * them did. Returns true when the statement was assigned by this call.
     */
    fun resolve(statementId: Long): Boolean {
        val statement = BankStatement.findById(statementId) ?: return false
        return assign(statement) == Outcome.Resolved
    }

    /** The one matching rule both entry points share; never touches an already-assigned statement. */
    private fun assign(statement: BankStatement): Outcome {
        if (statement.bankAccount != null) return Outcome.AlreadyAssigned
        val iban = statement.accountIban?.takeIf { it.isNotBlank() } ?: return Outcome.WithoutIban
        val account = accounts.findByIban(iban) ?: return Outcome.UnknownIban(iban)
        statement.bankAccount = account
        return Outcome.Resolved
    }

    private sealed interface Outcome {
        data object Resolved : Outcome

        data object AlreadyAssigned : Outcome

        data object WithoutIban : Outcome

        data class UnknownIban(
            val iban: String,
        ) : Outcome
    }
}
