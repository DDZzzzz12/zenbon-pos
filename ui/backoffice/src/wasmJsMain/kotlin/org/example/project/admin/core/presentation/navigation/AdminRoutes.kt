package org.example.project.admin.core.presentation.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the admin panel. A route that carries data is a
 * `@Serializable data class` whose properties ARE the navigation arguments; navigate with
 * an instance and read it back via `backStackEntry.toRoute<Route>()`.
 */
@Serializable
object DashboardRoute

@Serializable
object ProductsRoute

@Serializable
object TablesRoute

/** Floor-plan editor for one location (adminApp sub-project D). Pushed from the Tische destination. */
@Serializable
object FloorPlanEditorRoute

@Serializable
object StaffRoute

@Serializable
object TseAdminRoute

/**
 * TSE-Befunde — the TANR findings computed against our own POS data and the explanation register.
 *
 * Separate from [TseAdminRoute]: that screen administers the device and verifies an export file;
 * this one reconciles the device's numbering against the till's records, which is a different
 * question with a different answer.
 */
@Serializable
object TseFindingsRoute

@Serializable
object TerminalsRoute

/**
 * Berechtigungen — the permission-group editor (Stage 6).
 *
 * Gated on PERMISSION_ADMIN, which is reserved and therefore held only by the admin group.
 */
@Serializable
object PermissionGroupsRoute

/** Geplante Aufgaben — the scheduled-jobs monitoring destination (read + run-now). */
@Serializable
object ScheduledJobsRoute

/** Software-Update — the running version, what the last check found, and the three install steps. */
@Serializable
object UpdateRoute

/** Analytics — daily takings, a like-for-like comparison, and the trade pattern by hour and weekday. */
@Serializable
object AnalyticsRoute

/** The till's daily report: the takings behind the figures, bill by bill, table by table. */
@Serializable
object DailyReportRoute

/** Z-Bon list — all Z-Bons with their bills and sessions. */
@Serializable
object ZBonListRoute

/** Audit-Log — the read-only transaction-audit and entry-log monitoring destination. */
@Serializable
object AuditLogRoute

/**
 * Einstellungen destination. [section] is the `SettingsSection` name, carried in the route rather
 * than hoisted above the `NavHost`, so the displayed section is part of navigation state that both
 * the sidebar and the back stack can read. Resolve it with `SettingsRoute.toSection()`.
 *
 * Carrying the section in the route is only half of what makes it addressable. The other half is
 * the controller being bound to browser history — `AuthenticatedApp` calls
 * `bindToBrowserNavigation` (`@ExperimentalBrowserHistoryApi`), so destinations, this one included,
 * carry a URL.
 *
 * It is a `String` rather than the enum so the serialized back-stack value stays readable and
 * survives an enum entry being renamed — `toSection()` falls back rather than throwing.
 */
@Serializable
data class SettingsRoute(
    val section: String = DEFAULT_SECTION,
) {
    companion object {
        /**
         * The section shown when none is specified. A bare string, not `SettingsSection.BUSINESS_RULES.name`,
         * because `core.presentation.navigation` must not depend on a feature package —
         * `SettingsRoute.toSection()` owns that coupling.
         */
        const val DEFAULT_SECTION = "BUSINESS_RULES"
    }
}

/**
 * Kassenbuch — the cash-book screen: tabs per book, a period-stepped table, and the selected
 * booking's detail panel.
 *
 * [headerId] preselects one entry and is what remains of the deleted standalone
 * `KassenbuchEntryRoute`: the dashboard's "latest entries" card still deep-links to a single
 * booking, but it now arrives on the full screen with that row already open instead of a detail
 * page of its own. `null` — the normal entry from the sidebar — opens the current period with
 * nothing selected.
 */
@Serializable
data class KassenbuchRoute(
    val headerId: Long? = null,
    /** Open the cash-book management dialog on arrival (where the signing till is designated). */
    val manageBooks: Boolean = false,
)

@Serializable
data class ZBonDetailRoute(
    val zbonId: Long,
)

/**
 * New-server first-terminal init destination (Terminal Phase RB3 Part B). Pushed on top of
 * DashboardRoute after the manager's first login on a freshly initialized server with no terminal
 * yet. Skippable — dismiss pops back to the dashboard.
 */
@Serializable
object FirstTerminalSetupRoute

/** Gutscheine — voucher management. Promoted out of Einstellungen: vouchers are stock, not config. */
@Serializable
object VouchersRoute

/** Drucker — printer configuration. Promoted out of Einstellungen: a printer is a device. */
@Serializable
object PrinterRoute

