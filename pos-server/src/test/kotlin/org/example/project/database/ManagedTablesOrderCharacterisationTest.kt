package org.example.project.database

import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.document.database.tables.DocumentTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the ordered table list the host manages and the two cross-area foreign keys it declares, so
 * that moving both declarations below the host (stage B2-5, task A4) cannot change a fresh install's
 * DDL order or the custom-export schema graph's traversal order.
 *
 * The order IS the contract: `SchemaManager.createTables` creates in it, the drops run in its
 * reverse, and `SchemaGraph` walks it to find incoming foreign keys deterministically.
 */
@DisplayName("Managed table order and cross-area foreign key referees are unchanged")
class ManagedTablesOrderCharacterisationTest {
    @Test
    fun `the managed tables keep their names and dependency order`() {
        assertEquals(EXPECTED_TABLES, SchemaManager.allTables.map { it.tableName })
    }

    @Test
    fun `bill clears_sales_invoice_id refers to sales_invoice id`() {
        assertSame(SalesInvoiceTable.id, CrossAreaForeignKeys.refereeOf(BillTable.clearsSalesInvoiceId))
    }

    @Test
    fun `kassenbuch_header linked_document_id refers to documents id`() {
        assertSame(DocumentTable.id, CrossAreaForeignKeys.refereeOf(KassenbuchHeaderTable.linkedDocumentId))
    }

    @Test
    fun `an ordinary column has no cross-area referee`() {
        assertNull(CrossAreaForeignKeys.refereeOf(KassenbuchHeaderTable.belegNr))
    }

    private companion object {
        val EXPECTED_TABLES =
            listOf(
                "location_types",
                "room_element",
                "documents",
                "document_invoice_metadata",
                "document_bank_statement_metadata",
                "business_partner",
                "supplier",
                "supplier_bank_account",
                "contract",
                "invoice",
                "invoice_line",
                "invoice_vat_breakdown",
                "invoice_allowance_charge",
                "invoice_booking",
                "contract_instalment",
                "bank_account",
                "bank_statement",
                "bank_statement_transaction",
                "shop_info",
                "shop_configuration",
                "terminals",
                "terminal_pairing_codes",
                "employees",
                "employee_info",
                "work_schedules",
                "refresh_tokens",
                "permission_group",
                "permission_group_capability",
                "employee_permission_group",
                "export_definition",
                "export_definition_field",
                "table_session",
                "r_table",
                "customers",
                "customer_addresses",
                "debtor",
                "reservations",
                "reservation_rules",
                "reservation_table_assignments",
                "opening_hours",
                "opening_hours_override",
                "combinable_tables",
                "product_category",
                "product_extras",
                "product_extras_assignment",
                "choice_group",
                "choice_group_option",
                "pfand_type",
                "product",
                "favourite_product",
                "product_choice_group",
                "vat",
                "t_order",
                "order_number_sequence",
                "t_order_item",
                "cancellation",
                "item_transfer",
                "item_extras",
                "voucher",
                "voucher_redeem",
                "payment_type",
                "bill",
                "bill_number_sequence",
                "bill_vat_detail",
                "payment",
                "sales_invoice",
                "sales_invoice_line",
                "sales_invoice_vat_breakdown",
                "sales_invoice_booking",
                "ar_number_sequence",
                "promotion",
                "bill_promotion",
                "sales_invoice_allowance_charge",
                "printer_setup",
                "printer_location",
                "product_category_printer_location",
                "print_job_history",
                "entry_log",
                "transaction_audit_log",
                "tse_transaction",
                "tse_signing_queue",
                "tse_late_signing",
                "tse_explanation",
                "tse_export_archive",
                "zbon",
                "zbon_stamm_terminals",
                "tax_group",
                "zbon_payment_summary",
                "employee_zbon_summary",
                "employee_zbon_payment_breakdown",
                "zbon_promotion_summary",
                "cash_book",
                "kassenbuch_header",
                "cash_book_close",
                "kassenbuch_line_item",
                "employee_tip_payout",
                "document_links",
                "payment_allocation",
                "cost_center",
                "wage_type",
                "time_entry",
                "time_break",
                "time_approval",
                "scheduled_jobs",
                "job_executions",
                "update_settings",
                "issue_report_settings",
                "card_terminal",
                "card_transaction",
                "card_settlement",
                "card_settlement_detail",
                "datev_export_log",
                "changelog_release",
            )
    }
}
