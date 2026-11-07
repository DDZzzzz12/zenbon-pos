package org.example.project.admin.core.presentation.navigation

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** H-6: Back on a deep-linked detail opens its list instead of falling through to Übersicht. */
class DetailBackTargetTest {
    private fun at(route: KClass<*>): (KClass<*>) -> Boolean = { it == route }

    @Test
    fun aDeepLinkedDetailGoesToItsList() {
        val parent = DetailBackTarget.parentToOpenFor(at(DebtorDetailRoute::class), at(DashboardRoute::class), reachedInApp = false)
        assertEquals(AdminNavItem.DEBTORS, parent)
    }

    @Test
    fun aDeepLinkedDetailWithNothingBeneathGoesToItsList() {
        assertEquals(AdminNavItem.CONTRACTS, DetailBackTarget.parentToOpenFor(at(ContractDetailRoute::class), null, reachedInApp = false))
    }

    @Test
    fun aDetailOpenedInsideTheAppPopsToWhereItCameFrom() {
        // A Beleg opened from the cash book returns to the cash book, not to Belege.
        assertNull(DetailBackTarget.parentToOpenFor(at(DocumentDetailRoute::class), at(KassenbuchRoute::class), reachedInApp = true))
    }

    @Test
    fun aDetailAboveItsOwnListPops() {
        assertNull(DetailBackTarget.parentToOpenFor(at(DebtorDetailRoute::class), at(DebtorsRoute::class), reachedInApp = false))
    }

    @Test
    fun aTopLevelScreenPops() {
        assertNull(DetailBackTarget.parentToOpenFor(at(DebtorsRoute::class), at(DashboardRoute::class), reachedInApp = false))
    }
}
