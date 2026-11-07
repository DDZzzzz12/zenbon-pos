package org.example.project.admin.core.presentation.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlin.reflect.KClass

/**
 * Where the in-screen Back of a detail or editor screen goes.
 *
 * A detail screen reached inside the app returns to wherever it was opened from (the list, the
 * dashboard, the cash book that linked a Beleg). One loaded straight from a URL (a deep link such as
 * `…#…DebtorDetailRoute/3`) has nothing meaningful beneath it but the start destination, so Back
 * used to drop the operator on Übersicht; it goes to the screen's parent list instead (H-6).
 */
object DetailBackTarget {
    /** Saved-state key set on every entry reached by in-app navigation (`go`). */
    const val REACHED_IN_APP = "admin.reachedInApp"

    /**
     * The parent nav item to navigate to, or null to pop as usual.
     *
     * @param current the screen being left.
     * @param previous the entry beneath it, null when there is none.
     * @param reachedInApp true when [current] was opened by in-app navigation.
     */
    fun parentToOpen(
        current: NavDestination?,
        previous: NavDestination?,
        reachedInApp: Boolean,
    ): AdminNavItem? =
        parentToOpenFor(
            current = current?.let { destination -> { route: KClass<*> -> destination.hasRoute(route) } },
            previous = previous?.let { destination -> { route: KClass<*> -> destination.hasRoute(route) } },
            reachedInApp = reachedInApp,
        )

    /** [parentToOpen] over route matchers, so the decision is testable without a nav graph. */
    internal fun parentToOpenFor(
        current: ((KClass<*>) -> Boolean)?,
        previous: ((KClass<*>) -> Boolean)?,
        reachedInApp: Boolean,
    ): AdminNavItem? {
        if (reachedInApp) return null
        val parent = current?.let { parentOf(it) } ?: return null
        val previousIsParent = previous?.invoke(parent.route::class) == true
        return if (previousIsParent) null else parent
    }

    /** The parent nav item of a detail route; null for a nav item's own route or an unknown one. */
    internal fun parentOf(matches: (KClass<*>) -> Boolean): AdminNavItem? =
        AdminNavItem.DETAIL_ROUTE_PARENTS.entries
            .firstOrNull { (route, _) -> matches(route) }
            ?.value
}
