/**
 * Implementation of CardTerminalService for managing card terminal lifecycle.
 *
 * Handles terminal registration, connection management, diagnostics, and
 * startup initialization. Maintains in-memory maps of active ZVT clients
 * and message handlers for use by payment and settlement services.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 9.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.example.project.core.domain.dto.cardTerminal.CardTerminalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.cardTerminal.domain.models.CardTerminal
import org.example.project.features.cardTerminal.domain.repository.CardTerminalRepository
import org.example.project.features.cardTerminal.domain.service.CardTerminalService
import org.example.project.features.cardTerminal.zvt.client.ZvtClient
import org.example.project.features.cardTerminal.zvt.client.ZvtClientImpl
import org.example.project.features.cardTerminal.zvt.client.ZvtMessageHandler
import org.example.project.features.cardTerminal.zvt.models.ZvtConfig
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of [CardTerminalService].
 *
 * Manages the lifecycle of ZVT card terminal connections. Maintains an
 * in-memory map of active [ZvtClient] instances keyed by terminal ID.
 *
 * @property repository Data access for CardTerminal entities
 */
class CardTerminalServiceImpl(
    private val repository: CardTerminalRepository,
) : CardTerminalService {
    private val logger = LoggerFactory.getLogger(CardTerminalServiceImpl::class.java)

    private val clients = ConcurrentHashMap<Long, ZvtClient>()
    private val messageHandlers = ConcurrentHashMap<Long, ZvtMessageHandler>()

    /**
     * Returns the active ZvtClient for the given terminal, or null if not connected.
     */
    fun getClient(terminalId: Long): ZvtClient? = clients[terminalId]

    /**
     * Returns the ZvtMessageHandler for the given terminal, or null if not connected.
     */
    fun getMessageHandler(terminalId: Long): ZvtMessageHandler? = messageHandlers[terminalId]

    override suspend fun getAllTerminals(): List<CardTerminalDto.CardTerminalResponseDto> =
        dbQuery {
            repository.getAll().map { it.toResponse() }
        }

    override suspend fun getTerminalById(id: Long): CardTerminalDto.CardTerminalResponseDto =
        dbQuery {
            val terminal = repository.getById(id) ?: throw cardTerminalNotFound(id)
            terminal.toResponse()
        }

    override suspend fun getAvailableTerminals(): List<CardTerminalDto.CardTerminalResponseDto> =
        dbQuery {
            repository.getActiveTerminals().filter { it.isConnected && !it.isBusy }.map { it.toResponse() }
        }

    override suspend fun registerTerminal(request: CardTerminalDto.CreateCardTerminalRequestDto): CardTerminalDto.CardTerminalResponseDto =
        dbQuery {
            try {
                repository
                    .create(
                        name = request.name,
                        host = request.host,
                        port = request.port,
                        password = request.password,
                        shopInfoId = request.shopInfoId,
                    ).toResponse()
            } catch (e: NoSuchElementException) {
                throw PosException(
                    PosErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatusCode.NotFound,
                    "ShopInfo ${request.shopInfoId} not found",
                    cause = e,
                )
            }
        }

    override suspend fun updateTerminal(
        id: Long,
        request: CardTerminalDto.UpdateCardTerminalRequestDto,
    ): CardTerminalDto.CardTerminalResponseDto =
        dbQuery {
            val terminal =
                repository.update(id, request.name, request.host, request.port, request.password)
                    ?: throw cardTerminalNotFound(id)
            terminal.toResponse()
        }

    override suspend fun deleteTerminal(id: Long) {
        // Disconnect first, best-effort
        val existingClient = clients.remove(id)
        messageHandlers.remove(id)
        if (existingClient != null) {
            try {
                existingClient.logOff()
            } catch (e: Exception) {
                logger.warn("Error during logOff on delete for terminal $id: ${e.message}")
            }
        }
        dbQuery {
            val deleted = repository.delete(id)
            if (!deleted) {
                throw cardTerminalNotFound(id)
            }
        }
    }

    override suspend fun connectTerminal(id: Long): CardTerminalDto.CardTerminalResponseDto {
        // Read config inside dbQuery
        data class TerminalConfig(
            val host: String,
            val port: Int,
            val password: String,
        )
        val config =
            dbQuery {
                val t = repository.getById(id) ?: throw cardTerminalNotFound(id)
                TerminalConfig(t.host, t.port, t.password)
            }

        val handler = ZvtMessageHandler()
        val client = ZvtClientImpl(handler)
        val zvtConfig =
            ZvtConfig(
                host = config.host,
                port = config.port,
                password = config.password.toIntOrNull() ?: 0,
            )

        val terminalStatus =
            try {
                client.register(zvtConfig)
            } catch (e: PosException) {
                try {
                    dbQuery { repository.updateConnectionState(id, false) }
                } catch (ignored: Exception) {
                    logger.warn("Failed to update disconnected state for terminal $id after register failure: ${ignored.message}")
                }
                throw cardTerminalUnreachable(id, e.message ?: "protocol error", cause = e)
            } catch (e: Exception) {
                logger.error("Failed to connect terminal $id: ${e.message}", e)
                try {
                    dbQuery { repository.updateConnectionState(id, false) }
                } catch (ignored: Exception) {
                    logger.warn("Failed to update disconnected state for terminal $id: ${ignored.message}")
                }
                throw cardTerminalUnreachable(id, e.message ?: "I/O", cause = e)
            }

        clients[id] = client
        messageHandlers[id] = handler
        val terminalIdStr = terminalStatus.terminalId
        return dbQuery {
            repository.updateConnectionState(id, true)
            repository.updateTerminalInfo(id, terminalIdStr, null)
            repository.getById(id)!!.toResponse()
        }
    }

    override suspend fun disconnectTerminal(id: Long): CardTerminalDto.CardTerminalResponseDto {
        val existingClient = clients.remove(id)
        messageHandlers.remove(id)
        if (existingClient != null) {
            try {
                existingClient.logOff()
            } catch (e: Exception) {
                logger.warn("Error during logOff for terminal $id: ${e.message}")
            }
        }
        return dbQuery {
            repository.updateConnectionState(id, false)
            val t = repository.getById(id) ?: throw cardTerminalNotFound(id)
            t.toResponse()
        }
    }

    override suspend fun getTerminalStatus(id: Long): CardTerminalDto.CardTerminalResponseDto {
        val client = clients[id] ?: throw cardTerminalDisconnected(id)
        val terminalStatus =
            try {
                client.statusEnquiry()
            } catch (e: PosException) {
                throw cardTerminalUnreachable(id, e.message ?: "protocol error", cause = e)
            } catch (e: Exception) {
                logger.error("Failed to get terminal status for $id: ${e.message}", e)
                throw cardTerminalUnreachable(id, e.message ?: "I/O", cause = e)
            }
        return dbQuery {
            repository.updateTerminalInfo(id, terminalStatus.terminalId, null)
            repository.getById(id)!!.toResponse()
        }
    }

    override suspend fun runDiagnosis(id: Long): CardTerminalDto.DiagnosisResponseDto {
        val client = clients[id] ?: throw cardTerminalDisconnected(id)

        data class TerminalInfo(
            val name: String,
            val host: String,
            val port: Int,
            val softwareVersion: String?,
        )
        val info =
            dbQuery {
                val t = repository.getById(id) ?: throw cardTerminalNotFound(id)
                TerminalInfo(t.name, t.host, t.port, t.softwareVersion)
            }
        val diag =
            try {
                client.diagnosis()
            } catch (e: PosException) {
                throw cardProtocolError(e.message ?: "protocol error", cause = e)
            } catch (e: Exception) {
                logger.error("Diagnosis failed for terminal $id: ${e.message}", e)
                throw cardTerminalUnreachable(id, e.message ?: "I/O", cause = e)
            }
        return CardTerminalDto.DiagnosisResponseDto(
            terminalId = id,
            terminalName = info.name,
            host = info.host,
            port = info.port,
            softwareVersion = info.softwareVersion,
            isConnected = true,
            diagnosisSuccess = diag.approved,
            resultCode = diag.resultCode,
            message = diag.errorDescription,
        )
    }

    override suspend fun initializeOnStartup() {
        try {
            logger.info("Resetting all terminal runtime state")
            dbQuery { repository.resetRuntimeState() }

            val terminalIds =
                dbQuery {
                    repository.getActiveTerminals().map { it.id.value }
                }
            logger.info("Attempting to connect ${terminalIds.size} active terminal(s) on startup")

            // Launch connections in background — don't block startup
            CoroutineScope(Dispatchers.IO).launch {
                for (terminalId in terminalIds) {
                    try {
                        connectTerminal(terminalId)
                        logger.info("Terminal $terminalId connected on startup")
                    } catch (e: Exception) {
                        logger.warn("Terminal $terminalId failed to connect on startup: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize terminals on startup: ${e.message}", e)
        }
    }

    // ===== Mapping =====

    private fun CardTerminal.toResponse(): CardTerminalDto.CardTerminalResponseDto =
        CardTerminalDto.CardTerminalResponseDto(
            id = id.value,
            name = name,
            host = host,
            port = port,
            terminalId = terminalId,
            softwareVersion = softwareVersion,
            isActive = isActive,
            isConnected = isConnected,
            isBusy = isBusy,
            shopInfoId = shopInfo.id.value,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    // ===== PosException helpers =====

    private fun cardTerminalNotFound(id: Long) =
        PosException(
            PosErrorCode.CARD_TERMINAL_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Card terminal $id not found",
        )

    private fun cardTerminalDisconnected(id: Long) =
        PosException(
            PosErrorCode.CARD_TERMINAL_DISCONNECTED,
            HttpStatusCode.ServiceUnavailable,
            "Card terminal $id disconnected",
        )

    private fun cardTerminalUnreachable(
        id: Long,
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.CARD_TERMINAL_UNREACHABLE,
        HttpStatusCode.ServiceUnavailable,
        "Card terminal $id unreachable: $msg",
        cause = cause,
    )

    private fun cardProtocolError(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.CARD_PROTOCOL_ERROR,
        HttpStatusCode.BadGateway,
        "Card terminal protocol error: $msg",
        cause = cause,
    )
}
