package org.example.project.admin.core.di

import org.example.project.admin.features.allocations.presentation.AllocationsViewModel
import org.example.project.admin.features.auth.presentation.AdminLoginViewModel
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
import org.example.project.admin.features.tse.presentation.TseFindingsViewModel
import org.example.project.admin.features.update.presentation.UpdateViewModel
import org.example.project.admin.features.voucher.presentation.VoucherViewModel
import org.example.project.admin.features.zBon.presentation.SessionDetailViewModel
import org.example.project.admin.features.zBon.presentation.ZBonHubViewModel
import org.example.project.admin.features.zBon.presentation.ZBonListViewModel
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.features.auditlog.presentation.viewmodel.AuditLogViewModel
import org.example.project.features.changelog.presentation.viewmodel.ChangelogViewModel
import org.example.project.features.employee.presentation.EmployeePickerViewModel
import org.example.project.features.scheduler.presentation.viewmodels.ScheduledJobsViewModel
import org.example.project.features.settings.presentation.viewmodel.DatevSettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.LocationTypeViewModel
import org.example.project.features.settings.presentation.viewmodel.OpeningHoursViewModel
import org.example.project.features.settings.presentation.viewmodel.PrinterSettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.RulesViewModel
import org.example.project.features.settings.presentation.viewmodel.SecuritySessionsViewModel
import org.example.project.features.settings.presentation.viewmodel.TseSettingsViewModel
import org.koin.core.annotation.KoinInternalApi
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel as DailyReportViewModel

/**
 * Guards T9-3: opening Analysen crashed the back office with a ClassCastException.
 *
 * Koin on wasm indexes a definition by the class's SIMPLE name (`KClass.qualifiedName` is not
 * available there — see `KoinPlatformTools.getClassName`), and a module silently replaces an earlier
 * definition that has the same index key. adminApp registered its own `ReportsViewModel` and
 * shared-ui's `ReportsViewModel` (aliased on import, which renames nothing at runtime), so the second
 * definition replaced the first and the Analysen screen was handed the till's view model.
 *
 * Every view model class [adminModule] declares must therefore still be a definition's primary type
 * once the module is built. A class that collides with another registered class by simple name drops
 * out of the module and fails this test. When a view model is added to the module, add it here too.
 */
@OptIn(KoinInternalApi::class)
class AdminModuleDefinitionCollisionTest {
    private val registeredViewModels =
        listOf(
            GlobalOverlayViewModel::class,
            SecuritySessionsViewModel::class,
            ScheduledJobsViewModel::class,
            UpdateViewModel::class,
            IssueReportViewModel::class,
            IssueReportSettingsViewModel::class,
            AuditLogViewModel::class,
            ChangelogViewModel::class,
            DatevSettingsViewModel::class,
            PrinterSettingsViewModel::class,
            AdminLoginViewModel::class,
            PendingAuthorizationsViewModel::class,
            DayFiguresViewModel::class,
            DayCloseViewModel::class,
            OpenWorkViewModel::class,
            TseHealthViewModel::class,
            RecentCashEntriesViewModel::class,
            AnalyticsViewModel::class,
            DailyReportViewModel::class,
            CloseReadinessViewModel::class,
            DatevOutputViewModel::class,
            DsfinvkExportViewModel::class,
            CustomExportViewModel::class,
            KassenbuchViewModel::class,
            BookManagementViewModel::class,
            DocumentUploadViewModel::class,
            ZBonHubViewModel::class,
            SessionDetailViewModel::class,
            ZBonListViewModel::class,
            ProductViewModel::class,
            TableViewModel::class,
            LocationTypeViewModel::class,
            FloorPlanEditorViewModel::class,
            RulesViewModel::class,
            OpeningHoursViewModel::class,
            PaymentTypeViewModel::class,
            VatViewModel::class,
            VatMappingViewModel::class,
            DocumentStorageViewModel::class,
            BrowserStationsViewModel::class,
            VoucherConfigViewModel::class,
            VoucherViewModel::class,
            ShopInfoViewModel::class,
            SetupChecklistViewModel::class,
            ExtrasViewModel::class,
            ChoiceGroupsViewModel::class,
            PromotionsViewModel::class,
            StaffViewModel::class,
            EmployeePickerViewModel::class,
            ScheduleViewModel::class,
            TimeEntriesViewModel::class,
            ApprovalsViewModel::class,
            HrProfileViewModel::class,
            TseSettingsViewModel::class,
            TerminalsViewModel::class,
            PermissionGroupsViewModel::class,
            DocumentsViewModel::class,
            DocumentDetailViewModel::class,
            InvoiceCaptureViewModel::class,
            SuppliersViewModel::class,
            InvoicesViewModel::class,
            SalesInvoicesViewModel::class,
            SalesInvoiceEditorViewModel::class,
            DebtorsViewModel::class,
            DebtorDetailViewModel::class,
            AllocationsViewModel::class,
            ContractsViewModel::class,
            ContractDetailViewModel::class,
            BankAccountsViewModel::class,
            TseFindingsViewModel::class,
            SupplierDetailViewModel::class,
            BootstrapSetupViewModel::class,
            FirstTerminalSetupViewModel::class,
        )

    private val primaryTypes = adminModule.mappings.values.map { it.beanDefinition.primaryType }

    @Test
    fun every_registered_view_model_survives_as_its_own_definition() {
        val missing = registeredViewModels.filterNot { it in primaryTypes }.map { it.simpleName }
        assertTrue(missing.isEmpty(), "replaced by a same-simple-name definition: $missing")
    }

    @Test
    fun the_analysen_view_model_and_the_till_report_view_model_are_both_registered() {
        assertTrue(AnalyticsViewModel::class in primaryTypes, "AnalyticsViewModel was replaced")
        assertTrue(DailyReportViewModel::class in primaryTypes, "DailyReportViewModel was replaced")
    }
}
