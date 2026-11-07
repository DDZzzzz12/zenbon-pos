package org.example.project.core.utils

/** Characters a TSE 2.0 refuses inside a client ID (the ERS serial number). */
private val TSE_FORBIDDEN_CLIENT_ID_CHARS = charArrayOf('/', ':', '?')

/**
 * Whether [clientId] can be registered on a TSE 2.0.
 *
 * TSE 2.0 rejects `/`, `:` and `?`; TSE 1.x accepted them. Our IDs are generated
 * server-side as `TER-001` and are therefore safe, so this is not input validation —
 * there is no user-entered client ID to validate. It exists so that a future change to
 * the ID scheme fails loudly here rather than at `tse_registerClient` on a customer's
 * device, where the terminal would silently never register.
 */
fun isTseSafeClientId(clientId: String): Boolean = clientId.isNotBlank() && clientId.none { it in TSE_FORBIDDEN_CLIENT_ID_CHARS }
