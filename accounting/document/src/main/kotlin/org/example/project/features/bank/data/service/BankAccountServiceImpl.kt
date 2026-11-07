package org.example.project.features.bank.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bank.domain.mapper.toDto
import org.example.project.features.bank.domain.mapper.toUnassignedDto
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.bank.domain.repository.BankAccountRepository
import org.example.project.features.bank.domain.service.BankAccountService
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll

/** See [BankAccountService]. Every method maps inside its own [dbQuery]. */
class BankAccountServiceImpl(
    private val repository: BankAccountRepository,
    private val resolver: StatementAccountResolver,
) : BankAccountService {
    override suspend fun list(): BankAccountDto.ListResponseDto =
        dbQuery {
            BankAccountDto.ListResponseDto(
                items = repository.listActive().map { it.toDtoWithStatements() },
                unassigned =
                    BankStatement
                        .find { BankStatementTable.bankAccountId eq null }
                        .orderBy(BankStatementTable.id to SortOrder.ASC)
                        .map { it.toUnassignedDto() },
            )
        }

    override suspend fun get(id: Long): BankAccountDto.ResponseDto = dbQuery { requireAccount(id).toDtoWithStatements() }

    override suspend fun create(request: BankAccountDto.CreateRequestDto): BankAccountDto.ResponseDto {
        rejectInvalid(request.validate())
        return dbQuery {
            requireIbanFree(request.iban)
            repository
                .create(
                    shop = requireShop(),
                    iban = request.iban,
                    bic = request.bic,
                    bankName = request.bankName,
                    accountHolder = request.accountHolder,
                    ledgerAccount = request.ledgerAccount,
                    currency = request.currency,
                ).toDtoWithStatements()
        }
    }

    override suspend fun assignStatement(
        statementId: Long,
        request: BankAccountDto.AssignStatementRequestDto,
    ): BankAccountDto.ResponseDto =
        dbQuery {
            val statement =
                BankStatement.findById(statementId)
                    ?: throw notFound("Bank statement $statementId not found")
            val account = requireAccount(request.bankAccountId)
            statement.bankAccount = account
            account.toDtoWithStatements()
        }

    override suspend fun resolveUnassigned(): Int = dbQuery { resolver.resolveUnassigned().resolved }

    override suspend fun statementsFor(bankAccountId: Long): BankAccountDto.StatementListResponseDto =
        dbQuery {
            val account = requireAccount(bankAccountId)
            val statements = account.statementsInPeriodOrder()
            // The same computation the list endpoint's `continuityBreaks` reports, over the same
            // ordering — one source of truth for "a statement is missing here", two renderings.
            val breaks = findContinuityBreaks(statements.map { it.toBalanceLink() }).toSet()
            BankAccountDto.StatementListResponseDto(
                bankAccountId = account.id.value,
                iban = account.iban,
                items = statements.map { it.toStatementDto(isBreak = it.id.value in breaks) },
            )
        }

    override suspend fun statementTransactions(
        bankAccountId: Long,
        statementId: Long,
        limit: Int,
        offset: Long,
    ): BankAccountDto.StatementTransactionListResponseDto =
        dbQuery {
            val account = requireAccount(bankAccountId)
            val statement =
                BankStatement.findById(statementId)?.takeIf { it.bankAccount?.id == account.id }
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "No statement $statementId on bank account $bankAccountId",
                    )
            val belongsToStatement = BankStatementTransactionTable.statementId eq statement.id
            BankAccountDto.StatementTransactionListResponseDto(
                statementId = statement.id.value,
                items =
                    BankStatementTransactionTable
                        .selectAll()
                        .where { belongsToStatement }
                        // Value date is what a bank statement is read down, and the id tiebreak keeps
                        // the paging stable when several bookings share a date — without it the
                        // engine picks, and page 2 can repeat or skip a row.
                        .orderBy(
                            BankStatementTransactionTable.valueDate to SortOrder.ASC,
                            BankStatementTransactionTable.id to SortOrder.ASC,
                        ).limit(limit)
                        .offset(offset)
                        .map { it.toTransactionDto(statement.currency) },
                total = BankStatementTransactionTable.selectAll().where { belongsToStatement }.count(),
                offset = offset,
            )
        }

    /**
     * Read straight off the row rather than through the DAO: `duplicateOfTransaction` is an entity
     * reference, so mapping it per booking would fetch the referenced transaction one query at a
     * time — a hundred extra round trips for a field that renders as a flag.
     *
     * [BankAccountDto.StatementTransactionDto.openAmount] falls back to the full amount, the same
     * rule the Zuordnung queue applies: a NULL `open_amount` means nobody has computed a residual
     * yet, so the whole booking is still waiting to be assigned.
     */
    private fun ResultRow.toTransactionDto(statementCurrency: String): BankAccountDto.StatementTransactionDto {
        val amount = this[BankStatementTransactionTable.amount]
        return BankAccountDto.StatementTransactionDto(
            transactionId = this[BankStatementTransactionTable.id].value,
            bookingDate = this[BankStatementTransactionTable.bookingDate].toString(),
            valueDate = this[BankStatementTransactionTable.valueDate].toString(),
            amount = amount.toDecimalString(),
            openAmount = (this[BankStatementTransactionTable.openAmount] ?: amount).toDecimalString(),
            currency = this[BankStatementTransactionTable.currency].ifBlank { statementCurrency },
            creditDebit = this[BankStatementTransactionTable.creditDebit],
            counterpartyName = this[BankStatementTransactionTable.counterpartyName],
            counterpartyIban = this[BankStatementTransactionTable.counterpartyIban],
            // The structured reference is a machine key (an ISO creditor reference); the
            // unstructured line is what the payer actually typed, so it reads first.
            remittance =
                this[BankStatementTransactionTable.remittanceUnstructured]
                    ?: this[BankStatementTransactionTable.remittanceStructuredRef],
            duplicateOfTransactionId = this[BankStatementTransactionTable.duplicateOfTransactionId]?.value,
        )
    }

    /**
     * Balances cross the wire as strings, never numbers — `platform/money/README.md`. `toDecimalString` pins
     * scale 2, which is what a balance is read as; the column's four decimals are a parser artefact,
     * not something a bank prints.
     */
    private fun BankStatement.toStatementDto(isBreak: Boolean): BankAccountDto.StatementDto =
        BankAccountDto.StatementDto(
            statementId = id.value,
            statementNumber = statementNumber,
            statementDate = statementDate?.toString(),
            periodStart = periodStart?.toString(),
            periodEnd = periodEnd?.toString(),
            openingBalance = openingBalance?.toDecimalString(),
            closingBalance = closingBalance?.toDecimalString(),
            currency = currency,
            transactionCount = transactions.count(),
            sourceDocumentId = sourceDocument?.id?.value,
            continuityBreak = isBreak,
            // Off the row, not through `overlappingStatement`: that reference would load the other
            // statement per row for a value that renders as a flag.
            overlappingStatementId = readValues[BankStatementTable.overlappingStatementId]?.value,
        )

    /**
     * The statements of one account, **ordered by `periodStart`, then `statementDate`, then `id`** —
     * a stable order matters, because an unstable one invents continuity breaks that are not there.
     * Nulls sort first explicitly, so H2 and Postgres agree.
     */
    private fun BankAccount.statementsInPeriodOrder(): List<BankStatement> =
        BankStatement
            .find { BankStatementTable.bankAccountId eq id }
            .orderBy(
                BankStatementTable.periodStart to SortOrder.ASC_NULLS_FIRST,
                BankStatementTable.statementDate to SortOrder.ASC_NULLS_FIRST,
                BankStatementTable.id to SortOrder.ASC,
            ).toList()

    private fun BankAccount.toDtoWithStatements(): BankAccountDto.ResponseDto {
        val statements = statementsInPeriodOrder()
        return toDto(
            statementCount = statements.size.toLong(),
            continuityBreaks = findContinuityBreaks(statements.map { it.toBalanceLink() }),
        )
    }

    /** The overlap is read off the row, like [toStatementDto] does, rather than loading the other statement. */
    private fun BankStatement.toBalanceLink(): StatementBalanceLink =
        StatementBalanceLink(
            statementId = id.value,
            openingBalance = openingBalance,
            closingBalance = closingBalance,
            overlapsStatementId = readValues[BankStatementTable.overlappingStatementId]?.value,
        )

    private fun requireAccount(id: Long): BankAccount = repository.findById(id) ?: throw notFound("Bank account $id not found")

    /**
     * Accounts hang off a shop, and the installation has exactly one. Missing means the setup wizard
     * has not run, which is a precondition failure rather than a bad request.
     */
    private fun requireShop(): ShopInfo =
        ShopInfo.all().firstOrNull()
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "No shop configured — complete setup before adding bank accounts",
            )

    /**
     * `bank_account.iban`'s unique index would otherwise surface as an `ExposedSQLException` and a
     * bare 500. Checked here rather than caught, because the seeder auto-creates the shop's own
     * account on start: a manager typing in one that already exists is routine, not exceptional.
     */
    private fun requireIbanFree(iban: String) {
        val owner = repository.findByIban(iban) ?: return
        throw PosException(
            PosErrorCode.BANK_ACCOUNT_IBAN_TAKEN,
            HttpStatusCode.Conflict,
            "Bank account ${owner.id.value} already carries IBAN ${owner.iban}",
        )
    }

    private fun rejectInvalid(fields: List<String>) {
        if (fields.isEmpty()) return
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Invalid bank account: ${fields.joinToString()}",
        )
    }

    private fun notFound(message: String) = PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, message)
}
