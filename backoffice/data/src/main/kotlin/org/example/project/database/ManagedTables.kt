package org.example.project.database

import org.example.project.features.allocation.database.tables.PaymentAllocationTable
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.auth.database.tables.RefreshTokenTable
import org.example.project.features.bank.database.tables.BankAccountTable
import org.example.project.features.bill.database.tables.BillNumberSequenceTable
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.cardTerminal.database.tables.CardSettlementDetailTable
import org.example.project.features.cardTerminal.database.tables.CardSettlementTable
import org.example.project.features.cardTerminal.database.tables.CardTerminalTable
import org.example.project.features.cardTerminal.database.tables.CardTransactionTable
import org.example.project.features.changelog.database.tables.ChangelogReleaseTable
import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.contract.database.tables.ContractTable
import org.example.project.features.customer.database.tables.CustomerAddressTable
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.customexport.database.tables.ExportDefinitionFieldTable
import org.example.project.features.customexport.database.tables.ExportDefinitionTable
import org.example.project.features.datevexportlog.database.tables.DatevExportLogTable
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.example.project.features.document.database.tables.DocumentBankStatementMetadataTable
import org.example.project.features.document.database.tables.DocumentInvoiceMetadataTable
import org.example.project.features.document.database.tables.DocumentLinkTable
import org.example.project.features.document.database.tables.DocumentTable
import org.example.project.features.document.database.tables.InvoiceAllowanceChargeTable
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceLineTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.database.tables.InvoiceVatBreakdownTable
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.database.tables.WorkSchedules
import org.example.project.features.issuereport.database.tables.IssueReportSettingsTable
import org.example.project.features.kassenbuch.database.tables.CashBookCloseTable
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchLineItemTable
import org.example.project.features.openinghours.database.tables.OpeningHoursOverrideTable
import org.example.project.features.openinghours.database.tables.OpeningHoursTable
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.database.tables.ItemTransferTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.database.tables.OrderNumberSequenceTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.database.tables.VatTable
import org.example.project.features.pfand.database.tables.PfandTypeTable
import org.example.project.features.printer.database.tables.PrintJobHistoryTable
import org.example.project.features.printer.database.tables.PrinterLocationTable
import org.example.project.features.printer.database.tables.PrinterSetupTable
import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.example.project.features.product.database.tables.FavouriteProductTable
import org.example.project.features.product.database.tables.ProductCategoryPrinterLocationTable
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductChoiceGroupTable
import org.example.project.features.product.database.tables.ProductExtrasAssignmentTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.reservation.database.tables.ReservationRuleTable
import org.example.project.features.reservation.database.tables.ReservationTable
import org.example.project.features.reservation.database.tables.ReservationTableAssignmentTable
import org.example.project.features.salesinvoice.database.tables.ArNumberSequenceTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceAllowanceChargeTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceBookingTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceLineTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceVatBreakdownTable
import org.example.project.features.scheduler.database.tables.JobExecutions
import org.example.project.features.scheduler.database.tables.ScheduledJobs
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.features.table.database.tables.CombinableTablesTable
import org.example.project.features.table.database.tables.LocationTypeTable
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.database.tables.RoomElementTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.terminal.database.tables.TerminalPairingCodeTable
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.timetracking.database.tables.CostCenterTable
import org.example.project.features.timetracking.database.tables.TimeApprovalTable
import org.example.project.features.timetracking.database.tables.TimeBreakTable
import org.example.project.features.timetracking.database.tables.TimeEntryTable
import org.example.project.features.timetracking.database.tables.WageTypeTable
import org.example.project.features.tse.database.tables.TseExplanationTable
import org.example.project.features.tse.database.tables.TseExportArchiveTable
import org.example.project.features.tse.database.tables.TseLateSigningTable
import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.update.database.tables.UpdateSettingsTable
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.database.tables.EmployeeZBonPaymentBreakdownTable
import org.example.project.features.zBon.database.tables.EmployeeZBonSummaryTable
import org.example.project.features.zBon.database.tables.VatGroupTable
import org.example.project.features.zBon.database.tables.ZBonPaymentSummaryTable
import org.example.project.features.zBon.database.tables.ZBonPromotionSummaryTable
import org.example.project.features.zBon.database.tables.ZBonStammTerminalTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.Table

