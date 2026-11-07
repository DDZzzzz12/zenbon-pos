package org.example.project.admin.core.di

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.example.project.admin.core.auth.canBrowseServerDirectories
import org.example.project.admin.core.auth.holds
import org.example.project.admin.core.data.InMemoryKeyValueStore
import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.admin.core.network.AdminHttpCall
import org.example.project.admin.core.network.KermitKtorLogger
import org.example.project.admin.core.util.file.pickFile
import org.example.project.admin.core.util.pdf.AdminPdfTokenProvider
import org.example.project.admin.features.allocations.presentation.AllocationsViewModel
import org.example.project.admin.features.auth.domain.AdminSignOut
import org.example.project.admin.features.auth.presentation.AdminLoginViewModel
import org.example.project.admin.features.auth.presentation.SessionEndedNotice
import org.example.project.admin.features.bank.presentation.BankAccountsViewModel
import org.example.project.admin.features.bootstrap.presentation.BootstrapSetupViewModel
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupsViewModel
import org.example.project.admin.features.contracts.presentation.ContractDetailViewModel
import org.example.project.admin.features.contracts.presentation.ContractsViewModel
import org.example.project.admin.features.customexport.presentation.CustomExportViewModel
import org.example.project.admin.features.dashboard.presentation.checklist.SetupChecklistViewModel
import org.example.project.admin.features.dashboard.presentation.sections.DayCloseViewModel
import org.example.project.admin.features.dashboard.presentation.sections.DayFiguresViewModel
import org.example.project.admin.features.dashboard.presentation.sections.OpenWorkViewModel
import org.example.project.admin.features.dashboard.presentation.sections.PendingAuthorizationsViewModel
import org.example.project.admin.features.dashboard.presentation.sections.RecentCashEntriesViewModel
import org.example.project.admin.features.dashboard.presentation.sections.RestoredPathsViewModel
import org.example.project.admin.features.dashboard.presentation.sections.TseHealthViewModel
import org.example.project.admin.features.dataexport.presentation.DatevOutputViewModel
import org.example.project.admin.features.dataexport.presentation.DsfinvkExportViewModel
import org.example.project.admin.features.debtors.presentation.DebtorDetailViewModel
import org.example.project.admin.features.debtors.presentation.DebtorsViewModel
import org.example.project.admin.features.documents.presentation.DocumentDetailViewModel
import org.example.project.admin.features.documents.presentation.DocumentUploadViewModel
import org.example.project.admin.features.documents.presentation.DocumentsViewModel
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureViewModel
import org.example.project.admin.features.extras.presentation.ExtrasViewModel
import org.example.project.admin.features.firstterminal.presentation.FirstTerminalSetupViewModel
import org.example.project.admin.features.floorplan.presentation.FloorPlanEditorViewModel
import org.example.project.admin.features.invoices.presentation.InvoicesViewModel
import org.example.project.admin.features.issuereport.presentation.IssueReportSettingsViewModel
import org.example.project.admin.features.issuereport.presentation.IssueReportViewModel
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchViewModel
import org.example.project.admin.features.permissions.presentation.PermissionGroupsViewModel
import org.example.project.admin.features.product.presentation.ProductViewModel
import org.example.project.admin.features.promotion.presentation.PromotionsViewModel
import org.example.project.admin.features.reports.presentation.AnalyticsViewModel
import org.example.project.admin.features.reports.presentation.CloseReadinessViewModel
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceEditorViewModel
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesViewModel
import org.example.project.admin.features.settings.presentation.sections.BrowserStationsViewModel
import org.example.project.admin.features.settings.presentation.sections.DocumentStorageViewModel
import org.example.project.admin.features.settings.presentation.sections.PaymentTypeViewModel
import org.example.project.admin.features.settings.presentation.sections.ShopInfoViewModel
import org.example.project.admin.features.settings.presentation.sections.SystemSettingsViewModel
import org.example.project.admin.features.settings.presentation.sections.VatMappingViewModel
import org.example.project.admin.features.settings.presentation.sections.VatViewModel
import org.example.project.admin.features.settings.presentation.sections.VoucherConfigViewModel
import org.example.project.admin.features.staff.presentation.StaffViewModel
import org.example.project.admin.features.staff.presentation.approvals.ApprovalsViewModel
import org.example.project.admin.features.staff.presentation.hr.HrProfileViewModel
import org.example.project.admin.features.staff.presentation.schedule.ScheduleViewModel
import org.example.project.admin.features.staff.presentation.time.TimeEntriesViewModel
import org.example.project.admin.features.supplier.presentation.SupplierDetailViewModel
import org.example.project.admin.features.supplier.presentation.SuppliersViewModel
import org.example.project.admin.features.tables.presentation.TableViewModel
import org.example.project.admin.features.terminal.presentation.TerminalsViewModel
import org.example.project.admin.features.tse.presentation.TseAdminGate
import org.example.project.admin.features.tse.presentation.TseFindingsViewModel
import org.example.project.admin.features.update.presentation.UpdateViewModel
import org.example.project.admin.features.voucher.presentation.VoucherViewModel
import org.example.project.admin.features.zBon.closing.ZBonClosingViewModel
import org.example.project.admin.features.zBon.presentation.SessionDetailViewModel
import org.example.project.admin.features.zBon.presentation.ZBonHubViewModel
import org.example.project.admin.features.zBon.presentation.ZBonListViewModel
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.remote.AccountingConfigurationApiClient
import org.example.project.core.data.remote.AllocationApiClient
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.AuditLogApiClient
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.data.remote.BackofficeConfigurationApiClient
import org.example.project.core.data.remote.BackupApiClient
import org.example.project.core.data.remote.BankAccountApiClient
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.BookkeepingStartApiClient
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.ChangelogApiClient
import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.data.remote.ContractApiClient
import org.example.project.core.data.remote.CustomExportApiClient
import org.example.project.core.data.remote.DatevExportApiClient
import org.example.project.core.data.remote.DatevExportLogApiClient
import org.example.project.core.data.remote.DebtorApiClient
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.MonthlyCloseApiClient
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.PermissionGroupApiClient
import org.example.project.core.data.remote.PfandTypeApiClient
import org.example.project.core.data.remote.PrintJobHistoryApiClient
import org.example.project.core.data.remote.PrinterDiscoveryApiClient
import org.example.project.core.data.remote.PrinterLocationApiClient
import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.data.remote.ProductChoiceGroupApiClient
import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.data.remote.PromotionApiClient
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.data.remote.SessionPolicyApiClient
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.data.remote.ShopInfoApiClient
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.data.remote.SupplierApiClient
import org.example.project.core.data.remote.TableSessionApiClient
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.data.remote.TimeTrackingApiClient
import org.example.project.core.data.remote.TseFindingsApiClient
import org.example.project.core.data.remote.TseQueueApiClient
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.data.remote.VatApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.data.remote.WebAccessApiClient
import org.example.project.core.data.remote.WorkScheduleApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.data.remote.impl.KtorAccountingConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorAllocationApiClient
import org.example.project.core.data.remote.impl.KtorApprovalRequestApiClient
import org.example.project.core.data.remote.impl.KtorAuditLogApiClient
import org.example.project.core.data.remote.impl.KtorAuthApiClient
import org.example.project.core.data.remote.impl.KtorBackofficeConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorBackupApiClient
import org.example.project.core.data.remote.impl.KtorBankAccountApiClient
import org.example.project.core.data.remote.impl.KtorBillApiClient
import org.example.project.core.data.remote.impl.KtorBookkeepingStartApiClient
import org.example.project.core.data.remote.impl.KtorCashBookApiClient
import org.example.project.core.data.remote.impl.KtorChangelogApiClient
import org.example.project.core.data.remote.impl.KtorChoiceGroupApiClient
import org.example.project.core.data.remote.impl.KtorContractApiClient
import org.example.project.core.data.remote.impl.KtorCustomExportApiClient
import org.example.project.core.data.remote.impl.KtorDatevExportApiClient
import org.example.project.core.data.remote.impl.KtorDatevExportLogApiClient
import org.example.project.core.data.remote.impl.KtorDebtorApiClient
import org.example.project.core.data.remote.impl.KtorDocumentManagementApiClient
import org.example.project.core.data.remote.impl.KtorDsfinvkApiClient
import org.example.project.core.data.remote.impl.KtorEcrConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorEmployeeApiClient
import org.example.project.core.data.remote.impl.KtorFiscalConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorInvoiceListApiClient
import org.example.project.core.data.remote.impl.KtorKassenbuchApiClient
import org.example.project.core.data.remote.impl.KtorMonthlyCloseApiClient
import org.example.project.core.data.remote.impl.KtorOrderApiClient
import org.example.project.core.data.remote.impl.KtorOrderItemApiClient
import org.example.project.core.data.remote.impl.KtorPaymentTypeApiClient
import org.example.project.core.data.remote.impl.KtorPermissionGroupApiClient
import org.example.project.core.data.remote.impl.KtorPfandTypeApiClient
import org.example.project.core.data.remote.impl.KtorPrintJobHistoryApiClient
import org.example.project.core.data.remote.impl.KtorPrinterDiscoveryApiClient
import org.example.project.core.data.remote.impl.KtorPrinterLocationApiClient
import org.example.project.core.data.remote.impl.KtorProductApiClient
import org.example.project.core.data.remote.impl.KtorProductCategoryApiClient
import org.example.project.core.data.remote.impl.KtorProductChoiceGroupApiClient
import org.example.project.core.data.remote.impl.KtorProductExtrasApiClient
import org.example.project.core.data.remote.impl.KtorPromotionApiClient
import org.example.project.core.data.remote.impl.KtorRTableApiClient
import org.example.project.core.data.remote.impl.KtorRoomElementApiClient
import org.example.project.core.data.remote.impl.KtorSalesInvoiceApiClient
import org.example.project.core.data.remote.impl.KtorSessionPolicyApiClient
import org.example.project.core.data.remote.impl.KtorShopConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorShopInfoApiClient
import org.example.project.core.data.remote.impl.KtorStatisticsApiClient
import org.example.project.core.data.remote.impl.KtorSupplierApiClient
import org.example.project.core.data.remote.impl.KtorTableSessionApiClient
import org.example.project.core.data.remote.impl.KtorTerminalApiClient
import org.example.project.core.data.remote.impl.KtorTimeTrackingApiClient
import org.example.project.core.data.remote.impl.KtorTseFindingsApiClient
import org.example.project.core.data.remote.impl.KtorTseQueueApiClient
import org.example.project.core.data.remote.impl.KtorTseSettingsApiClient
import org.example.project.core.data.remote.impl.KtorVatApiClient
import org.example.project.core.data.remote.impl.KtorVoucherApiClient
import org.example.project.core.data.remote.impl.KtorWebAccessApiClient
import org.example.project.core.data.remote.impl.KtorWorkScheduleApiClient
import org.example.project.core.data.remote.impl.KtorZBonApiClient
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.network.HttpCall
import org.example.project.core.network.HttpClientConfig
import org.example.project.core.network.RefreshTransport
import org.example.project.core.network.createPlatformEngine
import org.example.project.core.network.createPosHttpClient
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.OverlayEventBus
import org.example.project.core.presentation.components.notification.OverlayEventBusImpl
import org.example.project.core.presentation.error.ClientErrorReporter
import org.example.project.core.presentation.error.DefaultClientErrorReporter
import org.example.project.core.presentation.settings.DeviceSettingsMirror
import org.example.project.core.presentation.settings.NoOpDeviceSettingsMirror
import org.example.project.features.auditlog.presentation.viewmodel.AuditLogViewModel
import org.example.project.features.changelog.presentation.viewmodel.ChangelogViewModel
import org.example.project.features.employee.presentation.EmployeeApiSummaryProvider
import org.example.project.features.employee.presentation.EmployeePickerViewModel
import org.example.project.features.issuereport.data.remote.IssueReportApiClient
import org.example.project.features.issuereport.data.remote.impl.KtorIssueReportApiClient
import org.example.project.features.openinghours.data.remote.OpeningHoursApiClient
import org.example.project.features.openinghours.data.remote.impl.KtorOpeningHoursApiClient
import org.example.project.features.scheduler.data.remote.ScheduledJobApiClient
import org.example.project.features.scheduler.data.remote.impl.KtorScheduledJobApiClient
import org.example.project.features.scheduler.presentation.viewmodels.ScheduledJobsViewModel
import org.example.project.features.settings.data.remote.LocationTypeApiClient
import org.example.project.features.settings.data.remote.impl.KtorLocationTypeApiClient
import org.example.project.features.settings.presentation.viewmodel.DatevSettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.LocationTypeViewModel
import org.example.project.features.settings.presentation.viewmodel.OpeningHoursViewModel
import org.example.project.features.settings.presentation.viewmodel.PrinterSettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.RulesViewModel
import org.example.project.features.settings.presentation.viewmodel.SecuritySessionsViewModel
import org.example.project.features.settings.presentation.viewmodel.TseSettingsViewModel
import org.example.project.features.setup.data.remote.KtorSetupApiClient
import org.example.project.features.setup.data.remote.SetupApiClient
import org.example.project.features.update.data.remote.UpdateApiClient
import org.example.project.features.update.data.remote.impl.KtorUpdateApiClient
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel as DailyReportViewModel