/** Kassenmeldung — the §146a AO reporting obligation. An action with a deadline, not a setting. */
@Serializable
object KassenmeldungRoute

/** Datenexport — the DSFinV-K export run for a Betriebsprüfung. */
@Serializable
object DataExportRoute

/** Belege — the document archive (DMS) search destination. */
@Serializable
object DocumentsRoute

/** Belege detail — a single document's metadata/PDF view. Reached only from a search result row. */
@Serializable
data class DocumentDetailRoute(
    val documentId: Long,
)

/** Lieferanten — the creditor master-data list. */
@Serializable
object SuppliersRoute

/** Bankkonten — our own accounts, their statements and the gaps between them. */
@Serializable
object BankAccountsRoute

/** A single creditor. Reached from a list row. */
@Serializable
data class SupplierDetailRoute(
    val supplierId: Long,
)

/**
 * Rechnungserfassung — the invoice capture form for one document. Reached from the document detail
 * screen, never from the sidebar.
 *
 * A dedicated route rather than a panel inside the detail screen: the form needs roughly 520 dp beside
 * the PDF (the detail screen's metadata column is 360 dp, sized for ten flat fields), it must not be a
 * `PosDialog` (which self-scrolls and crashes the wasm measure pass when a scrolling form is nested in
 * it), and a drawer would cover the PDF the operator is reading while typing.
 */
@Serializable
data class InvoiceCaptureRoute(
    val documentId: Long,
)

/**
 * Editing an invoice that has no document — a bare XRechnung, or one entered by hand. Reached from
 * the Rechnungen list, never from the sidebar.
 *
 * A route of its own rather than a nullable `documentId` on [InvoiceCaptureRoute]: one route that
 * can be constructed in three states, one of them meaningless, would push a `when` into every call
 * site. Both render the same form.
 */
@Serializable
data class InvoiceEditRoute(
    val invoiceId: Long,
)

/**
 * Rechnungen — the invoice capture pipeline.
 *
 * @param bucket which `InvoiceListDto.Bucket` to open, by name, or `""` for "none named" — the
 *   sidebar's case, which keeps the screen's redirect-away-from-an-empty-opening-bucket behaviour.
 *   A dashboard tile names one, which suppresses that redirect so an explicitly clicked bucket is
 *   never bounced out of for being empty. Empty string rather than null: `navigate()` with a null
 *   route argument silently does nothing.
 */
@Serializable
data class InvoicesRoute(
    val bucket: String = "",
)

/** Ausgangsrechnungen — the invoices we issue (AR slice 4a-6). */
@Serializable
object SalesInvoicesRoute

/**
 * The standalone-invoice editor — create mode.
 *
 * A route of its own rather than a nullable id, following the [DebtorCreateRoute] precedent:
 * `navigate(SalesInvoiceEditRoute(null))` silently does nothing, because navigation-compose has no
 * representation for a null `Long` argument.
 */
@Serializable
object SalesInvoiceEditorRoute

/** The standalone-invoice editor — edit mode. */
@Serializable
data class SalesInvoiceEditRoute(
    val invoiceId: Long,
)

/** Debitoren — the customers we invoice (AR slice 4a-8). */
@Serializable
object DebtorsRoute

/** One debtor, reached from a list row. */
@Serializable
data class DebtorDetailRoute(
    val debtorId: Long,
)

/**
 * The create form — the same screen with no id yet, saved with a POST.
 *
 * A route of its own rather than a nullable `debtorId` on [DebtorDetailRoute], following the
 * [InvoiceCaptureRoute] / [InvoiceEditRoute] precedent above. Not merely a style choice here:
 * `navigate(DebtorDetailRoute(null))` silently does nothing, because navigation-compose has no
 * representation for a null `Long` argument — the click lands, the graph finds no match, and the
 * screen never opens. Verified in a browser 2026-08-16.
 */
@Serializable
object DebtorCreateRoute

/** Zuordnung — assigning payments to what they paid for. */
@Serializable
object AllocationsRoute

/** Verträge — recurring obligations. */
@Serializable
object ContractsRoute

/**
 * One contract. A null [contractId] is the create form — the same fields, with no instalments yet,
 * since a contract has none until the nightly job has run against it.
 */
@Serializable
data class ContractDetailRoute(
    val contractId: Long? = null,
)

/** Eigene Exporte — operator-defined CSV extracts. */
@Serializable
object CustomExportRoute

/**
 * Dev-only entry for the document scanner. Not a nav item and not in `DETAIL_ROUTE_GROUPS`; reached by
 * opening the panel with `?scanner=dev`. Removed (or replaced by a real caller) when the scanner is wired
 * into document upload.
 */
@Serializable
object ScannerDevRoute
