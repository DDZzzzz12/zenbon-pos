package org.example.project.infrastructure.di

import org.example.project.features.allocation.data.repository.AllocationQueueRepositoryImpl
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.allocation.data.service.AllocationWriter
import org.example.project.features.allocation.domain.repository.AllocationQueueRepository
import org.example.project.features.allocation.domain.repository.AllocationRepository
import org.example.project.features.audit.data.repository.EntryLogRepositoryImpl
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.domain.repository.EntryLogRepository
import org.example.project.features.audit.domain.repository.TransactionAuditRepository
import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.bank.domain.repository.BankAccountRepository
import org.example.project.features.bill.data.repository.BillRepositoryImpl
import org.example.project.features.bill.domain.repository.BillRepository
import org.example.project.features.cardTerminal.data.repository.CardSettlementRepositoryImpl
import org.example.project.features.cardTerminal.data.repository.CardTerminalRepositoryImpl
import org.example.project.features.cardTerminal.data.repository.CardTransactionRepositoryImpl
import org.example.project.features.cardTerminal.domain.repository.CardSettlementRepository
import org.example.project.features.cardTerminal.domain.repository.CardTerminalRepository
import org.example.project.features.cardTerminal.domain.repository.CardTransactionRepository
import org.example.project.features.changelog.data.repository.ChangelogRepositoryImpl
import org.example.project.features.changelog.domain.repository.ChangelogRepository
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.contract.domain.repository.ContractRepository
import org.example.project.features.customer.data.repository.CustomerAddressRepositoryImpl
import org.example.project.features.customer.data.repository.CustomerRepositoryImpl
import org.example.project.features.customer.domain.repository.CustomerAddressRepository
import org.example.project.features.customer.domain.repository.CustomerRepository
import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.repository.DebtorRepository
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.data.repository.WorkScheduleRepositoryImpl
import org.example.project.features.employee.domain.repository.EmployeeInfoRepository
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.employee.domain.repository.PermissionGroupRepository
import org.example.project.features.employee.domain.repository.WorkScheduleRepository
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.openinghours.data.repository.OpeningHoursRepositoryImpl
import org.example.project.features.openinghours.domain.repository.OpeningHoursRepository
import org.example.project.features.order.data.repository.CancellationRepositoryImpl
import org.example.project.features.order.data.repository.ItemTransferRepositoryImpl
import org.example.project.features.order.data.repository.OrderItemRepositoryImpl
import org.example.project.features.order.data.repository.OrderRepositoryImpl
import org.example.project.features.order.domain.repository.CancellationRepository
import org.example.project.features.order.domain.repository.ItemTransferRepository
import org.example.project.features.order.domain.repository.OrderItemRepository
import org.example.project.features.order.domain.repository.OrderRepository
import org.example.project.features.partner.data.repository.BusinessPartnerRepositoryImpl
import org.example.project.features.partner.domain.repository.BusinessPartnerRepository
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.payment.domain.repository.PaymentTypeRepository
import org.example.project.features.pfand.data.repository.PfandTypeRepositoryImpl
import org.example.project.features.pfand.domain.repository.PfandTypeRepository
import org.example.project.features.printer.data.repository.PrintJobHistoryRepositoryImpl
import org.example.project.features.printer.data.repository.PrinterConfigurationRepositoryImpl
import org.example.project.features.printer.data.repository.PrinterLocationRepositoryImpl
import org.example.project.features.printer.domain.repository.PrintJobHistoryRepository
import org.example.project.features.printer.domain.repository.PrinterConfigurationRepository
import org.example.project.features.printer.domain.repository.PrinterLocationRepository
import org.example.project.features.product.data.repository.ChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.FavouriteProductRepositoryImpl
import org.example.project.features.product.data.repository.ProductCategoryRepositoryImpl
import org.example.project.features.product.data.repository.ProductChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.ProductExtrasAssignmentRepositoryImpl
import org.example.project.features.product.data.repository.ProductExtrasRepositoryImpl
import org.example.project.features.product.data.repository.ProductRepositoryImpl
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.features.product.domain.repository.FavouriteProductRepository
import org.example.project.features.product.domain.repository.ProductCategoryRepository
import org.example.project.features.product.domain.repository.ProductChoiceGroupRepository
import org.example.project.features.product.domain.repository.ProductExtrasAssignmentRepository
import org.example.project.features.product.domain.repository.ProductExtrasRepository
import org.example.project.features.product.domain.repository.ProductRepository
import org.example.project.features.reservation.data.repository.ReservationRepositoryImpl
import org.example.project.features.reservation.data.repository.ReservationRuleRepositoryImpl
import org.example.project.features.reservation.data.repository.ReservationTableAssignmentRepositoryImpl
import org.example.project.features.reservation.domain.repository.ReservationRepository
import org.example.project.features.reservation.domain.repository.ReservationRuleRepository
import org.example.project.features.reservation.domain.repository.ReservationTableAssignmentRepository
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.domain.repository.ArNumberSequenceRepository
import org.example.project.features.salesinvoice.domain.repository.SalesInvoiceRepository
import org.example.project.features.scheduler.data.repository.JobExecutionRepositoryImpl
import org.example.project.features.scheduler.data.repository.ScheduledJobRepositoryImpl
import org.example.project.features.scheduler.domain.repository.JobExecutionRepository
import org.example.project.features.scheduler.domain.repository.ScheduledJobRepository
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.domain.repository.ConfigurationContributor
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.setup.domain.repository.VatRepository
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.statistics.domain.repository.StatisticsRepository
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.repository.SupplierRepository
import org.example.project.features.table.data.repository.CombinableTablesRepositoryImpl
import org.example.project.features.table.data.repository.LocationTypeRepositoryImpl
import org.example.project.features.table.data.repository.RTableRepositoryImpl
import org.example.project.features.table.data.repository.RoomElementRepositoryImpl
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.domain.repository.CombinableTablesRepository
import org.example.project.features.table.domain.repository.LocationTypeRepository
import org.example.project.features.table.domain.repository.RTableRepository
import org.example.project.features.table.domain.repository.RoomElementRepository
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.example.project.features.terminal.data.repository.TerminalPairingCodeRepositoryImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.repository.TerminalPairingCodeRepository
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.timetracking.data.repository.CostCenterRepositoryImpl
import org.example.project.features.timetracking.data.repository.TimeApprovalRepositoryImpl
import org.example.project.features.timetracking.data.repository.TimeEntryRepositoryImpl
import org.example.project.features.timetracking.data.repository.WageTypeRepositoryImpl
import org.example.project.features.timetracking.domain.repository.CostCenterRepository
import org.example.project.features.timetracking.domain.repository.TimeApprovalRepository
import org.example.project.features.timetracking.domain.repository.TimeEntryRepository
import org.example.project.features.timetracking.domain.repository.WageTypeRepository
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.domain.repository.TseLateSigningRepository
import org.example.project.features.tse.domain.repository.TseSigningQueueRepository
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.features.voucher.data.repository.VoucherRepositoryImpl
import org.example.project.features.voucher.data.service.VoucherProvisioning
import org.example.project.features.voucher.domain.repository.VoucherRepository
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.domain.repository.ZBonRepository
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val reposModule =
    module {
        // Repository Layer - Data Access Layer implementations
        singleOf(::EmployeeRepositoryImpl) { bind<EmployeeRepository>() }
        singleOf(::PermissionGroupRepositoryImpl) { bind<PermissionGroupRepository>() }
        singleOf(::EmployeeInfoRepositoryImpl) { bind<EmployeeInfoRepository>() }
        singleOf(::WorkScheduleRepositoryImpl) { bind<WorkScheduleRepository>() }
        singleOf(::ShopInfoRepositoryImpl) { bind<ShopInfoRepository>() }
        singleOf(::OrderRepositoryImpl) { bind<OrderRepository>() }
        singleOf(::ProductRepositoryImpl) { bind<ProductRepository>() }
        singleOf(::PfandTypeRepositoryImpl) { bind<PfandTypeRepository>() }
        singleOf(::ChangelogRepositoryImpl) { bind<ChangelogRepository>() }
        singleOf(::ProductCategoryRepositoryImpl) { bind<ProductCategoryRepository>() }
        singleOf(::ProductExtrasRepositoryImpl) { bind<ProductExtrasRepository>() }
        singleOf(::ProductExtrasAssignmentRepositoryImpl) { bind<ProductExtrasAssignmentRepository>() }
        singleOf(::ChoiceGroupRepositoryImpl) { bind<ChoiceGroupRepository>() }
        singleOf(::ProductChoiceGroupRepositoryImpl) { bind<ProductChoiceGroupRepository>() }
        singleOf(::FavouriteProductRepositoryImpl) { bind<FavouriteProductRepository>() }
        singleOf(::RTableRepositoryImpl) { bind<RTableRepository>() }
        singleOf(::TableSessionRepositoryImpl) { bind<TableSessionRepository>() }
        singleOf(::RoomElementRepositoryImpl) { bind<RoomElementRepository>() }
        singleOf(::TseTransactionRepositoryImpl) { bind<TseTransactionRepository>() }
        singleOf(::VatRepositoryImpl) { bind<VatRepository>() }
        singleOf(::PaymentTypeRepositoryImpl) { bind<PaymentTypeRepository>() }
        singleOf(::KassenbuchRepositoryImpl) { bind<KassenbuchRepository>() }
        singleOf(::CashBookRepositoryImpl) { bind<CashBookRepository>() }
        singleOf(::VoucherRepositoryImpl) { bind<VoucherRepository>() }
        singleOf(::BillRepositoryImpl) { bind<BillRepository>() }
        singleOf(::OrderItemRepositoryImpl) { bind<OrderItemRepository>() }
        singleOf(::CancellationRepositoryImpl) { bind<CancellationRepository>() }
        singleOf(::ItemTransferRepositoryImpl) { bind<ItemTransferRepository>() }
        singleOf(::ZBonRepositoryImpl) { bind<ZBonRepository>() }
        singleOf(::StatisticsRepositoryImpl) { bind<StatisticsRepository>() }
        singleOf(::EntryLogRepositoryImpl) { bind<EntryLogRepository>() }
        singleOf(::TransactionAuditRepositoryImpl) { bind<TransactionAuditRepository>() }

        // Customer Management repositories
        singleOf(::CustomerRepositoryImpl) { bind<CustomerRepository>() }
        singleOf(::CustomerAddressRepositoryImpl) { bind<CustomerAddressRepository>() }

        // Configuration Repository (key-value storage)
        single<ConfigurationContributor>(qualifier = named("voucher")) { VoucherProvisioning() }
        single { ConfigurationRepository(getAll<ConfigurationContributor>()) }

        // Printer repositories
        singleOf(::PrinterConfigurationRepositoryImpl) { bind<PrinterConfigurationRepository>() }
        singleOf(::PrintJobHistoryRepositoryImpl) { bind<PrintJobHistoryRepository>() }
        singleOf(::PrinterLocationRepositoryImpl) { bind<PrinterLocationRepository>() }

        // Document Management repository
        singleOf(::DocumentRepositoryImpl) { bind<DocumentRepository>() }

        // Supplier (creditor) master data repository
        singleOf(::SupplierRepositoryImpl) { bind<SupplierRepository>() }

        // The party both accounting roles point at: duplicate suggestions and the merge writes.
        singleOf(::BusinessPartnerRepositoryImpl) { bind<BusinessPartnerRepository>() }

        // Our own bank accounts
        singleOf(::BankAccountRepositoryImpl) { bind<BankAccountRepository>() }

        // Outgoing invoices: debtor master data, the invoice root and the §14 number counter.
        singleOf(::DebtorRepositoryImpl) { bind<DebtorRepository>() }
        singleOf(::SalesInvoiceRepositoryImpl) { bind<SalesInvoiceRepository>() }
        singleOf(::ArNumberSequenceRepositoryImpl) { bind<ArNumberSequenceRepository>() }
        singleOf(::BillInvoiceFactsGatherer)

        // Payment allocations. AllocationWriter is the only supported way to add or remove one:
        // it writes the allocation and both residuals in the caller's transaction.
        singleOf(::AllocationRepositoryImpl) { bind<AllocationRepository>() }
        singleOf(::AllocationQueueRepositoryImpl) { bind<AllocationQueueRepository>() }
        singleOf(::AllocationWriter)

        // Recurring obligations and their expected payments.
        singleOf(::ContractRepositoryImpl) { bind<ContractRepository>() }

        // TSE Signing Queue repository (for Nachsignieren - late signing)
        singleOf(::TseSigningQueueRepositoryImpl) { bind<TseSigningQueueRepository>() }
        singleOf(::TseLateSigningRepositoryImpl) { bind<TseLateSigningRepository>() }

        // Terminal repository (for multi-client DSFinV-K compliance)
        singleOf(::TerminalRepositoryImpl) { bind<TerminalRepository>() }
        singleOf(::TerminalPairingCodeRepositoryImpl) { bind<TerminalPairingCodeRepository>() }

        // Time tracking repositories
        singleOf(::TimeEntryRepositoryImpl) { bind<TimeEntryRepository>() }
        singleOf(::CostCenterRepositoryImpl) { bind<CostCenterRepository>() }
        singleOf(::WageTypeRepositoryImpl) { bind<WageTypeRepository>() }
        singleOf(::TimeApprovalRepositoryImpl) { bind<TimeApprovalRepository>() }

        // Reservation repositories
        singleOf(::ReservationRepositoryImpl) { bind<ReservationRepository>() }
        singleOf(::ReservationRuleRepositoryImpl) { bind<ReservationRuleRepository>() }

        // Opening hours repositories
        singleOf(::OpeningHoursRepositoryImpl) { bind<OpeningHoursRepository>() }

        // Combinable tables repository
        singleOf(::CombinableTablesRepositoryImpl) { bind<CombinableTablesRepository>() }

        // Location type repository
        singleOf(::LocationTypeRepositoryImpl) { bind<LocationTypeRepository>() }

        // Reservation table assignment repository
        singleOf(::ReservationTableAssignmentRepositoryImpl) { bind<ReservationTableAssignmentRepository>() }

        // Scheduler repositories
        singleOf(::ScheduledJobRepositoryImpl) { bind<ScheduledJobRepository>() }
        singleOf(::JobExecutionRepositoryImpl) { bind<JobExecutionRepository>() }

        // Card terminal repositories
        singleOf(::CardTerminalRepositoryImpl) { bind<CardTerminalRepository>() }
        singleOf(::CardTransactionRepositoryImpl) { bind<CardTransactionRepository>() }
        singleOf(::CardSettlementRepositoryImpl) { bind<CardSettlementRepository>() }
    }