/**
 * Koin DI graph for the admin panel.
 */
val adminModule =
    module {
        single<KeyValueStore> {
            LocalStorageKeyValueStore().also { store ->
                // Browsers upgraded from a build that kept the session in localStorage still hold a
                // 30-day refresh token there. TokenStore.clear() removes exactly the token keys and
                // nothing else (language, nav state, device id stay). Runs on first injection, which
                // AdminApp's root does at startup.
                TokenStore(store).clear()
            }
        }
        // The session lives in memory (access token) and an HttpOnly cookie (refresh token) — never
        // in localStorage.
        single { TokenStore(InMemoryKeyValueStore()) }
        single { SessionState(get(), StartupPolicy.RESUME_VIA_REFRESH_COOKIE) }
        single { SessionEndedNotice() }
        single<OverlayEventBus> { OverlayEventBusImpl(get()) }
        viewModel { GlobalOverlayViewModel(get()) }
        single<HttpClient> {
            createPosHttpClient(
                engine = createPlatformEngine(HttpClientConfig.WASM),
                tokenStore = get(),
                onUnauthorized = {
                    val session = get<SessionState>()
                    // A refresh the server rejected: the session ended for the operator, so the login
                    // screen says so. The explicit sign-out button never passes through here.
                    get<SessionEndedNotice>().markEndedFrom(session.status)
                    session.logout()
                },
                // adminApp reads the token store directly, so its DATA is already fresh after a
                // refresh — but the sidebar and the destination gate memoise their answer, and a
                // refresh moves no Compose state on its own. Bumping the epoch is what makes them
                // recompute.
                onTokensRefreshed = { get<SessionState>().onCapabilitiesRefreshed() },
                baseUrlResolver = { "/" },
                logger = KermitKtorLogger(),
                logLevel = LogLevel.INFO,
                refreshTransport = RefreshTransport.COOKIE,
            )
        }
        single<HttpCall> { AdminHttpCall(get()) }

        /**
         * Reports errors shown in the web admin into the server's client-error log.
         *
         * The browser has no paired device token, so these reports authenticate with the bearer
         * token the shared HTTP client already attaches — which is why the endpoint accepts
         * either credential.
         */
        single<ClientErrorReporter> {
            val store = get<KeyValueStore>()
            val session = get<SessionState>()
            val log = Logger.withTag("clientErrors")
            DefaultClientErrorReporter(
                httpCall = get(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                app = "ADMIN",
                appVersion = "admin",
                employeeNumberProvider = { store.getString("employee_number") },
                terminalIdProvider = { null },
                // NEW-23: signed out there is no credential to send, so the server answered 401 to
                // every report (each failed sign-in). Dropped with a log line instead.
                mayReport = {
                    val signedIn = session.status == SessionStatus.AUTHENTICATED
                    if (!signedIn) log.i { "client error not reported: no session (${session.status})" }
                    signedIn
                },
            )
        }
        single<AuthApiClient> { KtorAuthApiClient(get(), get()) }
        // A user-initiated sign-out: ends the server session (expiring the HttpOnly refresh cookie)
        // before the local one. The failed-refresh path above deliberately bypasses it.
        factory { AdminSignOut(get(), get()) }
        // The shared PdfViewer's URL fetch runs outside the Ktor client; this hands it the bearer and
        // renews it through the client's own refresh (see AdminPdfTokenProvider).
        single { AdminPdfTokenProvider(get(), get()) }
        single { TseAdminGate(get()) }
        single<StatisticsApiClient> { KtorStatisticsApiClient(get()) }
        single<MonthlyCloseApiClient> { KtorMonthlyCloseApiClient(get()) }
        single<DatevExportLogApiClient> { KtorDatevExportLogApiClient(get()) }
        single<SalesInvoiceApiClient> { KtorSalesInvoiceApiClient(get()) }
        single<DebtorApiClient> { KtorDebtorApiClient(get()) }
        single<DatevExportApiClient> { KtorDatevExportApiClient(get()) }
        single<CustomExportApiClient> { KtorCustomExportApiClient(get()) }
        single<KassenbuchApiClient> { KtorKassenbuchApiClient(get()) }
        single<CashBookApiClient> { KtorCashBookApiClient(get()) }
        single<DocumentManagementApiClient> { KtorDocumentManagementApiClient(get()) }
        single<SupplierApiClient> { KtorSupplierApiClient(get()) }
        single<BankAccountApiClient> { KtorBankAccountApiClient(get()) }
        single<InvoiceListApiClient> { KtorInvoiceListApiClient(get()) }
        single<AllocationApiClient> { KtorAllocationApiClient(get()) }
        single<BookkeepingStartApiClient> { KtorBookkeepingStartApiClient(get()) }
        single<ContractApiClient> { KtorContractApiClient(get()) }
        single<ZBonApiClient> { KtorZBonApiClient(get()) }
        single<BillApiClient> { KtorBillApiClient(get()) }
        single<TableSessionApiClient> { KtorTableSessionApiClient(get()) }
        single<OrderApiClient> { KtorOrderApiClient(get()) }
        single<OrderItemApiClient> { KtorOrderItemApiClient(get()) }
        single<ProductApiClient> { KtorProductApiClient(get()) }
        single<ProductCategoryApiClient> { KtorProductCategoryApiClient(get()) }
        single<ProductExtrasApiClient> { KtorProductExtrasApiClient(get()) }
        single<PromotionApiClient> { KtorPromotionApiClient(get()) }
        single<EmployeeApiClient> { KtorEmployeeApiClient(get()) }
        single<WorkScheduleApiClient> { KtorWorkScheduleApiClient(get()) }
        single<TimeTrackingApiClient> { KtorTimeTrackingApiClient(get()) }
        single<TerminalApiClient> { KtorTerminalApiClient(get()) }
        single<PermissionGroupApiClient> { KtorPermissionGroupApiClient(get()) }
        single<TseSettingsApiClient> { KtorTseSettingsApiClient(get()) }
        single<TseQueueApiClient> { KtorTseQueueApiClient(get()) }
        single<TseFindingsApiClient> { KtorTseFindingsApiClient(get()) }
        single<DsfinvkApiClient> { KtorDsfinvkApiClient(get()) }
        single<SessionPolicyApiClient> { KtorSessionPolicyApiClient(get()) }
        single<ScheduledJobApiClient> { KtorScheduledJobApiClient(get()) }
        single<UpdateApiClient> { KtorUpdateApiClient(get()) }
        single<IssueReportApiClient> { KtorIssueReportApiClient(get()) }
        single<AuditLogApiClient> { KtorAuditLogApiClient(get()) }
        single<ChangelogApiClient> { KtorChangelogApiClient(get()) }
        single<BackupApiClient> { KtorBackupApiClient(get()) }
        single<PrinterLocationApiClient> { KtorPrinterLocationApiClient(get()) }
        single<PrintJobHistoryApiClient> { KtorPrintJobHistoryApiClient(get()) }
        single<PrinterDiscoveryApiClient> { KtorPrinterDiscoveryApiClient(get()) }
        single<ChoiceGroupApiClient> { KtorChoiceGroupApiClient(get()) }
        single<ProductChoiceGroupApiClient> { KtorProductChoiceGroupApiClient(get()) }
        single<PfandTypeApiClient> { KtorPfandTypeApiClient(get()) }
        single<SetupApiClient> { KtorSetupApiClient(get()) }
        single<RTableApiClient> { KtorRTableApiClient(get()) }
        single<RoomElementApiClient> { KtorRoomElementApiClient(get()) }
        single<LocationTypeApiClient> { KtorLocationTypeApiClient(get()) }
        single<ShopConfigurationApiClient> { KtorShopConfigurationApiClient(get()) }
        single<AccountingConfigurationApiClient> { KtorAccountingConfigurationApiClient(get()) }
        single<FiscalConfigurationApiClient> { KtorFiscalConfigurationApiClient(get()) }
        single<EcrConfigurationApiClient> { KtorEcrConfigurationApiClient(get()) }
        single<BackofficeConfigurationApiClient> { KtorBackofficeConfigurationApiClient(get()) }
        single<WebAccessApiClient> { KtorWebAccessApiClient(get()) }
        single<ShopInfoApiClient> { KtorShopInfoApiClient(get()) }
        single<OpeningHoursApiClient> { KtorOpeningHoursApiClient(get()) }
        single<PaymentTypeApiClient> { KtorPaymentTypeApiClient(get()) }
        single<VatApiClient> { KtorVatApiClient(get()) }
        single<VoucherApiClient> { KtorVoucherApiClient(get()) }
        single<ApprovalRequestApiClient> { KtorApprovalRequestApiClient(get()) }
        // The settings ViewModels only ever *write* to this mirror, to let a till enforce rules while
        // offline. A web admin runs no till and has no local store, so the no-op is the whole contract.
        single<DeviceSettingsMirror> { NoOpDeviceSettingsMirror }
        viewModel { SecuritySessionsViewModel(get()) }
        viewModel { ScheduledJobsViewModel(get()) }
        // api, then the token store: the UPDATE_MANAGE gate is enforced inside the ViewModel, not
        // only by the screen disabling its buttons.
        viewModel { UpdateViewModel(get(), get()) }
        // api, then the token store: ISSUE_REPORT_CREATE / ISSUE_REPORT_MANAGE are enforced inside
        // each ViewModel as well as by the menu entry and the settings section.
        viewModel { IssueReportViewModel(get(), get()) }
        viewModel { IssueReportSettingsViewModel(get(), get()) }
        viewModel { AuditLogViewModel(get()) }
        viewModel { ChangelogViewModel(get()) }
        // Seven clients, in constructor order: backup, tseSettings, shopConfiguration,
        // accountingConfiguration, fiscalConfiguration, scheduledJob, paymentType.
        viewModel {
            val tokenStore = get<TokenStore>()
            DatevSettingsViewModel(get(), get(), get(), get(), get(), get(), get(), canManageBackups = { tokenStore.canBrowseServerDirectories })
        }
        // Five clients in constructor order — printerLocation, productCategory, ecrConfiguration,
        // printJobHistory, printerDiscovery — then the shop id. Literal 1L matches this module's
        // existing single-tenant assumption.
        viewModel { PrinterSettingsViewModel(get(), get(), get(), get(), get(), 1L) }
        viewModel { AdminLoginViewModel(get(), get(), get(), get(), get()) }
        viewModel { PendingAuthorizationsViewModel(get()) }
        viewModel { DayFiguresViewModel(get(), get()) }
        viewModel { DayCloseViewModel(get(), get()) }
        viewModel { OpenWorkViewModel(get(), get()) }
        viewModel { TseHealthViewModel(get(), get()) }
        viewModel { RestoredPathsViewModel(get()) }
        viewModel { RecentCashEntriesViewModel(get(), get()) }
        viewModel { AnalyticsViewModel(get()) }
        // The till's report view model, shared from shared-ui. Koin on wasm indexes definitions by
        // the SIMPLE class name (qualifiedName is unavailable), so no two classes registered here may
        // share one — the later definition silently replaces the earlier. That is why adminApp's own
        // Analysen VM is AnalyticsViewModel and not ReportsViewModel (T9-3); the DailyReportViewModel
        // import alias is only a compile-time rename. AdminModuleDefinitionCollisionTest guards this.
        viewModel { DailyReportViewModel(get(), get(), get(), get(), get()) }
        viewModel { CloseReadinessViewModel(get()) }
        viewModel { DatevOutputViewModel(get(), get(), get()) }
        viewModel { DsfinvkExportViewModel(get()) }
        viewModel { CustomExportViewModel(get()) }
        viewModel { KassenbuchViewModel(get(), get(), get(), get()) }
        // Book management is its own ViewModel: it is the only Kassenbuch surface that needs
        // the shop-configuration client, and it answers a different question from the screen.
        viewModel {
            val tokenStore = get<TokenStore>()
            BookManagementViewModel(get(), get(), canManageTerminals = { tokenStore.holds(Capability.TERMINAL_MANAGE) })
        }
        // The browser `<input type=file>` picker is wired in here rather than imported by the
        // ViewModel, so the upload section itself holds nothing wasm-specific: a desktop or Android
        // host would bind its own picker and reuse the component unchanged.
        viewModel { DocumentUploadViewModel(get(), ::pickFile) }
        viewModel { params -> ZBonHubViewModel(params.get(), get(), get(), get()) }
        viewModel { params -> SessionDetailViewModel(params.get(), get(), get(), get(), get()) }
        viewModel { ZBonListViewModel(get()) }
        viewModel { ZBonClosingViewModel(get(), get(), get()) }
        viewModel { ProductViewModel(get(), get(), get(), get(), get()) }
        viewModel { TableViewModel(get()) }
        viewModel { LocationTypeViewModel(get()) }
        viewModel { FloorPlanEditorViewModel(get(), get()) }
        // shopId defaults to 1L — adminApp is single-tenant, same as composeApp.
        viewModel { RulesViewModel(get(), get(), get()) }
        viewModel { OpeningHoursViewModel(get()) }
        viewModel { PaymentTypeViewModel(get()) }
        viewModel { VatViewModel(get()) }
        viewModel { VatMappingViewModel(get(), get()) }
        viewModel { DocumentStorageViewModel(get(), get()) }
        viewModel { BrowserStationsViewModel(get()) }
        viewModel { SystemSettingsViewModel(get()) }
        viewModel { VoucherConfigViewModel(get()) }
        viewModel { VoucherViewModel(get()) }
        viewModel { ShopInfoViewModel(get()) }
        viewModel { SetupChecklistViewModel(get()) }
        viewModel { ExtrasViewModel(get(), get()) }
        viewModel { ChoiceGroupsViewModel(get(), get()) }
        // Constructor order: promotionApi, productApi.
        viewModel { PromotionsViewModel(get(), get()) }
        viewModel { StaffViewModel(get(), get()) }
        viewModel { EmployeePickerViewModel(EmployeeApiSummaryProvider(get<EmployeeApiClient>())) }
        viewModel { ScheduleViewModel(get()) }
        viewModel { TimeEntriesViewModel(get()) }
        viewModel { ApprovalsViewModel(get()) }
        viewModel { HrProfileViewModel(get()) }
        viewModel { TseSettingsViewModel(get(), get(), get(), get()) }
        viewModel { TerminalsViewModel(get(), get(), get()) }
        viewModel { PermissionGroupsViewModel(get()) }
        viewModel { DocumentsViewModel(get()) }
        viewModel { DocumentDetailViewModel(get()) }
        viewModel { InvoiceCaptureViewModel(get(), get(), get()) }
        viewModel { SuppliersViewModel(get()) }
        viewModel { InvoicesViewModel(get(), get()) }
        viewModel { SalesInvoicesViewModel(get()) }
        viewModel { SalesInvoiceEditorViewModel(get(), get()) }
        viewModel { DebtorsViewModel(get(), get()) }
        viewModel { DebtorDetailViewModel(get()) }
        viewModel { AllocationsViewModel(get(), get(), get(), get()) }
        viewModel { ContractsViewModel(get()) }
        viewModel { ContractDetailViewModel(get(), get()) }
        viewModel { BankAccountsViewModel(get()) }
        viewModel { TseFindingsViewModel(get()) }
        viewModel { SupplierDetailViewModel(get()) }
        viewModel { BootstrapSetupViewModel(get()) }
        // First-terminal init ViewModel (Terminal Phase RB3 Part B): commission TSE → create first
        // terminal → register its TSE client (in-sitting) → issue pairing code + QR. serverUrlProvider
        // uses the default (window.location.origin). Dependencies: TerminalApiClient (RB2), TseSettingsApiClient (RB4).
        viewModel { FirstTerminalSetupViewModel(get(), get()) }
    }
