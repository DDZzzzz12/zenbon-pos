package org.example.project.admin.features.allocations

import org.example.project.admin.features.allocations.presentation.AllocationsViewModel
import org.example.project.admin.features.bank.FakeBankAccountApiClient
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.admin.features.finance.FakeBookkeepingStartApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType

/**
 * The shared fixtures of the Zuordnung tests.
 *
 * [allocationsViewModel] exists so a new counter-leg client can be added to the ViewModel without
 * rewriting twenty construction sites — every test names only the fakes it asserts on.
 */
internal fun queueRow(
    id: Long,
    amount: String = "100.0000",
    open: String = "100.0000",
    allocations: List<AllocationDto.AllocationRowDto> = emptyList(),
) = AllocationDto.QueueRowDto(
    transactionId = id,
    bookingDate = "2026-03-01T00:00",
    valueDate = "2026-03-01T00:00",
    amount = amount,
    openAmount = open,
    creditDebit = "DEBIT",
    counterpartyName = "Metro AG",
    remittance = "RE-2026-0001",
    allocations = allocations,
)

internal fun bankAccount(
    id: Long,
    iban: String = "DE02120300000000202051",
) = BankAccountDto.ResponseDto(
    id = id,
    iban = iban,
    bankName = "Sparkasse",
    accountHolder = "Zenbon GmbH",
)

internal fun cashEntry(
    id: Long,
    amount: String = "500.0000",
) = KassenbuchDto.KassenbuchGroupedSummaryDto(
    headerId = id,
    cashBookId = 1,
    belegNr = "KB-$id",
    entryType = KassenbuchEntryType.entries.first(),
    belegtext = "Einzahlung Bank",
    belegDatum = "2026-03-01",
    waehrung = "EUR",
    totalAmount = amount,
    lineItems = emptyList(),
    currentBalance = "1000.0000",
    employeeNumber = "E1",
    createdAt = "2026-03-01T09:00:00",
)

internal fun allocationsViewModel(
    api: FakeAllocationApiClient = FakeAllocationApiClient(),
    bookkeeping: FakeBookkeepingStartApiClient = FakeBookkeepingStartApiClient(),
    bankAccounts: FakeBankAccountApiClient = FakeBankAccountApiClient(),
    cashEntries: FakeKassenbuchApiClient = FakeKassenbuchApiClient(entries = { emptyList() }, balance = { "0.00" }),
) = AllocationsViewModel(api, bookkeeping, bankAccounts, cashEntries)
