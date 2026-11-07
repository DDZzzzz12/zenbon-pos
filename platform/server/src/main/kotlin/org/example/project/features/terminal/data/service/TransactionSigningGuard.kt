package org.example.project.features.terminal.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.domain.SigningDefect
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException

/**
 * Rejects a transaction if the resolved terminal may not TSE-sign under [mode].
 * Call this BEFORE creating any transactional entity (fail-fast). A deactivated terminal never
 * signs, in ANY mode (checked first, unconditionally) — this is the SOLE gate for any HTTP caller
 * that reaches a service sign path without going through the route-level capability ceiling in
 * `RouteAuthorizationPlugin`/`TransactionalRoutes` (e.g. a route the ceiling's route-set doesn't
 * cover). Beyond that: non-fiscal terminals never sign; a registered TSE client is required only
 * in NORMAL mode (SIMULATION mock-signs).
 * This is the service-level defense-in-depth beneath the route-level capability ceiling.
 */
object TransactionSigningGuard {
    fun assertMaySign(
        terminal: Terminal,
        mode: TseMode,
    ) {
        when (SigningDefect.of(terminal, mode)) {
            null -> Unit
            SigningDefect.INACTIVE ->
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.Forbidden,
                    "Terminal ${terminal.clientId} is deactivated",
                )
            SigningDefect.NOT_FISCAL ->
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.Forbidden,
                    "Non-fiscal terminal ${terminal.clientId} cannot sign transactions",
                )
            SigningDefect.NOT_REGISTERED ->
                throw PosException(
                    PosErrorCode.TERMINAL_NOT_TSE_REGISTERED,
                    HttpStatusCode.FailedDependency,
                    "Terminal ${terminal.clientId} has no registered TSE client",
                )
        }
    }
}
