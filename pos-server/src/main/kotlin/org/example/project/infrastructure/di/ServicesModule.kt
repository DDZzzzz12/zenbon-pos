package org.example.project.infrastructure.di

import kotlinx.coroutines.runBlocking
import org.example.project.BuildConfig
import org.example.project.banking.BankStatementParser
import org.example.project.banking.DefaultBankStatementParser
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.data.service.NumberTrackerCleanupService
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.security.TokenClaimsProvider
import org.example.project.core.security.TokenClaimsProviderImpl
import org.example.project.core.utils.SemanticVersion
import org.example.project.database.DatabaseFactory
import org.example.project.database.LiveDataSourceProvider
import org.example.project.einvoice.DefaultEInvoiceParser
import org.example.project.einvoice.EInvoiceParser
import org.example.project.features.admin.data.service.PermissionDiagnosticsServiceImpl
import org.example.project.features.allocation.data.service.AllocationServiceImpl
import org.example.project.features.allocation.domain.service.AllocationService
import org.example.project.features.audit.data.service.ChangeFeedServiceImpl
import org.example.project.features.audit.data.service.EntryLogRetentionJob
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.audit.domain.service.ChangeFeedService
import org.example.project.features.audit.domain.service.EntryLogService
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.auth.data.repository.RefreshTokenRepositoryImpl
import org.example.project.features.auth.data.service.ApprovalRequestServiceImpl
import org.example.project.features.auth.data.service.RefreshTokenServiceImpl
import org.example.project.features.auth.data.service.TemporarySessionServiceImpl
import org.example.project.features.auth.domain.repository.RefreshTokenRepository
import org.example.project.features.auth.domain.service.ApprovalDetailsResolver
import org.example.project.features.auth.domain.service.ApprovalRequestService
import org.example.project.features.auth.domain.service.RefreshTokenService
import org.example.project.features.auth.domain.service.TemporarySessionService
import org.example.project.features.backup.data.service.BackupCopyCoordinator
import org.example.project.features.backup.data.service.BackupCopyServiceImpl
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.backup.data.service.RestoredPathReviewServiceImpl
import org.example.project.features.backup.domain.service.BackupCopyService
import org.example.project.features.backup.domain.service.BackupRestoreService
import org.example.project.features.backup.domain.service.PostRestoreHook
import org.example.project.features.backup.domain.service.RestoredPathReviewService
import org.example.project.features.bank.data.service.BankAccountServiceImpl
import org.example.project.features.bank.data.service.StatementAccountResolver
import org.example.project.features.bank.domain.service.BankAccountService
import org.example.project.features.bill.data.service.BillServiceImpl
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.bill.domain.service.BillSummaries
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.cardTerminal.data.service.CardPaymentServiceImpl
import org.example.project.features.cardTerminal.data.service.CardSettlementServiceImpl
import org.example.project.features.cardTerminal.data.service.CardTerminalServiceImpl
import org.example.project.features.cardTerminal.domain.service.CardPaymentService
import org.example.project.features.cardTerminal.domain.service.CardSettlementService
import org.example.project.features.cardTerminal.domain.service.CardTerminalService
import org.example.project.features.changelog.data.service.ChangelogServiceImpl
import org.example.project.features.changelog.domain.service.ChangelogService
import org.example.project.features.contract.data.service.ContractServiceImpl
import org.example.project.features.contract.data.service.InstalmentGenerator
import org.example.project.features.contract.domain.service.ContractService
import org.example.project.features.customer.data.service.CustomerAddressServiceImpl
import org.example.project.features.customer.data.service.CustomerServiceImpl
import org.example.project.features.customer.domain.service.CustomerAddressService
import org.example.project.features.customer.domain.service.CustomerService
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.service.CustomExportService
import org.example.project.features.datevexportlog.data.repository.DatevExportLogRepositoryImpl
import org.example.project.features.datevexportlog.data.service.DatevExportCatchupServiceImpl
import org.example.project.features.datevexportlog.data.service.DatevExportLogServiceImpl
import org.example.project.features.datevexportlog.data.service.MonthlyCloseDatevStatusImpl
import org.example.project.features.datevexportlog.domain.repository.DatevExportLogRepository
import org.example.project.features.datevexportlog.domain.service.DatevExportCatchupService
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.debtor.data.service.DebtorServiceImpl
import org.example.project.features.debtor.domain.service.DebtorService
import org.example.project.features.document.data.service.DocumentManagementServiceImpl
import org.example.project.features.document.data.service.InvoiceCancellationServiceImpl
import org.example.project.features.document.data.service.InvoiceListServiceImpl
import org.example.project.features.document.data.service.KassenbuchDocumentsImpl
import org.example.project.features.document.data.service.PdfGenerationServiceImpl
import org.example.project.features.document.domain.service.DocumentKassenbuchLinkResolver
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.InvoiceCancellationService
import org.example.project.features.document.domain.service.InvoiceListService
import org.example.project.features.document.domain.service.PdfGenerationService
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.dsfinvk.domain.service.DsfinvkExportService
import org.example.project.features.employee.data.service.EmployeeInfoServiceImpl
import org.example.project.features.employee.data.service.EmployeeServiceImpl
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.data.service.PermissionGroupServiceImpl
import org.example.project.features.employee.data.service.WorkScheduleServiceImpl
import org.example.project.features.employee.domain.service.EmployeeInfoService
import org.example.project.features.employee.domain.service.EmployeeService
import org.example.project.features.employee.domain.service.StaffRosterListener
import org.example.project.features.employee.domain.service.WorkScheduleService
import org.example.project.features.finance.data.service.BookkeepingStartServiceImpl
import org.example.project.features.finance.domain.service.BookkeepingStartService
import org.example.project.features.issuereport.data.remote.GithubIssueClient
import org.example.project.features.issuereport.data.repository.IssueReportSettingsRepositoryImpl
import org.example.project.features.issuereport.data.service.IssueReportServiceImpl
import org.example.project.features.issuereport.data.service.LogTail
import org.example.project.features.issuereport.domain.models.UpdateTokenSource
import org.example.project.features.issuereport.domain.repository.IssueReportSettingsRepository
import org.example.project.features.issuereport.domain.service.IssueReportService
import org.example.project.features.issuereport.domain.tracker.IssueTracker
import org.example.project.features.kassenbuch.data.service.CashBookCloseServiceImpl
import org.example.project.features.kassenbuch.data.service.CashBookServiceImpl
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevApExportServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelBelegeExportServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelExportServiceImpl
import org.example.project.features.kassenbuch.data.service.DatevXmlExportServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchBelegResolver
import org.example.project.features.kassenbuch.data.service.KassenbuchDocumentLinkResolver
import org.example.project.features.kassenbuch.data.service.KassenbuchServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.kassenbuch.domain.service.CashBookCloseService
import org.example.project.features.kassenbuch.domain.service.CashBookService
import org.example.project.features.kassenbuch.domain.service.CashBookTransferService
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelBelegeExportService
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelExportService
import org.example.project.features.kassenbuch.domain.service.DatevXmlExportService
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.maintenance.data.service.LogSearchService
import org.example.project.features.maintenance.data.service.MaintenanceAuthService
import org.example.project.features.matching.data.service.MatchFactsGatherer
import org.example.project.features.matching.data.service.MatchSuggestionServiceImpl
import org.example.project.features.matching.data.service.ReversalProcessor
import org.example.project.features.matching.domain.service.MatchSuggestionService
import org.example.project.features.monthlyclose.data.repository.SimulatedSignatureRepository
import org.example.project.features.monthlyclose.data.service.MonthlyCloseServiceImpl
import org.example.project.features.monthlyclose.data.service.checks.BillsOutsideZBonCheck
import org.example.project.features.monthlyclose.data.service.checks.CashMovementsDocumentedCheck
import org.example.project.features.monthlyclose.data.service.checks.ChainIntegrityCheck
import org.example.project.features.monthlyclose.data.service.checks.DatevExportedCheck
import org.example.project.features.monthlyclose.data.service.checks.OpenBillsCheck
import org.example.project.features.monthlyclose.data.service.checks.PossibleDoubleSignCheck
import org.example.project.features.monthlyclose.data.service.checks.SimulatedSignatureCheck
import org.example.project.features.monthlyclose.data.service.checks.TseTanrContinuityCheck
import org.example.project.features.monthlyclose.data.service.checks.UnsignedTseCheck
import org.example.project.features.monthlyclose.data.service.checks.VatConsistencyCheck
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseDatevStatus
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseFinancials
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseService
import org.example.project.features.openinghours.data.service.OpeningHoursServiceImpl
import org.example.project.features.openinghours.domain.service.OpeningHoursService
import org.example.project.features.order.data.service.OrderApprovalDetailsResolver
import org.example.project.features.order.data.service.OrderItemServiceImpl
import org.example.project.features.order.data.service.OrderServiceImpl
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.order.data.service.RotationNextNotifier
import org.example.project.features.order.data.service.RotationStaffRosterListener
import org.example.project.features.order.domain.service.OrderItemService
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.example.project.features.partner.data.service.BusinessPartnerServiceImpl
import org.example.project.features.partner.data.service.DatevStammdatenExportServiceImpl
import org.example.project.features.partner.domain.service.BusinessPartnerService
import org.example.project.features.partner.domain.service.DatevStammdatenExportService
import org.example.project.features.payment.data.service.PaymentTypeServiceImpl
import org.example.project.features.payment.domain.service.PaymentTypeService
import org.example.project.features.payment.domain.service.VatRateAdmission
import org.example.project.features.payment.domain.service.VatService
import org.example.project.features.pfand.data.service.PfandTypeServiceImpl
import org.example.project.features.pfand.domain.service.PfandTypeService
import org.example.project.features.printer.data.service.PrintJobHistoryServiceImpl
import org.example.project.features.printer.data.service.PrinterLocationServiceImpl
import org.example.project.features.printer.data.service.PrinterServiceImpl
import org.example.project.features.printer.domain.service.PrintJobHistoryService
import org.example.project.features.printer.domain.service.PrinterLocationService
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.printer.domain.service.ReceiptPdfRenderer
import org.example.project.features.product.data.service.ChoiceGroupServiceImpl
import org.example.project.features.product.data.service.FavouriteProductServiceImpl
import org.example.project.features.product.data.service.ProductCategoryServiceImpl
import org.example.project.features.product.data.service.ProductChoiceGroupServiceImpl
import org.example.project.features.product.data.service.ProductExtrasAssignmentServiceImpl
import org.example.project.features.product.data.service.ProductExtrasServiceImpl
import org.example.project.features.product.data.service.ProductServiceImpl
import org.example.project.features.product.domain.service.ChoiceGroupService
import org.example.project.features.product.domain.service.FavouriteProductService
import org.example.project.features.product.domain.service.ProductCategoryService
import org.example.project.features.product.domain.service.ProductChoiceGroupService
import org.example.project.features.product.domain.service.ProductExtrasAssignmentService
import org.example.project.features.product.domain.service.ProductExtrasService
import org.example.project.features.product.domain.service.ProductService
import org.example.project.features.promotion.data.repository.PromotionRepositoryImpl
import org.example.project.features.promotion.data.service.PromotionServiceImpl
import org.example.project.features.promotion.domain.repository.PromotionRepository
import org.example.project.features.promotion.domain.service.PromotionService
import org.example.project.features.reservation.data.service.ReservationRuleServiceImpl
import org.example.project.features.reservation.data.service.ReservationServiceImpl
import org.example.project.features.reservation.data.service.TableAssignmentServiceImpl
import org.example.project.features.reservation.domain.service.ReservationRuleService
import org.example.project.features.reservation.domain.service.ReservationService
import org.example.project.features.reservation.domain.service.TableAssignmentService
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.data.service.SalesInvoiceDatevExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceDatevExportService
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.features.scheduler.data.service.BuiltInJobsSeeder
import org.example.project.features.scheduler.data.service.NextRunCalculator
import org.example.project.features.scheduler.data.service.ScheduledJobServiceImpl
import org.example.project.features.scheduler.data.service.SchedulerEngine
import org.example.project.features.scheduler.data.service.executors.BackupJobExecutor
import org.example.project.features.scheduler.data.service.executors.ContractInstalmentJobExecutor
import org.example.project.features.scheduler.data.service.executors.DatevAutoExportJobExecutor
import org.example.project.features.scheduler.data.service.executors.EntryLogRetentionJobExecutor
import org.example.project.features.scheduler.data.service.executors.ReversalJobExecutor
import org.example.project.features.scheduler.data.service.executors.UpdateCheckJobExecutor
import org.example.project.features.scheduler.domain.repository.JobExecutionRepository
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.features.scheduler.domain.service.ScheduledJobService
import org.example.project.features.setup.data.service.ConfigurationServiceImpl
import org.example.project.features.setup.data.service.EntryLogServiceImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.features.setup.data.service.ShopInfoServiceImpl
import org.example.project.features.setup.data.service.VatRateGuard
import org.example.project.features.setup.data.service.VatServiceImpl
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.setup.domain.service.ConfigurationService
import org.example.project.features.setup.domain.service.InitialSetupService
import org.example.project.features.setup.domain.service.ShopIdentityChangeGuard
import org.example.project.features.setup.domain.service.ShopInfoService
import org.example.project.features.statistics.data.service.MonthlyCloseFinancialsImpl
import org.example.project.features.statistics.data.service.StatisticsServiceImpl
import org.example.project.features.statistics.domain.service.StatisticsService
import org.example.project.features.supplier.data.service.SupplierServiceImpl
import org.example.project.features.supplier.domain.service.SupplierService
import org.example.project.features.table.data.service.LocationTypeServiceImpl
import org.example.project.features.table.data.service.RTableServiceImpl
import org.example.project.features.table.data.service.RoomElementServiceImpl
import org.example.project.features.table.data.service.TableSessionOwnerPolicyImpl
import org.example.project.features.table.data.service.TableSessionServiceImpl
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.service.LocationTypeService
import org.example.project.features.table.domain.service.RTableService
import org.example.project.features.table.domain.service.RoomElementService
import org.example.project.features.table.domain.service.TableSessionOwnerPolicy
import org.example.project.features.table.domain.service.TableSessionService
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.StationSessionServiceImpl
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.data.service.TerminalServiceImpl
import org.example.project.features.terminal.domain.service.StationSessionService
import org.example.project.features.terminal.domain.service.TerminalFiscalGateway
import org.example.project.features.terminal.domain.service.TerminalService
import org.example.project.features.timetracking.data.service.CostCenterServiceImpl
import org.example.project.features.timetracking.data.service.DatevExportServiceImpl
import org.example.project.features.timetracking.data.service.TimeApprovalServiceImpl
import org.example.project.features.timetracking.data.service.TimeTrackingServiceImpl
import org.example.project.features.timetracking.data.service.TimeValidationServiceImpl
import org.example.project.features.timetracking.data.service.WageTypeServiceImpl
import org.example.project.features.timetracking.domain.service.CostCenterService
import org.example.project.features.timetracking.domain.service.DatevExportService
import org.example.project.features.timetracking.domain.service.TimeApprovalService
import org.example.project.features.timetracking.domain.service.TimeTrackingService
import org.example.project.features.timetracking.domain.service.TimeValidationService
import org.example.project.features.timetracking.domain.service.WageTypeService
import org.example.project.features.tse.data.repository.TseExportArchiveRepositoryImpl
import org.example.project.features.tse.data.service.FilteredExportClientValidator
import org.example.project.features.tse.data.service.TseConfigurationServiceImpl
import org.example.project.features.tse.data.service.TseExportArchiveService
import org.example.project.features.tse.data.service.TsePendingSweeper
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.data.service.TseSigningRetryService
import org.example.project.features.tse.data.service.TseTerminalFiscalGateway
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.repository.TseExportArchiveRepository
import org.example.project.features.tse.domain.repository.TseSigningQueueRepository
import org.example.project.features.tse.domain.service.TseConfigurationService
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.tse.findings.TseExplanationRepository
import org.example.project.features.tse.findings.TseFindingService
import org.example.project.features.tse.findings.TseTanrRepository
import org.example.project.features.tse.verification.TseExportDirectory
import org.example.project.features.tse.verification.TseExportSidecars
import org.example.project.features.tse.verification.TseVerificationService
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.data.repository.UpdateSettingsRepositoryImpl
import org.example.project.features.update.data.service.AndroidClientStager
import org.example.project.features.update.data.service.ClientArtifactStore
import org.example.project.features.update.data.service.ClientUpdateServiceImpl
import org.example.project.features.update.data.service.UpdateApplier
import org.example.project.features.update.data.service.UpdatePreflight
import org.example.project.features.update.data.service.UpdateServiceImpl
import org.example.project.features.update.data.service.UpdateStager
import org.example.project.features.update.data.service.exitFromNewThread
import org.example.project.features.update.domain.repository.UpdateSettingsRepository
import org.example.project.features.update.domain.service.ClientUpdateService
import org.example.project.features.update.domain.service.UpdateService
import org.example.project.features.voucher.data.service.VoucherServiceImpl
import org.example.project.features.voucher.domain.service.VoucherService
import org.example.project.features.zBon.data.service.ZBonServiceImpl
import org.example.project.features.zBon.domain.CashScope
import org.example.project.features.zBon.domain.FiscalShopIdentityGuard
import org.example.project.features.zBon.domain.KassenSwVersionPostRestoreHook
import org.example.project.features.zBon.domain.OpenBusinessCheck
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.infrastructure.security.RoutePermissionsRuleCatalog
import org.example.project.infrastructure.security.RouteRuleCatalog
import org.example.project.printer.core.formatter.ZBonFormatter
import org.example.project.printer.di.printerEngineModule
import org.example.project.resolveInstallDir
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.system.exitProcess

