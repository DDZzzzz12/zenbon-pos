package org.example.project.admin.features.bank.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminDrillHost
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.AdminListSkeleton
import org.example.project.admin.core.presentation.components.AdminSplitPane
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentUploadSection
import org.example.project.admin.features.documents.presentation.DocumentUploadViewModel
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.money.Decimal
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveTable
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosContentRow
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/** CAMT.053 (.xml) and MT940 (.sta / .txt) — the formats the server's `:accounting-banking` parser reads. */
private const val STATEMENT_UPLOAD_ACCEPT = ".xml,.sta,.txt,application/xml,text/plain"

/** `2027-01-31T00:00` &rarr; `2027-01-31`; a null date is a blank, not the word "null". */

/**
 * Bankkonten — our own accounts, and the statement history of whichever one is selected.
 *
 * The accounts are the spine of the screen rather than the third of four stacked cards. Picking one
 * shows its statements, its continuity strip and its gaps beside it — the only place a per-account
 * statement history exists at all.
 *
 * The old *Lücken* card is gone: it listed `continuityBreaks`, which are statement **ids**, joined
 * into a comma-separated string. Those ids now drive [ContinuityStrip], where a missing statement is
 * a hole in a pattern instead of a database key an operator was asked to interpret.
 *
 * Import moved to a header action. An operator opens this page to check an account, not to be shown
 * a file picker first.
 */
@Composable
fun BankAccountsScreen() {
    val viewModel: BankAccountsViewModel = koinViewModel()
    val state = viewModel.state
    // Gate each write control on the capability its server rule requires (T9-21). The server
    // refuses regardless; this keeps a read-only group from being offered a button that 403s.
    val access = BankAccountsAccess.of(LocalAccessPolicy.current)

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive logout.
    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.BankTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    var showImport by remember { mutableStateOf(false) }
    var assignTarget by remember { mutableStateOf<BankAccountDto.UnassignedStatementDto?>(null) }

    var detailOpen by remember { mutableStateOf(false) }
    AdminDrillHost(detailOpen = detailOpen, hasSelection = state.selectedAccount != null) { phoneDetail ->
        AdminListScaffold(
            title = str(AdminStringKeys.BankTitle),
            showHeader = !phoneDetail,
            actions = {
                if (access.mayImportStatements) {
                    PosSecondaryButton(
                        text = str(AdminStringKeys.BankImportAction),
                        onClick = { showImport = !showImport },
                        enabled = !state.isBusy,
                    )
                }
                if (access.mayManageAccounts) {
                    PosButton(
                        text = str(AdminStringKeys.BankAddAccount),
                        onClick = viewModel::openCreateDialog,
                        enabled = !state.isBusy,
                    )
                }
            },
            isLoading = state.isLoading && !phoneDetail,
            isEmpty = state.accounts.isEmpty() && !state.isLoading && !phoneDetail,
            empty = {
                AdminListEmptyState(
                    message = str(AdminStringKeys.BankEmpty),
                    icon = Icons.Default.AccountBalance,
                    actionText = if (access.mayManageAccounts) str(AdminStringKeys.BankAddAccount) else null,
                    onAction = if (access.mayManageAccounts) viewModel::openCreateDialog else null,
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!phoneDetail && showImport && access.mayImportStatements) {
                    StatementImportCard(onUploaded = { viewModel.load() })
                }

                // Rendered while a resolve result is still on screen even with nothing left unassigned,
                // so the sweep's outcome does not vanish the moment it succeeds.
                if (!phoneDetail && (state.unassigned.isNotEmpty() || state.lastResolvedCount != null)) {
                    UnassignedBanner(
                        unassigned = state.unassigned,
                        lastResolvedCount = state.lastResolvedCount,
                        actionsEnabled = !state.isBusy,
                        mayManage = access.mayManageAccounts,
                        onAssign = { statement -> assignTarget = statement },
                        onResolve = viewModel::resolveUnassigned,
                    )
                }

                AdminSplitPane(
                    list = {
                        AccountRail(
                            accounts = state.accounts,
                            selectedId = state.selectedAccountId,
                            // selectAccount is a no-op for the already selected id, so the drill must
                            // open regardless of what the view model does.
                            onSelect = { id ->
                                viewModel.selectAccount(id)
                                detailOpen = true
                            },
                        )
                    },
                    detail = {
                        AccountStatements(
                            account = state.selectedAccount,
                            statements = state.statements,
                            isLoading = state.isLoadingStatements,
                            expandedStatementId = state.expandedStatementId,
                            transactions = state.transactionsByStatement,
                            loadingTransactionsFor = state.loadingTransactionsFor,
                            onToggleStatement = viewModel::toggleStatement,
                        )
                    },
                    detailOpen = detailOpen,
                    onCloseDetail = { detailOpen = false },
                    // Nothing is preselected (NEW-08): stacked, the list stands alone until a row is picked.
                    detailEmpty = state.selectedAccount == null,
                )
            }
        }
    }

    // Open/closed lives in the VM, which closes it on success — see BankAccountsState.isCreateDialogOpen.
    if (state.isCreateDialogOpen && access.mayManageAccounts) {
        CreateBankAccountDialog(
            createError = state.createError,
            isBusy = state.isBusy,
            onSubmit = viewModel::createAccount,
            onDismiss = viewModel::dismissCreateDialog,
            validationAttempt = state.createAttempt,
            onIbanEdited = viewModel::onCreateIbanEdited,
        )
    }

    assignTarget?.let { statement ->
        AssignStatementDialog(
            statement = statement,
            accounts = state.accounts,
            onAssign = { accountId ->
                assignTarget = null
                viewModel.assignStatement(statement.statementId, accountId)
            },
            onDismiss = { assignTarget = null },
        )
    }
}

