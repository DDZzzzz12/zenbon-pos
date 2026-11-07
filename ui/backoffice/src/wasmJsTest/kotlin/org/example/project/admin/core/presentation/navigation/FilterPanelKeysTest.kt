package org.example.project.admin.core.presentation.navigation

import org.example.project.ui.components.filter.PosFilterPanelStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FilterPanelKeysTest {
    private val pkg = "org.example.project.admin.core.presentation.navigation."

    @Test
    fun keysAreTheirRoutesSimpleNames() {
        assertEquals(ProductsRoute::class.simpleName, FilterPanelKeys.PRODUCTS)
        assertEquals(TablesRoute::class.simpleName, FilterPanelKeys.TABLES)
        assertEquals(KassenbuchRoute::class.simpleName, FilterPanelKeys.KASSENBUCH)
        assertEquals(VouchersRoute::class.simpleName, FilterPanelKeys.VOUCHERS)
        assertEquals(TerminalsRoute::class.simpleName, FilterPanelKeys.TERMINALS)
        assertEquals(AnalyticsRoute::class.simpleName, FilterPanelKeys.ANALYTICS)
        assertEquals(DailyReportRoute::class.simpleName, FilterPanelKeys.DAILY_REPORT)
        assertEquals(DocumentsRoute::class.simpleName, FilterPanelKeys.DOCUMENTS)
        assertEquals(ZBonListRoute::class.simpleName, FilterPanelKeys.ZBON_LIST)
        assertEquals(SuppliersRoute::class.simpleName, FilterPanelKeys.SUPPLIERS)
        assertEquals(DebtorsRoute::class.simpleName, FilterPanelKeys.DEBTORS)
        assertEquals(ContractsRoute::class.simpleName, FilterPanelKeys.CONTRACTS)
        assertEquals(InvoicesRoute::class.simpleName, FilterPanelKeys.INVOICES)
        assertEquals(SalesInvoicesRoute::class.simpleName, FilterPanelKeys.SALES_INVOICES)
    }

    @Test
    fun routePatternsMapToTheirKey() {
        assertEquals("ProductsRoute", FilterPanelKeys.forRoute(pkg + "ProductsRoute"))
        assertEquals("DocumentDetailRoute", FilterPanelKeys.forRoute(pkg + "DocumentDetailRoute/{documentId}"))
        assertNull(FilterPanelKeys.forRoute(null))
    }

    @Test
    fun aRouteChangeClearsOtherScreensButAResizeKeepsTheDraft() {
        val store = PosFilterPanelStore()
        val products = store.getOrCreate(FilterPanelKeys.PRODUCTS, "a")
        products.open()
        products.draft.value = "typed"

        // A resize rebuilds the shell but the route is unchanged.
        FilterPanelKeys.onRouteChanged(store, pkg + "ProductsRoute")
        assertSame(products, store.getOrCreate(FilterPanelKeys.PRODUCTS, "a"))
        assertTrue(products.isOpen)

        // Navigating to another screen drops it, so coming back starts closed.
        FilterPanelKeys.onRouteChanged(store, pkg + "TablesRoute")
        assertFalse(store.contains(FilterPanelKeys.PRODUCTS))
        FilterPanelKeys.onRouteChanged(store, pkg + "ProductsRoute")
        assertFalse(store.getOrCreate(FilterPanelKeys.PRODUCTS, "a").isOpen)
    }

    @Test
    fun theScreenJustEnteredKeepsTheStateItCreated() {
        val store = PosFilterPanelStore()
        store.getOrCreate(FilterPanelKeys.PRODUCTS, "a")
        val tables = store.getOrCreate(FilterPanelKeys.TABLES, "b")
        FilterPanelKeys.onRouteChanged(store, pkg + "TablesRoute")
        assertSame(tables, store.getOrCreate(FilterPanelKeys.TABLES, "b"))
        assertEquals(1, store.size)
    }

    @Test
    fun aFreshStoreIsEmptyAfterSignOut() {
        // The store is remembered inside the authenticated app, so a sign-out disposes it; clear() is the explicit form.
        val store = PosFilterPanelStore()
        store.getOrCreate(FilterPanelKeys.PRODUCTS, "a")
        store.clear()
        assertEquals(0, store.size)
    }
}
