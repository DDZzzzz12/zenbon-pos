package org.example.project.features.startpage

import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.infrastructure.plugins.ApiDocsPolicy

/**
 * The web pages this server serves, in display order. Add a new page HERE, next to its `staticResources`
 * mount in Routing.kt — `StartPageRoutesTest` fails if an entry's path is not routed.
 */
object WebEntryRegistry {
    val ADMIN = WebEntry("admin", "/admin/", StartPageStrings.ADMIN, StartPageStrings.ADMIN_HINT, StartPageTarget.ADMIN)
    val POS = WebEntry("pos", "/pos/", StartPageStrings.POS_BOTH, StartPageStrings.POS_HINT, StartPageTarget.POS)
    val MANUAL = WebEntry("manual", "/manual/", StartPageStrings.MANUAL, null, null)
    val API_DOCS = WebEntry("api_docs", "/swagger", StartPageStrings.API_DOCS, null, null)

    val all: List<WebEntry> = listOf(ADMIN, POS, MANUAL, API_DOCS)

    /** The entries shown right now, with the POS label naming the enabled role(s). */
    suspend fun available(configManager: IShopConfigurationManager): List<WebEntry> {
        val roles = configManager.getBrowserStationRoles()
        val docs = ApiDocsPolicy.current(configManager).enabled
        return all.mapNotNull { entry ->
            when (entry) {
                POS -> if (roles.isEmpty()) null else entry.copy(labelKey = posLabel(roles))
                API_DOCS -> entry.takeIf { docs }
                else -> entry
            }
        }
    }

    private fun posLabel(roles: Set<TerminalRole>): String =
        when {
            TerminalRole.CASHIER in roles && TerminalRole.ORDER_STATION in roles -> StartPageStrings.POS_BOTH
            TerminalRole.CASHIER in roles -> StartPageStrings.POS_CASHIER
            else -> StartPageStrings.POS_STATION
        }
}
