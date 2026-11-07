package org.example.project.features.launch.presentation

/**
 * Why a device threw its pairing away.
 *
 * A cold reset costs a technician with a pairing code from the back office, so it may only follow
 * something that actually says the pairing is dead: the server saying so, or the server refusing
 * the device token. It may NOT follow an error the device inferred locally - on 2026-09-19 a
 * refused socket did exactly that and emptied two tills while the server still had both of them
 * registered and valid. Requiring a reason at every call site is what keeps that from coming back:
 * there is no value here for "something went wrong".
 */
enum class TerminalColdResetReason {
    /** The server pushed a terminal-session-terminated message with `requiresRepairing = true`. */
    SERVER_REVOKED,

    /** The server refused this device token: 409 revoked or 403 deactivated on the station exchange. */
    DEVICE_TOKEN_REJECTED,

    /** The operator asked for it ("Gerät reparieren" in the nav footer). */
    OPERATOR_REPAIR,
    ;

    /** Persisted after an unpair so the next pairing can say what happened; see LAST_COLD_RESET_REASON. */
    val diagnostic: String get() = name
}