/**
 * Import a bank statement. Reuses the shared [DocumentUploadSection] with the BANK_STATEMENT type,
 * so the file goes through the same `/api/documents/upload` path the server parses into
 * transactions — no separate upload plumbing. A wider `accept` than the PDF-only default lets an
 * operator pick a CAMT or MT940 file.
 */
@Composable
private fun StatementImportCard(onUploaded: () -> Unit) {
    val uploadViewModel: DocumentUploadViewModel = koinViewModel()
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(str(AdminStringKeys.BankStatementUploadTitle), style = MaterialTheme.typography.titleMedium)
            Text(
                text = str(AdminStringKeys.BankStatementUploadExplainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            DocumentUploadSection(
                fileType = DocumentType.BANK_STATEMENT.name,
                accept = STATEMENT_UPLOAD_ACCEPT,
                chooseKey = AdminStringKeys.StatementUploadChoose,
                replaceKey = AdminStringKeys.StatementUploadReplace,
                onUploaded = { onUploaded() },
                viewModel = uploadViewModel,
            )
            StatementImportOutcome.of(uploadViewModel.state)?.let { StatementImportResult(it) }
        }
    }
}

/**
 * What the import made of the file, under the upload button.
 *
 * The section above it only says the file was attached, which is true even for a statement the
 * server refused — this line is what tells the operator whether a statement actually arrived.
 */
