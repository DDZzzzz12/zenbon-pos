package org.example.project.admin.features.settings.presentation.sections

import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/** The roles a browser can run. Mirrors the server's allow-list; anything else is refused with a 400. */
val BROWSER_CAPABLE_ROLES: List<TerminalRole> = listOf(TerminalRole.CASHIER, TerminalRole.ORDER_STATION)

/**
 * State for the Browser-Stationen section.
 *
 * [enabledRoles] is the working draft the switches edit; [savedRoles] is what the server holds. They
 * differ only between a toggle and a successful save, and a failed save leaves [savedRoles] alone.
 * Role names are kept as strings, the wire form, so a role a newer server returns survives a save of
 * an unrelated switch.
 */
data class BrowserStationsState(
    val enabledRoles: Set<String> = emptySet(),
    val savedRoles: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
    val successMessage: StringKey? = null,
) {
    val isDirty: Boolean get() = enabledRoles != savedRoles

    val canSave: Boolean get() = isDirty && !isSaving && !isLoading
}

/**
 * True when the back office was loaded over plain `http:` from a host other than loopback — the case
 * where a browser station would send PINs and sales unencrypted across the network. Pure so it can be
 * tested without a browser.
 */
fun isUnsecuredRemoteConnection(
    protocol: String,
    hostname: String,
): Boolean {
    if (protocol != "http:") return false
    val host = hostname.lowercase().removeSurrounding("[", "]")
    val loopback = host == "localhost" || host == "::1" || host.startsWith("127.") || host.endsWith(".localhost")
    return !loopback
}
