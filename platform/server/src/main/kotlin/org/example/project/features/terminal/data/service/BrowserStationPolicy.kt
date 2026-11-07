package org.example.project.features.terminal.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException

/** The one rule: a BROWSER terminal may only operate in a role the shop has enabled. */
class BrowserStationPolicy(
    private val configManager: IShopConfigurationManager,
) {
    suspend fun requireAllowed(
        kind: ClientKind,
        role: TerminalRole,
    ) {
        if (kind != ClientKind.BROWSER) return
        if (role in configManager.getBrowserStationRoles()) return
        throw PosException(
            PosErrorCode.BROWSER_STATION_DISABLED,
            HttpStatusCode.Forbidden,
            "Browser stations are not enabled for role ${role.name}",
        )
    }
}