@Composable
private fun StatementImportResult(outcome: StatementImportOutcome) {
    val (key, isProblem) =
        when (outcome) {
            is StatementImportOutcome.Imported ->
                if (outcome.overlapsEarlier) {
                    AdminStringKeys.BankImportOverlap to true
                } else {
                    AdminStringKeys.BankImportImported to false
                }
            StatementImportOutcome.NotReconciled -> AdminStringKeys.BankImportNotReconciled to true
            StatementImportOutcome.Unreadable -> AdminStringKeys.BankImportUnreadable to true
            StatementImportOutcome.NoStatement -> AdminStringKeys.BankImportNoStatement to false
            StatementImportOutcome.Duplicate -> AdminStringKeys.DocumentImportDuplicateTitle to false
        }
    Text(
        text = str(key),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Statements that were parsed and stored but whose IBAN matches no account of ours.
 *
 * A banner rather than a section: it is a work item that exists only sometimes, and giving it a
 * permanent card of its own pushed the accounts — the reason the page exists — below the fold.
 * Nothing is guessed; the operator either assigns one or creates the missing account.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnassignedBanner(
    unassigned: List<BankAccountDto.UnassignedStatementDto>,
    lastResolvedCount: Int?,
    actionsEnabled: Boolean,
    mayManage: Boolean,
    onAssign: (BankAccountDto.UnassignedStatementDto) -> Unit,
    onResolve: () -> Unit,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (unassigned.isEmpty()) {
                            str(AdminStringKeys.BankUnassignedNone)
                        } else {
                            str(AdminStringKeys.BankUnassignedBanner, unassigned.size)
                        },
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (unassigned.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                if (mayManage) {
                    PosSecondaryButton(
                        text = str(AdminStringKeys.BankResolveAction),
                        onClick = onResolve,
                        enabled = actionsEnabled,
                    )
                }
            }

            unassigned.forEach { statement ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 600.dp) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = statement.assertedIban.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = statement.statementDate.asDisplayDay(LocalAppLanguage.current),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (mayManage) {
                                PosButton(
                                    text = str(AdminStringKeys.BankAssignAction),
                                    onClick = { onAssign(statement) },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = statement.assertedIban.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(2f),
                            )
                            Text(
                                text = statement.statementDate.asDisplayDay(LocalAppLanguage.current),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (mayManage) {
                                PosButton(
                                    text = str(AdminStringKeys.BankAssignAction),
                                    onClick = { onAssign(statement) },
                                    enabled = actionsEnabled,
                                )
                            }
                        }
                    }
                }
            }

            if (lastResolvedCount != null) {
                Text(
                    text =
                        if (lastResolvedCount > 0) {
                            str(AdminStringKeys.BankResolveDone, lastResolvedCount)
                        } else {
                            str(AdminStringKeys.BankResolveNone)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = str(AdminStringKeys.BankUnassignedExplainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The accounts, as the spine of the screen. An account with gaps says so where it is chosen. */
@Composable
private fun AccountRail(
    accounts: List<BankAccountDto.ResponseDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { account ->
            AccountRailRow(
                account = account,
                selected = account.id == selectedId,
                onSelect = { onSelect(account.id) },
            )
        }
    }
}

@Composable
private fun AccountRailRow(
    account: BankAccountDto.ResponseDto,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ).clickable { onSelect() }
                .padding(horizontal = 12.dp, vertical = PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = account.bankName.orEmpty().ifBlank { account.iban },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = account.iban,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = str(AdminStringKeys.BankColumnStatements) + ": " + account.statementCount,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (account.continuityBreaks.isNotEmpty()) {
                PosStatusChip(
                    text = str(AdminStringKeys.BankAccountGapBadge, account.continuityBreaks.size),
                    backgroundColor = MaterialTheme.colorScheme.errorContainer,
                    textColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** The selected account's statement history: the continuity strip, then the statements themselves. */
@Composable
private fun AccountStatements(
    account: BankAccountDto.ResponseDto?,
    statements: List<BankAccountDto.StatementDto>,
    isLoading: Boolean,
    expandedStatementId: Long?,
    transactions: Map<Long, BankAccountDto.StatementTransactionListResponseDto>,
    loadingTransactionsFor: Long?,
    onToggleStatement: (Long) -> Unit,
) {
    if (account == null) {
        Text(
            text = str(AdminStringKeys.BankSelectAccountPrompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(
                text = account.bankName.orEmpty().ifBlank { account.iban },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = account.iban,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                isLoading -> AdminListSkeleton(rows = 4)
                statements.isEmpty() ->
                    Text(
                        text = str(AdminStringKeys.BankStatementsEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> {
                    ContinuitySection(statements)
                    Text(str(AdminStringKeys.BankStatementsTitle), style = MaterialTheme.typography.titleSmall)
                    PosAdaptiveTable(
                        cardBelow = STATEMENT_CARD_BELOW,
                        columns = statementColumns(),
                        rows =
                            statements.map { statement ->
                                statementRow(
                                    statement = statement,
                                    overlappedNumber = statement.overlappedStatementLabel(statements),
                                    accountCurrency = account.currency,
                                    isExpanded = statement.statementId == expandedStatementId,
                                    isLoadingTransactions = statement.statementId == loadingTransactionsFor,
                                    page = transactions[statement.statementId],
                                    onToggle = { onToggleStatement(statement.statementId) },
                                )
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinuitySection(statements: List<BankAccountDto.StatementDto>) {
    val gaps = statements.count { it.continuityBreak }
    Text(str(AdminStringKeys.BankContinuityTitle), style = MaterialTheme.typography.titleSmall)
    ContinuityStrip(
        statements = statements,
        presentColor = MaterialTheme.colorScheme.primary,
        gapColor = MaterialTheme.colorScheme.error,
    )
    Text(
        text =
            if (gaps == 0) {
                str(AdminStringKeys.BankContinuityComplete)
            } else {
                str(AdminStringKeys.BankContinuityGaps, gaps)
            },
        style = MaterialTheme.typography.bodySmall,
        color = if (gaps == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}

/** Lower bound for the card switch (the table sits beside a pane); the table form also needs `requiredTableWidth(columns)`, whichever is larger wins. */
private val STATEMENT_CARD_BELOW = 600.dp

@Composable
private fun statementColumns(): List<PosAdaptiveColumn> =
    listOf(
        // The chevron gets its own narrow, unlabelled column rather than riding inside the number
        // cell, so every row's disclosure sits on the same vertical line. In card form it is the
        // trailing (top right) element.
        PosAdaptiveColumn(PosTableColumn(title = "", weight = 0.75f, minWidth = 40.dp), PosCardRole.Trailing),
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.BankColumnStatementNumber), weight = 2.1f, minWidth = PosTableMin.Id, nowrap = true),
            PosCardRole.Title,
        ),
        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.BankColumnPeriod), weight = 2.7f, minWidth = 168.dp, nowrap = true)),
        PosAdaptiveColumn(
            PosTableColumn(
                title = str(AdminStringKeys.BankColumnOpeningBalance),
                align = PosTableAlign.End,
                weight = 1.75f,
                minWidth = PosTableMin.Money,
                nowrap = true,
            ),
        ),
        PosAdaptiveColumn(
            PosTableColumn(
                title = str(AdminStringKeys.BankColumnClosingBalance),
                align = PosTableAlign.End,
                weight = 1.75f,
                minWidth = PosTableMin.Money,
                nowrap = true,
            ),
        ),
        PosAdaptiveColumn(
            PosTableColumn(title = str(AdminStringKeys.BankColumnTransactions), align = PosTableAlign.End, weight = 1.35f, minWidth = 80.dp, nowrap = true),
        ),
    )

/**
 * One statement, and its bookings when it is open.
 *
 * A row whose statement does not continue the previous one says so in its own first cell, so the
 * finding survives even where the strip is scrolled out of view.
 */
@Composable
private fun statementRow(
    statement: BankAccountDto.StatementDto,
    overlappedNumber: String?,
    accountCurrency: String,
    isExpanded: Boolean,
    isLoadingTransactions: Boolean,
    page: BankAccountDto.StatementTransactionListResponseDto?,
    onToggle: () -> Unit,
): PosContentRow {
    val currency = statement.currency.ifBlank { accountCurrency }
    return PosContentRow(
        onClick = onToggle,
        expanded =
            if (!isExpanded) {
                null
            } else {
                { StatementBookings(page = page, isLoading = isLoadingTransactions, fallbackCurrency = currency) }
            },
        cells =
            listOf(
                {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = statement.statementNumber.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (statement.continuityBreak) {
                            Text(
                                text = str(AdminStringKeys.BankStatementMissing),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        // The statement itself, not only its repeated bookings, says it overlaps.
                        if (overlappedNumber != null) {
                            Text(
                                text = str(AdminStringKeys.BankStatementOverlap, overlappedNumber),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                {
                    Text(
                        statement.periodLabel(LocalAppLanguage.current),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                { BalanceCell(statement.openingBalance, currency) },
                { BalanceCell(statement.closingBalance, currency) },
                {
                    Text(
                        text = statement.transactionCount.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
            ),
    )
}

/**
 * The bookings behind one statement.
 *
 * No scrolling container of its own: this sits inside the pane's scroll, and on wasm a scrollable
 * nested in a scrollable measures to infinite height and takes the composition down. The page cap is
 * what keeps it bounded instead, and [BookingsFooter] says so when it bites.
 */
@Composable
private fun StatementBookings(
    page: BankAccountDto.StatementTransactionListResponseDto?,
    isLoading: Boolean,
    fallbackCurrency: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when {
            isLoading -> AdminListSkeleton(rows = 3)
            page == null || page.items.isEmpty() ->
                Text(
                    text = str(AdminStringKeys.BankBookingsEmpty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                page.items.forEach { BookingLine(it, fallbackCurrency) }
                BookingsFooter(page)
            }
        }
    }
}

/**
 * One booking: when, who, how much, and whether it still needs assigning.
 *
 * The amount is signed for reading — a `DEBIT` leaves the account — while the DTO keeps it positive
 * with the direction beside it, which is how the column is stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookingLine(
    booking: BankAccountDto.StatementTransactionDto,
    fallbackCurrency: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (maxWidth < BOOKING_TWO_LINE_BELOW) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) { BookingCounterparty(booking) }
                    PosMoneyText(
                        amount = booking.signedAmount(),
                        currencyCode = booking.currency.ifBlank { fallbackCurrency },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = booking.valueDate.asDisplayDay(LocalAppLanguage.current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (booking.duplicateOfTransactionId != null) {
                        DuplicateChip()
                    }
                    AssignmentChip(booking)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            ) {
                Text(
                    text = booking.valueDate.asDisplayDay(LocalAppLanguage.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) { BookingCounterparty(booking) }
                PosMoneyText(
                    amount = booking.signedAmount(),
                    currencyCode = booking.currency.ifBlank { fallbackCurrency },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (booking.duplicateOfTransactionId != null) {
                    DuplicateChip()
                }
                AssignmentChip(booking)
            }
        }
    }
}

/** Below this width a booking drops to two lines (counterparty + amount, then date + chips). */
private val BOOKING_TWO_LINE_BELOW = 420.dp

/** Counterparty name and purpose, one ellipsised line each. */
@Composable
private fun BookingCounterparty(booking: BankAccountDto.StatementTransactionDto) {
    Text(
        text = booking.counterpartyName.orEmpty().ifBlank { booking.counterpartyIban.orEmpty() },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    booking.remittance?.takeIf { it.isNotBlank() }?.let { purpose ->
        Text(
            text = purpose,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DuplicateChip() {
    PosStatusChip(
        text = str(AdminStringKeys.BankBookingDuplicate),
        backgroundColor = MaterialTheme.colorScheme.errorContainer,
        textColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

/**
 * How much of this booking is still unassigned, as a chip.
 *
 * Compared as `Decimal`, never as strings or doubles: `"0.00"` and `"0.0000"` are the same amount
 * and must not read as two different states.
 */
@Composable
private fun AssignmentChip(booking: BankAccountDto.StatementTransactionDto) {
    val open = booking.openAmount.toDecimalOrZero()
    val full = booking.amount.toDecimalOrZero()
    val (label, background, foreground) =
        when {
            open <= Decimal.ZERO ->
                Triple(
                    AdminStringKeys.BankBookingAssigned,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            open < full ->
                Triple(
                    AdminStringKeys.BankBookingPartial,
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
            else ->
                Triple(
                    AdminStringKeys.BankBookingOpen,
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    PosStatusChip(text = str(label), backgroundColor = background, textColor = foreground)
}

/**
 * Renders only when the page does not hold the whole statement.
 *
 * A cap that says nothing is indistinguishable from a statement that really has 100 bookings, which
 * is the reading an operator reconciling a month must not be left with.
 */
@Composable
private fun BookingsFooter(page: BankAccountDto.StatementTransactionListResponseDto) {
    if (page.items.size.toLong() >= page.total) return
    Text(
        text = str(AdminStringKeys.BankBookingsShowing, page.items.size, page.total),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * The number of the statement this one overlaps, for its flag — or null when it overlaps nothing.
 *
 * When that number equals this row's own, the id is appended so the two can be told apart. Falls back to the id when the earlier statement states no number, or sits on another account and
 * is therefore not in [statements]: a flag with an id is still a flag, and an empty one would hide
 * the overlap the operator has to see.
 */
internal fun BankAccountDto.StatementDto.overlappedStatementLabel(statements: List<BankAccountDto.StatementDto>): String? {
    val overlappedId = overlappingStatementId ?: return null
    val number =
        statements
            .firstOrNull { it.statementId == overlappedId }
            ?.statementNumber
            ?.takeIf { it.isNotBlank() }
            ?: return "#$overlappedId"
    // A re-import carries the same number as the statement it overlaps, so "overlaps 3/2026" on the
    // row numbered 3/2026 names nothing: the id says which of the two is meant.
    return if (number == statementNumber) "$number (#$overlappedId)" else number
}

/** `-70,00` for money leaving the account; the DTO keeps the amount positive and the sign beside it. */
private fun BankAccountDto.StatementTransactionDto.signedAmount(): String =
    if (creditDebit.equals("DEBIT", ignoreCase = true)) {
        (Decimal.ZERO - amount.toDecimalOrZero()).toPlainString()
    } else {
        amount
    }

/** A balance the parser never read is blank, not `0,00 €` — an unknown balance is not a zero one. */
@Composable
private fun BalanceCell(
    amount: String?,
    currency: String,
) {
    if (amount == null) {
        Text("", style = MaterialTheme.typography.bodyMedium)
        return
    }
    PosMoneyText(
        amount = amount,
        currencyCode = currency,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * `periodStart – periodEnd`, falling back to the statement date when the parser read no period.
 * MT940 in particular often carries only a date.
 */
private fun BankAccountDto.StatementDto.periodLabel(language: Language): String {
    val start = periodStart.asDisplayDay(language)
    val end = periodEnd.asDisplayDay(language)
    return when {
        start.isNotEmpty() && end.isNotEmpty() -> "$start – $end"
        start.isNotEmpty() -> start
        else -> statementDate.asDisplayDay(language)
    }
}
