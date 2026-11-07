package org.example.project.features.tse.data.provider.local

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.isTseSafeClientId
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery

/**
 * Chooses the terminal a TSE setup registers as the device's first client, and records afterwards
 * that it is registered.
 *
 * The operator chooses it (owner decision 2026-10-04): the back office is a browser, not a paired
 * terminal, so resolving the client from the caller's device alone made commissioning from there
 * impossible — `404 Terminal not found for deviceId=…` on the real Swissbit TSE. A chosen terminal
 * need not be paired: a client id is only a name registered on the TSE. Without a choice the
 * caller's own terminal is used, which is what an older till sends.
 *
 * Every refusal here happens before the TSE is touched.
 */
class TseSetupTerminal(
    private val terminalRepository: TerminalRepository,
) {
    /**
     * The client id to register: [chosenTerminalId] when given, else the terminal of [deviceId].
     *
     * @throws PosException 400 for a blank or TSE-unsafe id, 404 `TERMINAL_NOT_FOUND` for an unknown
     *   chosen id, 404 `TSE_SETUP_TERMINAL_REQUIRED` when nothing was chosen and the caller is no
     *   terminal, 409 `TERMINAL_DEACTIVATED` / `TERMINAL_NOT_FISCAL` for a terminal that cannot sign.
     */
    suspend fun resolve(
        deviceId: String,
        chosenTerminalId: String?,
    ): String {
        val terminal =
            if (chosenTerminalId != null) {
                findChosen(chosenTerminalId.trim())
            } else {
                dbQuery { terminalRepository.findByDeviceId(deviceId) } ?: throw noTerminalChosen(deviceId)
            }
        return dbQuery { requireUsable(terminal) }
    }

    /**
     * Records that setup registered [clientId] on the TSE — the same flag `tse-register` sets — and
     * returns the bound device id whose cached capability is now stale (null when unpaired).
     */
    suspend fun markRegistered(clientId: String): String? =
        dbQuery {
            val terminal = terminalRepository.getAll().firstOrNull { it.clientId == clientId } ?: return@dbQuery null
            terminal.tseClientRegistered = true
            terminal.deviceId
        }

    /**
     * Records that a factory reset removed every client from the TSE: no terminal is registered any
     * more. Without this the flags stayed true, the capability resolver kept answering "can sign", and
     * the next order on such a terminal failed `WORM_ERROR_CLIENT_NOT_REGISTERED` and sat in the
     * signing queue until someone registered the terminal again.
     */
    suspend fun markAllUnregistered() {
        dbQuery { terminalRepository.getAll().forEach { it.tseClientRegistered = false } }
    }

    private suspend fun findChosen(clientId: String): Terminal {
        if (clientId.isBlank()) {
            throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, "Terminal id must not be blank")
        }
        return dbQuery { terminalRepository.getAll().firstOrNull { it.clientId == clientId } }
            ?: throw PosException(
                code = PosErrorCode.TERMINAL_NOT_FOUND,
                httpStatus = HttpStatusCode.NotFound,
                message = "No terminal '$clientId' exists. Choose an existing terminal; nothing was written to the TSE.",
            )
    }

    private fun requireUsable(terminal: Terminal): String {
        val clientId = terminal.clientId
        if (!terminal.isActive) {
            throw PosException(
                code = PosErrorCode.TERMINAL_DEACTIVATED,
                httpStatus = HttpStatusCode.Conflict,
                message = "Terminal '$clientId' is deactivated and cannot become a TSE client; nothing was written to the TSE.",
            )
        }
        if (!terminal.fiscal) {
            throw PosException(
                code = PosErrorCode.TERMINAL_NOT_FISCAL,
                httpStatus = HttpStatusCode.Conflict,
                message = "Terminal '$clientId' is not fiscal and never signs, so it cannot be the TSE's client; nothing was written to the TSE.",
            )
        }
        if (!isTseSafeClientId(clientId)) {
            throw PosException(
                code = PosErrorCode.VALIDATION_FAILED,
                httpStatus = HttpStatusCode.BadRequest,
                message = "Terminal clientId '$clientId' contains characters a TSE 2.0 rejects",
            )
        }
        return clientId
    }

    private fun noTerminalChosen(deviceId: String): PosException =
        PosException(
            code = PosErrorCode.TSE_SETUP_TERMINAL_REQUIRED,
            httpStatus = HttpStatusCode.NotFound,
            message =
                "Terminal not found for deviceId=$deviceId. Choose the terminal to register as the TSE's first client " +
                    "(create one under Geräte → Terminals if none exists); nothing was written to the TSE.",
        )
}
