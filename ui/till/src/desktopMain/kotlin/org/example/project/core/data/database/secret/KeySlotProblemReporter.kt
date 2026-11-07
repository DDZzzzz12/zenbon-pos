package org.example.project.core.data.database.secret

import co.touchlab.kermit.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Logs a local-secrets key-slot problem when it starts and when it ends, instead of on every call. The till reads its
 * secrets on token load and refresh, login, pairing and settings, so a slot that stays unreadable — a Linux kiosk with no
 * keyring daemon — would otherwise put a WARN with a stack trace in the log each time.
 *
 * State is kept per subject (a slot's role, or the keychain probe) and lives here, not in a slot, because
 * [OsDesktopKeySource] builds new slots for every call. One instance belongs to one key source for that source's life.
 * - [failed] logs a WARN, with the cause if there is one, when [subject] was not failing; otherwise a DEBUG line, without
 *   the stack trace. A problem with no exception behind it, such as a malformed stored key, passes no cause.
 * - [recovered] logs an INFO when [subject] was failing; otherwise nothing.
 *
 * Thread-safe: each transition is logged once, however many calls race. Messages name slots, never key material.
 */
class KeySlotProblemReporter(
    private val log: Logger = Logger.withTag("DesktopKeySource"),
) {
    private val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun failed(
        subject: String,
        cause: Throwable?,
        message: () -> String,
    ) {
        if (failing.add(subject)) log.w(cause) { message() } else log.d { message() }
    }

    fun recovered(
        subject: String,
        message: () -> String,
    ) {
        if (failing.remove(subject)) log.i { message() }
    }
}
