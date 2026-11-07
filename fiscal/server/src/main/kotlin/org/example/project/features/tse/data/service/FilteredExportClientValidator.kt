package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Validates the optional client filter of a time- or transaction-filtered TSE export (F69).
 *
 * Absent or blank means **all clients** and needs no check. A value must be a client this
 * installation knows: a terminal's `clientId` in the terminals table — any terminal, including a
 * deactivated one, because its past transactions are still on the TSE — or, failing that, a
 * client the TSE itself lists. Anything else is a 400: a typo would otherwise produce an empty
 * export (TSE 2.0) or a "no data" 404 (TSE 1.x) that reads like a missing transaction.
 *
 * The TSE list is best effort: on TSE 2.0 it needs the Admin sitting unlocked, and an export
 * deliberately does not. When it cannot be read, the terminals table alone decides.
 *
 * Multi-shop: client ids belong to terminals, and terminals belong to a shop. There is one shop
 * today and no caller shop scope in the request, so the whole terminals table is consulted. Once
 * a caller carries a `shopId`, restrict the lookup to that shop's terminals — a manager must not
 * be able to export another shop's till by naming its client id.
 */
class FilteredExportClientValidator(
    private val terminalRepository: TerminalRepository,
    private val tseAdminProvider: TseAdminProvider,
) {
    private val logger = LoggerFactory.getLogger(FilteredExportClientValidator::class.java)

    /**
     * The client to filter on, or null for all clients.
     *
     * @throws PosException 400 `VALIDATION_FAILED` when [clientId] is set but unknown.
     */
    suspend fun resolve(clientId: String?): String? {
        val requested = clientId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isTerminalClient(requested) || isTseClient(requested)) return requested
        throw PosException(
            code = PosErrorCode.VALIDATION_FAILED,
            httpStatus = HttpStatusCode.BadRequest,
            message = "Unknown TSE client '$requested': it is neither a terminal nor a client registered on the TSE",
        )
    }

    private suspend fun isTerminalClient(clientId: String): Boolean = dbQuery { terminalRepository.getAll().any { it.clientId == clientId } }

    private suspend fun isTseClient(clientId: String): Boolean =
        try {
            val result = tseAdminProvider.listClients()
            result.success && clientId in result.clients
        } catch (e: PosException) {
            logger.info("TSE client list unreadable (${e.code}); validating '$clientId' against terminals only")
            false
        }
}