/**
 * Every database table the host manages, in dependency order.
 *
 * Tables are ordered to ensure that parent tables (referenced by foreign keys)
 * are created before child tables that reference them. This prevents foreign
 * key constraint errors during table creation.
 *
 * The list is data, not DDL: `SchemaManager` creates, verifies and drops in this order (it reads
 * it as `SchemaManager.allTables`), and the custom-export schema graph walks it, because a table's
 * *incoming* foreign keys cannot be found from the table itself, only by scanning every table.
 * That ordering doubles as the graph's deterministic traversal order — see `SchemaGraph`. It sits
 * below the host so the backoffice export can read it without reaching into `pos-server`.
 */
object ManagedTables {
    val all: Array<Table> =
        arrayOf<Table>(
            // Location type system (must be before RTable and RoomElement)
            LocationTypeTable,
            // System preferences (depends on LocationTypeTable)
            RoomElementTable,
            DocumentTable,
            DocumentInvoiceMetadataTable,
            DocumentBankStatementMetadataTable,
            BusinessPartnerTable, // Depends on ShopInfoTable; SupplierTable/DebtorTable will reference it once partner_id lands
            SupplierTable,
            SupplierBankAccountTable, // Depends on SupplierTable
            ContractTable, // Depends on ShopInfoTable, SupplierTable and DocumentTable
            InvoiceTable, // Depends on DocumentTable and ContractTable
            InvoiceLineTable, // Depends on InvoiceTable
            InvoiceVatBreakdownTable, // Depends on InvoiceTable
            InvoiceAllowanceChargeTable, // Depends on InvoiceTable
            InvoiceBookingTable, // Depends on InvoiceTable
            ContractInstalmentTable, // Depends on ContractTable and InvoiceTable
            BankAccountTable, // Depends on ShopInfoTable
            BankStatementTable, // Depends on DocumentTable and BankAccountTable
            BankStatementTransactionTable, // Depends on BankStatementTable
            // Shop information and setup (no dependencies)
            ShopInfoTable,
            ShopConfigurationTable, // Depends on ShopInfoTable
            TerminalTable, // Depends on ShopInfoTable (nullable)
            TerminalPairingCodeTable, // Depends on TerminalTable
            // Employee system
            EmployeeTable,
            EmployeeInfoTable, // Depends on EmployeeTable
            WorkSchedules, // Depends on EmployeeTable
            RefreshTokenTable, // Depends on EmployeeTable
            // Permission groups — the capability authority as of Stage 2a
            PermissionGroupTable,
            PermissionGroupCapabilityTable, // Depends on PermissionGroupTable
            EmployeePermissionGroupTable, // Depends on EmployeeTable and PermissionGroupTable
            // Saved custom exports — parent before child, the child references the parent
            ExportDefinitionTable, // Depends on ShopInfoTable
            ExportDefinitionFieldTable, // Depends on ExportDefinitionTable
            // Restaurant table system
            TableSessionTable, // Create before RTableTable since RTableTable references it
            RTableTable,
            // Customer tables
            CustomerTable,
            CustomerAddressTable,
            DebtorTable, // Depends on ShopInfoTable and CustomerTable
            // Reservation system (depends on Customer, Employee)
            ReservationTable,
            ReservationRuleTable,
            ReservationTableAssignmentTable, // Depends on ReservationTable, RTableTable, EmployeeTable
            // Opening hours system (independent)
            OpeningHoursTable,
            OpeningHoursOverrideTable,
            // Combinable tables (depends on RTableTable)
            CombinableTablesTable,
            // Product system (independent)
            ProductCategoryTable,
            ProductExtrasTable,
            ProductExtrasAssignmentTable, // Junction table
            ChoiceGroupTable, // Reusable selection group (no FK to Product)
            ChoiceGroupOptionTable, // Join: option in group (depends on ChoiceGroupTable + ProductExtrasTable)
            PfandTypeTable, // Pfand catalog (ProductTable optionally FKs it)
            ProductTable, // Depends on ProductCategoryTable + PfandTypeTable
            FavouriteProductTable,
            ProductChoiceGroupTable, // Join: group attached to product (depends on ProductTable + ChoiceGroupTable)
            // VAT system (independent)
            VatTable,
            // Order system (depends on Employee, RTable, Product, VAT)
            OrdersTable,
            OrderNumberSequenceTable, // No dependencies; the order-number counter
            OrderItemTable,
            CancellationTable, // Depends on OrderItem, Employee, TseTransaction
            ItemTransferTable,
            ItemExtrasTable,
            // Voucher system (depends on Orders)
            VoucherTable, // Depends on OrdersTable
            VoucherRedeemTable, // Depends on VoucherTable
            // Bill and Payment system (depends on Employee, VAT)
            PaymentTypeTable,
            BillTable,
            BillNumberSequenceTable, // No dependencies; the Kassenbeleg counter
            BillVatDetailTable,
            PaymentTable,
            // Outgoing invoices (depend on ShopInfo, Debtor, Bill, Employee, Document, OrderItem)
            SalesInvoiceTable,
            SalesInvoiceLineTable, // Depends on SalesInvoiceTable and OrderItemTable
            SalesInvoiceVatBreakdownTable, // Depends on SalesInvoiceTable
            SalesInvoiceBookingTable, // Depends on SalesInvoiceTable
            ArNumberSequenceTable, // No dependencies
            // Promotion system (depends on Bill, OrderItem, Employee)
            PromotionTable,
            BillPromotionTable, // Depends on BillTable, PromotionTable, OrderItemTable, EmployeeTable
            SalesInvoiceAllowanceChargeTable, // Depends on SalesInvoiceTable, BillPromotionTable
            // Setup system (independent)
            PrinterSetupTable,
            PrinterLocationTable,
            ProductCategoryPrinterLocationTable, // Junction table depends on ProductCategory and PrinterLocation
            PrintJobHistoryTable, // Depends on PrinterLocationTable
            EntryLogTable,
            TransactionAuditLogTable, // Audit trail for financial transactions
            // Report system (depends on Employee, Shop, TSE)
            TseTransactionTable,
            TseSigningQueueTable, // Depends on TseTransactionTable
            TseLateSigningTable, // Depends on TseTransactionTable
            TseExplanationTable, // Depends on TseTransactionTable (design 2026-09-22 D7)
            TseExportArchiveTable, // No dependencies; the TSE archive register (real-TSE QA rt14)
            ZBonTable,
            ZBonStammTerminalTable, // Per-closing Stamm_Terminals snapshot - depends on ZBonTable
            VatGroupTable,
            ZBonPaymentSummaryTable,
            EmployeeZBonSummaryTable,
            EmployeeZBonPaymentBreakdownTable,
            ZBonPromotionSummaryTable, // Per-promotion Z-Bon breakdown - depends on ZBonTable
            // Kassenbuch system (German cash book for DATEV compliance)
            CashBookTable, // Cash-book dimension - Depends on ShopInfoTable
            KassenbuchHeaderTable, // Header table - Depends on Employee, Shop, TSE, Z-Bon
            CashBookCloseTable, // Kassensturz - Depends on CashBookTable, KassenbuchHeaderTable, Employee
            KassenbuchLineItemTable, // Line items - Depends on KassenbuchHeaderTable
            EmployeeTipPayoutTable, // Tip payouts - Depends on ZBon, Employee, KassenbuchHeader
            DocumentLinkTable, // References KassenbuchHeaderTable
            // Payment assignment - depends on BankStatementTransaction, KassenbuchHeader, Invoice, BankAccount
            PaymentAllocationTable,
            // Time tracking system
            CostCenterTable,
            WageTypeTable,
            TimeEntryTable, // Depends on Employee, CostCenter
            TimeBreakTable, // Depends on TimeEntry
            TimeApprovalTable, // Depends on Employee
            // Scheduler system (cron-style jobs)
            ScheduledJobs,
            JobExecutions, // Depends on ScheduledJobs
            // Update check settings (single row, no foreign keys)
            UpdateSettingsTable,
            // Problem-report settings (single row, no foreign keys)
            IssueReportSettingsTable,
            // Card terminal system
            CardTerminalTable, // Depends on ShopInfoTable
            CardTransactionTable, // Depends on PaymentTable, CardTerminalTable
            CardSettlementTable, // Depends on CardTerminalTable, ZBonTable
            CardSettlementDetailTable, // Depends on CardSettlementTable
            // DATEV export log (audit trail for DATEV exports — no foreign keys)
            DatevExportLogTable,
            // Changelog release notes (written by Flyway release-marker migrations, read by the API)
            ChangelogReleaseTable,
        )
}
