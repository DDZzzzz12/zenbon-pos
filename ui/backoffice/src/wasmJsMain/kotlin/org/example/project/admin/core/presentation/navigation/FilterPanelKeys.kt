package org.example.project.admin.core.presentation.navigation

import org.example.project.ui.components.filter.PosFilterPanelStore

/**
 * The key each adminApp screen files its filter panel under in the [PosFilterPanelStore] (one string per
 * screen, equal to the simple name of the screen's route so [forRoute] can tell which screen is showing).
 * A key must never be shared by two screens: the store casts the draft type per key.
 */
object FilterPanelKeys {
    const val VOUCHERS = "VouchersRoute"
    const val DAILY_REPORT = "DailyReportRoute"
    const val DOCUMENTS = "DocumentsRoute"
    const val PRODUCTS = "ProductsRoute"
    const val TABLES = "TablesRoute"
    const val KASSENBUCH = "KassenbuchRoute"
    const val TERMINALS = "TerminalsRoute"
    const val ANALYTICS = "AnalyticsRoute"
    const val ZBON_LIST = "ZBonListRoute"
    const val SUPPLIERS = "SuppliersRoute"
    const val DEBTORS = "DebtorsRoute"
    const val CONTRACTS = "ContractsRoute"
    const val INVOICES = "InvoicesRoute"
    const val SALES_INVOICES = "SalesInvoicesRoute"

    /** The key of the screen a NavDestination route pattern (`pkg.FooRoute`, `pkg.FooRoute/{id}` or `pkg.FooRoute?x={x}`) belongs to. */
    fun forRoute(destinationRoute: String?): String? = destinationRoute?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.')

    /**
     * A real navigation: drop every stored panel except the screen now showing. A window resize keeps
     * the same route, so it keeps the draft; leaving a screen and coming back starts closed.
     */
    fun onRouteChanged(
        store: PosFilterPanelStore,
        destinationRoute: String?,
    ) = store.clearExcept(forRoute(destinationRoute))
}