val servicesModule =
    module {
        // Shop Configuration Manager — register the existing singleton as Koin-provided interface
        single<IShopConfigurationManager> { ShopConfigurationManager }

        // Token Claims Provider — bridges Security.kt auth functions to server-common interface
        single<TokenClaimsProvider> { TokenClaimsProviderImpl() }

        // Service Layer - Business Logic Layer implementations
        single<EmployeeService> {
            EmployeeServiceImpl(get(), get(), get(), get(), getAll<StaffRosterListener>())
        }
        // Staff-roster listeners — multi-binding collected via getAll<StaffRosterListener>().
        single<StaffRosterListener>(qualifier = named("rotation")) { RotationStaffRosterListener() }
        // No interface: the group API has exactly one implementation and one consumer, and an
        // interface with a single implementor is indirection without a seam.
        singleOf(::PermissionGroupServiceImpl)
        singleOf(::PermissionDiagnosticsServiceImpl)
        singleOf(::EmployeeInfoServiceImpl) { bind<EmployeeInfoService>() }
        singleOf(::WorkScheduleServiceImpl) { bind<WorkScheduleService>() }
        // Fiscal closing duty for shop-identity edits (DSFinV-K Tz. 3.2) behind the platform port.
        single<ShopIdentityChangeGuard> { FiscalShopIdentityGuard() }
        singleOf(::ShopInfoServiceImpl) { bind<ShopInfoService>() }
        singleOf(::OrderServiceImpl) { bind<OrderService>() }
        singleOf(::ProductServiceImpl) { bind<ProductService>() }
        singleOf(::PfandTypeServiceImpl) { bind<PfandTypeService>() }
        singleOf(::ChangelogServiceImpl) { bind<ChangelogService>() }
        singleOf(::FavouriteProductServiceImpl) { bind<FavouriteProductService>() }
        singleOf(::ProductExtrasServiceImpl) { bind<ProductExtrasService>() }
        singleOf(::ProductCategoryServiceImpl) { bind<ProductCategoryService>() }
        singleOf(::ProductExtrasAssignmentServiceImpl) { bind<ProductExtrasAssignmentService>() }
        singleOf(::ChoiceGroupServiceImpl) { bind<ChoiceGroupService>() }
        singleOf(::ProductChoiceGroupServiceImpl) { bind<ProductChoiceGroupService>() }
        singleOf(::RTableServiceImpl) { bind<RTableService>() }
        singleOf(::TableSessionServiceImpl) { bind<TableSessionService>() }
        singleOf(::RoomElementServiceImpl) { bind<RoomElementService>() }
        singleOf(::LocationTypeServiceImpl) { bind<LocationTypeService>() }
        singleOf(::InitialSetupServiceImpl) { bind<InitialSetupService>() }

        // The one writer of a Vorgang's tse_transaction row (E2E R2-7, design 2026-09-22 §4.1).
        single { TseVorgangSigner(get(), get(), get()) }

        // Simple TSE Service Factory
        single<TseServiceFactory> {
            TseServiceFactory(get(), get(), get())
        }
        // The order, cancellation and bill services sign sales through this port (spec §5 hard seam).
        singleOf(::TseSaleSigningGateway) { bind<SaleSigningGateway>() }
        single<VatRateAdmission> { VatRateGuard }
        singleOf(::VatServiceImpl) { bind<VatService>() }
        singleOf(::PaymentTypeServiceImpl) { bind<PaymentTypeService>() }
        // The shared TSE-signing path, injected into CashBookTransferServiceImpl so it does not
        // have to depend on the concrete KassenbuchServiceImpl for it.
        singleOf(::KassenbuchTseSigner)
        singleOf(::KassenbuchServiceImpl) { bind<KassenbuchService>() }
        singleOf(::CashBookTransferServiceImpl) { bind<CashBookTransferService>() }
        singleOf(::CashBookCloseServiceImpl) { bind<CashBookCloseService>() }
        singleOf(::CashBookServiceImpl) { bind<CashBookService>() }
        singleOf(::DatevExportLogRepositoryImpl) { bind<DatevExportLogRepository>() }
        singleOf(::DatevExportLogServiceImpl) { bind<DatevExportLogService>() }
        singleOf(::DatevXmlExportServiceImpl) { bind<DatevXmlExportService>() }
        singleOf(::DatevApExportServiceImpl) { bind<DatevApExportService>() }
        singleOf(::DatevBuchungsstapelExportServiceImpl) { bind<DatevBuchungsstapelExportService>() }
        singleOf(::DatevBuchungsstapelBelegeExportServiceImpl) { bind<DatevBuchungsstapelBelegeExportService>() }
        singleOf(::DatevExportCatchupServiceImpl) { bind<DatevExportCatchupService>() }
        single { KassenbuchBelegResolver(zBonService = get()) }
        singleOf(::VoucherServiceImpl) { bind<VoucherService>() }
        // Promotion services
        singleOf(::PromotionRepositoryImpl) { bind<PromotionRepository>() }
        singleOf(::PromotionServiceImpl) { bind<PromotionService>() }
        singleOf(::SalesInvoiceBillInvoiceLink) { bind<BillInvoiceLink>() }
        singleOf(::MapperBillSummaries) { bind<BillSummaries>() }
        singleOf(::BillServiceImpl) { bind<BillService>() }

        singleOf(::CardTerminalServiceImpl) { bind<CardTerminalService>() }
        singleOf(::CardPaymentServiceImpl) { bind<CardPaymentService>() }
        singleOf(::CardSettlementServiceImpl) { bind<CardSettlementService>() }
        singleOf(::OrderItemServiceImpl) { bind<OrderItemService>() }
        // createdAtStart: RotationNextNotifier (a static object) must have its service before the first publish.
        single<RotationAssignmentService>(createdAtStart = true) {
            RotationAssignmentServiceImpl(get(), get(), get(), get()).also { RotationNextNotifier.install(it) }
        }
        singleOf(::TableSessionOwnerPolicyImpl) { bind<TableSessionOwnerPolicy>() }
        singleOf(::ZBonServiceImpl) { bind<ZBonService>() }
        singleOf(::StatisticsServiceImpl) { bind<StatisticsService>() }
        singleOf(::EntryLogServiceImpl) { bind<EntryLogService>() }
        singleOf(::ChangeFeedServiceImpl) { bind<ChangeFeedService>() }
        single<TransactionAuditService> {
            TransactionAuditServiceImpl(
                repository = get(),
                json = get(AppJson),
            )
        }

        // Hybrid Authorization System services
        singleOf(::TemporarySessionServiceImpl) { bind<TemporarySessionService>() }
        single<ApprovalRequestService> { ApprovalRequestServiceImpl(getAll<ApprovalDetailsResolver>()) }
        // Approval-details resolvers — multi-binding collected via getAll<ApprovalDetailsResolver>().
        single<ApprovalDetailsResolver>(qualifier = named("orderDetails")) { OrderApprovalDetailsResolver() }

        // Refresh Token services (JWT rotation + session-cutoff policy enforcement)
        single<RefreshTokenRepository> { RefreshTokenRepositoryImpl() }
        single<RefreshTokenService> {
            RefreshTokenServiceImpl(
                repository = get(),
                cutoffSupplier = { get<IShopConfigurationManager>().effectiveSessionCutoffEpochSeconds() },
            )
        }

        // Customer Management services
        singleOf(::CustomerServiceImpl) { bind<CustomerService>() }
        singleOf(::CustomerAddressServiceImpl) { bind<CustomerAddressService>() }

        // Printer engine (from server-printer module: USB, Network, IPP services + formatter + factory)
        includes(printerEngineModule)

        // Printer orchestration services (database-bound, stay in main server)
        singleOf(::PrinterServiceImpl) { bind<PrinterService>() }
        singleOf(::PrinterLocationServiceImpl) { bind<PrinterLocationService>() }
        singleOf(::PrintJobHistoryServiceImpl) { bind<PrintJobHistoryService>() }
        // The Z-Bon is fiscal content: its formatter lives in the host, not in the printer engine.
        // ZBonRoutes hands PrinterService.printZBon the finished bytes through it.
        singleOf(::ZBonFormatter)

        // Document Management services
        // The Kassenbuch feature owns the link lookup; the document mapper only consumes it.
        singleOf(::KassenbuchDocumentLinkResolver) { bind<DocumentKassenbuchLinkResolver>() }
        // Structured e-invoice parsing on upload. Stateless and dependency-free, so a plain single.
        single<EInvoiceParser> { DefaultEInvoiceParser() }
        // CAMT/MT940 parsing on upload, alongside the e-invoice parser and equally stateless.
        single<BankStatementParser> { DefaultBankStatementParser() }
        // Five collaborators, not four: sub-project 3 handed the statement parser in here.
        single<DocumentManagementService> { DocumentManagementServiceImpl(get(), get(), get(), get(), get()) }

        single<SalesInvoiceService> { SalesInvoiceServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single<SalesInvoiceDatevExportService> { SalesInvoiceDatevExportServiceImpl(get(), get(), get()) }
        // The Buchungsbeginn. Bound before the two list services because both take it.
        singleOf(::BookkeepingStartServiceImpl) { bind<BookkeepingStartService>() }
        singleOf(::InvoiceListServiceImpl) { bind<InvoiceListService>() }
        singleOf(::InvoiceCancellationServiceImpl) { bind<InvoiceCancellationService>() }
        singleOf(::SupplierServiceImpl) { bind<SupplierService>() }
        // Parties: duplicate suggestions and the soft merge. Takes the audit service because a
        // merge is master-data with fiscal reach and every one of them is recorded.
        singleOf(::BusinessPartnerServiceImpl) { bind<BusinessPartnerService>() }
        // Custom exports: the catalogue is Kotlin, so this holds no repository — it reads the
        // catalogue object and writes the two definition tables through the DAO directly.
        // Constructed by hand, not `singleOf`: the only constructor parameter is the `rowCap: Int`
        // default, and `singleOf` resolves every parameter from the graph — so it looked for an `Int`
        // definition and threw. Nothing injected this service until the routes existed, which is why
        // the failure surfaced only now, as a 500 on the first request rather than at startup.
        single<CustomExportService> { CustomExportServiceImpl() }
        // The master-data handover. Reads the shop's own DATEV configuration, never the caller's:
        // a Beraternummer taken from a request can send a whole master file to the wrong advisor.
        single<DatevStammdatenExportService> { DatevStammdatenExportServiceImpl(get(), get()) }
        singleOf(::DebtorServiceImpl) { bind<DebtorService>() }
        // Recurring obligations. Shop-implicit, like every endpoint in this feature.
        singleOf(::ContractServiceImpl) { bind<ContractService>() }
        // Zuordnung. Writes go through AllocationWriter, never straight to the repository.
        singleOf(::AllocationServiceImpl) { bind<AllocationService>() }
        // The match ladder: the gatherer loads, the pure rankers decide, the service serves.
        singleOf(::MatchFactsGatherer)
        singleOf(::MatchSuggestionServiceImpl) { bind<MatchSuggestionService>() }
        // Un-allocates what a returned direct debit undid. Driven by the scheduled job below;
        // requires an ambient transaction, which the job's executor opens.
        singleOf(::ReversalProcessor)

        // Materialises the rolling window of contract instalments. Driven by the scheduled job
        // below; requires an ambient transaction, which the job's executor opens.
        singleOf(::InstalmentGenerator)
        // Our own bank accounts. The resolver is a plain collaborator — it needs the same repository.
        singleOf(::StatementAccountResolver)
        singleOf(::BankAccountServiceImpl) { bind<BankAccountService>() }
        singleOf(::PdfGenerationServiceImpl) {
            bind<PdfGenerationService>()
            bind<ReceiptPdfRenderer>()
        }
        // The fiscal Kassenbuch/Z-Bon code reaches documents and PDFs only through this port (spec §13);
        // the implementation delegates to the two document singletons above.
        singleOf(::KassenbuchDocumentsImpl) { bind<KassenbuchDocuments>() }

        // Number tracker cleanup service (auto-start periodic cleanup for bill/voucher number reservations)
        // Eager singleton - initialized immediately so trackers can register
        single(createdAtStart = true) {
            NumberTrackerCleanupService()
        }

        // TSE (Technical Security Element) Configuration Service
        // Uses ShopConfigurationManager for cached JSON configuration storage
        // Explicit constructor call: the reload seam (the hardware reload lives in this host) is supplied here, not resolved by singleOf.
        single<TseConfigurationService> { TseConfigurationServiceImpl(get(), get()) { configure -> reloadTseProviders(configure) } }

        // DSFinV-K Export Service (German fiscal compliance CSV export)
        single<DsfinvkExportService> {
            DsfinvkExportServiceImpl(json = get(AppJson), configRepository = get(), invoices = get(), tseAdminProvider = get(), exportArchives = get())
        }

        // TSE Signing Queue Service (for Nachsignieren - late signing when TSE unavailable)
        single<TseSigningQueueService> {
            TseSigningQueueServiceImpl(
                queueRepository = get(),
                tseTransactionRepository = get(),
                tseSignClient = get(),
                transactionAuditService = get(),
                signer = get(),
            )
        }

        // TSE Signing Retry Service (background job for processing queued signing requests)
        // Eager singleton - initialized immediately and starts background processing
        single(createdAtStart = true) {
            TseSigningRetryService(get()).apply { start() }
        }

        // TSE PENDING-without-queue-row Sweeper (round-3 TSER-9): rescues Vorgänge stranded as PENDING
        // when the enqueue never happened (crash between commit and enqueue, or enqueue failure), which
        // the Nachsignieren worker — a queue-row-only path — could never otherwise sign.
        single(createdAtStart = true) {
            TsePendingSweeper(get(), get()).apply { start() }
        }

        // TSE Export Directory resolver (design spec §6.1) — <getDatevXmlExportPath()>/tse, falling
        // back to exports/tse. Both the export operations (TseAdminRoutes) and the verification
        // service below resolve through it so a default-path export is always where verify looks.
        single { TseExportDirectory(get()) }

        // Validates the optional client filter of the time-/transaction-filtered exports (F69).
        single { FilteredExportClientValidator(get(), get()) }

        // TSE Log-Sequence Verification Service (AGD §5.2 plausibility checks over an export TAR).
        single { TseExportSidecars() }
        single { TseVerificationService(exportDir = get(), tanrs = get(), sidecars = get(), archives = get()) }

        // The TSE archive register (real-TSE QA rt14): export-and-delete archives and those an
        // administrator registers; the DSFinV-K export ships them for periods erased from the TSE.
        single<TseExportArchiveRepository> { TseExportArchiveRepositoryImpl() }
        single { TseExportArchiveService(adminProvider = get(), exportDir = get(), repository = get()) }

        // TANR findings and their append-only explanations (design 2026-09-22 §4.7).
        single { TseTanrRepository() }
        single { TseExplanationRepository() }
        single { TseFindingService(get(), get()) }

        // Terminal Service (for multi-client DSFinV-K compliance). Its TSE client lifecycle, admin
        // sitting, ecr signing-state check and Stammdaten freeze go through the fiscal gateway.
        single<TerminalFiscalGateway> { TseTerminalFiscalGateway(get()) }
        singleOf(::TerminalServiceImpl) { bind<TerminalService>() }

        // Station-session exchange (order-station shell): device token -> masked station JWT.
        singleOf(::BrowserStationPolicy)
        singleOf(::StationSessionServiceImpl) { bind<StationSessionService>() }

        // Maintainer account: login + throttle state live for the process (the throttle is in memory).
        single { MaintenanceAuthService() }
        single<RouteRuleCatalog> { RoutePermissionsRuleCatalog() }
        single { LogSearchService.default(get()) }

        // Terminal Capability Resolver (A4 Task 1): in-memory device -> terminal-capability cache
        // feeding the RouteAuthorizationPlugin ceiling. Invalidated directly by TerminalServiceImpl
        // on every terminal mutation (A4 Task 2).
        singleOf(::TerminalCapabilityResolver)
        singleOf(::GroupCapabilityResolver)

        // Backup and Restore Service (database backup/restore via pg_dump/pg_restore)
        // Seeder provider is lazy to break the cycle: seeder → ConfigurationService → backup-config writers.
        single<LiveDataSourceProvider> { LiveDataSourceProvider { DatabaseFactory.getDataSource() } }
        // Post-restore hooks — multi-binding collected via getAll<PostRestoreHook>().
        single<PostRestoreHook>(qualifier = named("kassenSwVersion")) { KassenSwVersionPostRestoreHook(get()) }
        single<BackupRestoreService> {
            BackupRestoreServiceImpl(get(), get(), get(), restoredPathReview = get(), postRestoreHooks = getAll<PostRestoreHook>()) { get() }
        }
        single<RestoredPathReviewService> { RestoredPathReviewServiceImpl(get(), get()) }
        single<BackupCopyService> { BackupCopyServiceImpl() }
        single {
            val configManager = get<IShopConfigurationManager>()
            BackupCopyCoordinator(
                get(),
                get(),
                copyTargetAwaitsReview = { RestoredPathSetting.BACKUP_COPY_PATH in configManager.getRestoredPathsPendingReview() },
            )
        }

        // Time tracking services
        singleOf(::TimeTrackingServiceImpl) { bind<TimeTrackingService>() }
        singleOf(::TimeValidationServiceImpl) { bind<TimeValidationService>() }
        singleOf(::TimeApprovalServiceImpl) { bind<TimeApprovalService>() }
        singleOf(::CostCenterServiceImpl) { bind<CostCenterService>() }
        singleOf(::WageTypeServiceImpl) { bind<WageTypeService>() }
        singleOf(::DatevExportServiceImpl) { bind<DatevExportService>() }

        // Reservation services
        singleOf(::ReservationServiceImpl) { bind<ReservationService>() }
        singleOf(::ReservationRuleServiceImpl) { bind<ReservationRuleService>() }

        // Opening hours services
        singleOf(::OpeningHoursServiceImpl) { bind<OpeningHoursService>() }

        // Table assignment service
        singleOf(::TableAssignmentServiceImpl) { bind<TableAssignmentService>() }

        // Scheduler: NextRunCalculator (pure utility)
        single { NextRunCalculator() }

        // Configuration service — shared read/write facade used by the scheduler executors.
        // Receives a lazy seeder provider to avoid a Koin constructor cycle
        // (ConfigurationServiceImpl <- BuiltInJobsSeeder <- ConfigurationService).
        single<ConfigurationService> {
            ConfigurationServiceImpl(get(), get()) { get() }
        }

        // System user id used by DATEV auto-export to attribute generated export rows.
        single(named("systemUserId")) { UUID.fromString("00000000-0000-0000-0000-000000000001") }

        // Scheduler: BackupJobExecutor — bound to ScheduledJobExecutor interface for multi-binding.
        // Manual single { } form because singleOf doesn't honor the default `shopId: Long = 1L` param.
        single<ScheduledJobExecutor>(qualifier = named("backupJobExecutor")) {
            BackupJobExecutor(get(), get(), copyCoordinator = get())
        }

        // Scheduler: DatevAutoExportJobExecutor — bound to ScheduledJobExecutor for multi-binding
        // via getAll<ScheduledJobExecutor>(). A named qualifier is used so the definition is
        // distinct from BackupJobExecutor's binding for the same interface.
        single<ScheduledJobExecutor>(qualifier = named("datevAutoExportExecutor")) {
            DatevAutoExportJobExecutor(get(), get(), get(named("systemUserId")))
        }

        // Entry log retention job (used by the scheduler executor below).
        singleOf(::EntryLogRetentionJob)

        // Scheduler: EntryLogRetentionJobExecutor — prunes entry_log rows older than 90 days daily.
        single<ScheduledJobExecutor>(qualifier = named("entryLogRetentionExecutor")) {
            EntryLogRetentionJobExecutor(get())
        }

        // Scheduler: ContractInstalmentJobExecutor — materialises the rolling window of contract
        // instalments daily. Named qualifier for the same multi-binding reason as its neighbours.
        single<ScheduledJobExecutor>(qualifier = named("contractInstalmentExecutor")) {
            ContractInstalmentJobExecutor(get())
        }

        // Scheduler: ReversalJobExecutor — sweeps returned direct debits nightly so an invoice the
        // bank un-paid stops reading as paid. Named qualifier for the same multi-binding reason as
        // its neighbours: the engine collects executors via getAll<ScheduledJobExecutor>().
        single<ScheduledJobExecutor>(qualifier = named("reversalSweepExecutor")) {
            ReversalJobExecutor(get(), get())
        }

        // ---- Software update ------------------------------------------------------------------
        //
        // The update-token key comes from the host's systemd EnvironmentFile, never from the
        // database, so a backup restored elsewhere carries a token nobody can open. A missing key
        // is NOT a boot failure: TokenCipher reports isAvailable = false and the private channel
        // simply says the token must be re-entered.
        single { TokenCipher(System.getenv("UPDATE_TOKEN_KEY")) }
        single<UpdateSettingsRepository> { UpdateSettingsRepositoryImpl(get()) }

        // Its own OkHttp client: followRedirects = false is part of GithubReleaseClient's contract
        // (the credential is dropped by hand on the hop to signed object storage), so the shared
        // application client must not be reused here.
        single { GithubReleaseClient(GithubReleaseClient.defaultHttpClient()) }

        single { UpdateStager(File(resolveInstallDir(), UPDATE_STAGING_DIR)) }
        // A separate directory from UPDATE_STAGING_DIR: the server's apply sweeps that one, and tills
        // download their APK after the server restarted. Same install dir, so it survives updates.
        single { ClientArtifactStore(File(resolveInstallDir(), CLIENT_UPDATE_DIR), get()) }
        single { AndroidClientStager(get(), get()) }
        single<ClientUpdateService> { ClientUpdateServiceImpl(get()) }

        single {
            val scope = this
            UpdateApplier(
                installDir = resolveInstallDir(),
                // Resolved lazily: the backup service is only needed on the rare apply path, and
                // reaching for it here would drag it into the eager scheduler singleton below.
                createBackup = { createPreApplyBackup(scope.get(), scope.get()) },
                extractServerJar = UpdateApplier::serverJarFrom,
                // Not exitProcess(code) in place: this runs on a Netty event-loop thread (see exitFromNewThread).
                exitProcess = { code -> exitFromNewThread(code) },
            )
        }

        single {
            val executions = get<JobExecutionRepository>()
            val queue = get<TseSigningQueueRepository>()
            val settings = get<UpdateSettingsRepository>()
            UpdatePreflight(
                // THE single definition of "business is open since the last Kassenabschluss",
                // shared with the Z-Bon and Stammdaten paths. A second query that disagreed with
                // the Z-Bon path about what "open" means would be worse than no preflight at all.
                // FISCAL_ONLY: only the fiscal books (the Ladenkasse) hold turnover that a restart can
                // strand; an unclosed Tresor or other non-fiscal book must not hold an update back.
                hasOpenBusiness = { dbQuery { OpenBusinessCheck.hasOpenBusiness(CashScope.FISCAL_ONLY) } },
                countOpenSessions = { dbQuery { countOpenTableSessions() } },
                hasOpenTseWork = { dbQuery { hasUnfinishedTseWork(queue) } },
                // Excluding UPDATE_CHECK's own executions: the engine marks this job's row RUNNING
                // before calling the executor, so counting it would refuse every update forever.
                countRunningJobs = { dbQuery { executions.countRunningExcludingType(ScheduledJobType.UPDATE_CHECK) } },
                freeDiskBytes = { resolveInstallDir().usableSpace },
                requiredBytes = { requiredInstallBytes(settings.load().stagedPath) },
            )
        }

        single<UpdateService> {
            UpdateServiceImpl(
                settings = get(),
                releases = get(),
                stager = get(),
                clientStager = get(),
                clientArtifacts = get(),
                preflight = get(),
                applier = get(),
                // The running build, from the generated BuildConfig (gradle's `app.version`).
                // An unparseable version falls back to an unreachably high one, not to 0.0.0:
                // 0.0.0 would make every published release look newer and could auto-install one
                // on a build whose version string nobody could read. Refusing to update is the
                // safe direction for a mis-stamped build.
                currentVersion = SemanticVersion.parse(BuildConfig.APP_VERSION) ?: UNKNOWN_VERSION,
                // Only to answer "may a token be stored on this host at all" before trying; the
                // repository remains the sole place a token is actually sealed or opened.
                cipher = get(),
                // The delta download: the installed jar is what blocks are copied out of. The
                // rebuild itself is assembled in UpdateStager.workDir — a subdirectory of the
                // staging directory, which the stager already sweeps, so an abandoned rebuild
                // cannot leak ~127 MB and an unverified one never sits in the staging directory
                // proper.
                resolveInstallDir = { resolveInstallDir() },
            )
        }

        // ---- Problem reports -------------------------------------------------------------------
        // Its own OkHttp client for the same reason as GithubReleaseClient: no redirect following.
        single<IssueReportSettingsRepository> { IssueReportSettingsRepositoryImpl(get()) }
        single<IssueTracker> { GithubIssueClient(GithubReleaseClient.defaultHttpClient()) }
        single<IssueReportService> {
            val updateSettings = get<UpdateSettingsRepository>()
            val shopInfo = get<ShopInfoRepository>()
            IssueReportServiceImpl(
                settings = get(),
                tracker = get(),
                cipher = get(),
                updateChannel = { updateSettings.load().channel },
                // Read-only: the report feature borrows the update token only when the Administrator
                // opts in, and only for the update source's own API host (checked by the service).
                updateTokenSource = {
                    updateSettings.load().let { UpdateTokenSource(it.apiBaseUrl, it.token, it.tokenUndecryptable) }
                },
                // ShopInfo is an Exposed entity, so the read needs an ambient transaction.
                shopName = { dbQuery { shopInfo.findAll().firstOrNull()?.shopName } },
                logTail = { LogTail.default().read() },
                appVersion = BuildConfig.APP_VERSION,
                platform = "${System.getProperty("os.name")} ${System.getProperty("os.version")} / Java ${System.getProperty("java.version")}",
            )
        }

        // Scheduler: UpdateCheckJobExecutor — nightly GitHub release check. Bound to
        // ScheduledJobExecutor for multi-binding via getAll<ScheduledJobExecutor>(); the named
        // qualifier keeps this definition distinct from the other executors' bindings.
        single<ScheduledJobExecutor>(qualifier = named("updateCheckExecutor")) {
            UpdateCheckJobExecutor(get())
        }

        // Scheduler: BuiltInJobsSeeder — upserts the five built-in scheduled job rows
        single { BuiltInJobsSeeder(get(), get(), get()) }

        // Scheduler: Engine — eager singleton, seeds built-in rows then starts background tick.
        // Seeding wraps the repository calls in a dbQuery transaction (Exposed convention:
        // services control transactions; the seeder itself is non-transactional).
        single(createdAtStart = true) {
            val seeder = get<BuiltInJobsSeeder>()
            runBlocking { dbQuery { seeder.seedAll() } }
            val configManager = get<IShopConfigurationManager>()
            SchedulerEngine(
                get(),
                get(),
                getAll<ScheduledJobExecutor>(),
                get(),
                pendingRestoredPaths = { configManager.getRestoredPathsPendingReview().keys },
            ).apply { start() }
        }

        // Scheduler: Service
        single<ScheduledJobService> {
            ScheduledJobServiceImpl(get(), get(), getAll<ScheduledJobExecutor>(), get(), get())
        }

        // Monthly Close checks (stateless — safe as singletons)
        singleOf(::ChainIntegrityCheck)
        singleOf(::BillsOutsideZBonCheck)
        singleOf(::VatConsistencyCheck)
        singleOf(::OpenBillsCheck)
        singleOf(::UnsignedTseCheck)
        singleOf(::SimulatedSignatureRepository)
        singleOf(::SimulatedSignatureCheck)
        singleOf(::PossibleDoubleSignCheck)
        singleOf(::TseTanrContinuityCheck)
        singleOf(::CashMovementsDocumentedCheck)
        singleOf(::DatevExportedCheck)

        // Monthly Close ports: the fiscal month close reads the backoffice statistics and the
        // accounting DATEV export log / AP export only through these (B2-3 A7). No default and no
        // nullable parameter on the consumer: a missing binding fails at resolution.
        singleOf(::MonthlyCloseFinancialsImpl) { bind<MonthlyCloseFinancials>() }
        singleOf(::MonthlyCloseDatevStatusImpl) { bind<MonthlyCloseDatevStatus>() }

        // Monthly Close Service
        singleOf(::MonthlyCloseServiceImpl) { bind<MonthlyCloseService>() }
    }

/** Stands in for a build whose version string will not parse; nothing is ever newer than this. */
private val UNKNOWN_VERSION = SemanticVersion(Int.MAX_VALUE, 0, 0)

/** Where verified release artifacts wait, next to the jar they will replace. */
private const val UPDATE_STAGING_DIR = "updates"
private const val CLIENT_UPDATE_DIR = "update-client"

/**
 * Room for the pre-apply database dump on top of the artifact itself.
 *
 * Deliberately generous and deliberately a constant: the dump's size is not knowable cheaply, and
 * the only failure worth avoiding here is starting an update that runs the disk out mid-dump.
 */
internal const val DATABASE_BACKUP_HEADROOM_BYTES = 2L * 1024 * 1024 * 1024

/** Runs inside the caller's transaction. */
private fun countOpenTableSessions(): Int =
    TableSessionTable
        .selectAll()
        .where { TableSessionTable.completed eq false }
        .count()
        .toInt()

/**
 * Server-wide unfinished TSE work.
 *
 * `TerminalFiscalGateway.hasPendingSigning` is scoped to one terminal, so it cannot
 * be reused here; the signing queue's own status counts are the global equivalent and are the
 * queue's single definition of "not finished yet".
 */
private suspend fun hasUnfinishedTseWork(queue: TseSigningQueueRepository): Boolean {
    val counts = queue.getStatusCounts()
    val unfinished =
        listOf(TseSigningStatus.PENDING, TseSigningStatus.PROCESSING, TseSigningStatus.QUEUED)
    return unfinished.any { (counts[it] ?: 0L) > 0L }
}

/**
 * Room for what an apply actually writes: the staged artifact, the `.bak` copy `UpdateApplier`
 * makes of the running jar before overwriting it, and the pre-apply database dump.
 *
 * Counting the staged file once was wrong even before delta updates -- the swap has always
 * written a second jar-sized file -- and it matters more now that the staged artifact is the jar
 * itself rather than an archive that happened to be larger than it.
 */
internal fun requiredInstallBytes(stagedPath: String?): Long {
    val staged = stagedPath?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L
    return staged * 2 + DATABASE_BACKUP_HEADROOM_BYTES
}

/**
 * The pre-apply database backup, written where scheduled backups already go.
 *
 * Returning a path the applier can verify is the whole contract: it refuses to swap the jar if the
 * file is missing or empty, because a jar rollback cannot undo a Flyway migration.
 */
private suspend fun createPreApplyBackup(
    backups: BackupRestoreService,
    config: ConfigurationService,
): String {
    val savePath =
        config.getBackupConfig(1L).defaultBackupPath
            ?: File(resolveInstallDir(), "backups").absolutePath
    return backups
        .createBackupToServer(
            BackupRestoreDto.CreateBackupToServerRequestDto(
                savePath = savePath,
                description = "pre-update-${LocalDate.now()}",
            ),
        ).filePath
}
