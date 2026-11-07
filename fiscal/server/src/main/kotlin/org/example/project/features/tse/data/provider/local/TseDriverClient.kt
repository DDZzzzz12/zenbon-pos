package org.example.project.features.tse.data.provider.local

import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.utils.dbQuery

/**
 * The register the in-process TSE driver runs its unattended work for: the first active terminal
 * the TSE already knows (`tseClientRegistered`).
 *
 * The one answer used at startup ([LocalTseLifecycleManager.initialize]), after re-acquiring the
 * device ([LocalTseLifecycleManager.reacquireIfLost]) and for every self test ([LocalTseSelfTest]).
 * There used to be two copies — `TseConfigurationServiceImpl.getSelfTestClientId` for startup and
 * `LocalTseAdminProvider.resolveSelfTestClientId` for "TSE neu starten" — and re-acquiring used
 * neither, which left a TSE plugged in during runtime without a client (real Swissbit TSE 1.x,
 * 2026-10-10).
 *
 * **No fallback to an unregistered terminal.** Both copies fell back to any active terminal. A TSE
 * 1.x self test against an unregistered client fails on the device with
 * `WORM_ERROR_CLIENT_NOT_REGISTERED`, which the driver treats as a hardware error and answers by
 * tearing the connection down — strictly worse than no client, which [LocalTseSelfTest] refuses
 * before any device call with a message telling the operator to register a terminal. Never a
 * synthetic client either: a TSE client is a Kasse and cannot be invented. TSE 2.0 needs no client
 * for the self test, so null is a valid answer there.
 *
 * @param terminalRepository the terminals and their TSE registration flag.
 */
class TseDriverClient(
    private val terminalRepository: TerminalRepository,
) {
    /** @return the registered client id, or null when no active terminal is registered on the TSE. */
    suspend fun resolve(): String? =
        dbQuery {
            terminalRepository.getActive().firstOrNull { it.tseClientRegistered }?.clientId
        }
}
