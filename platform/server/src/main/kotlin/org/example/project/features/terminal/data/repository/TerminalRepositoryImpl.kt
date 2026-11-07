/**
 * Implementation of TerminalRepository using Exposed ORM.
 *
 * Provides concrete implementation of terminal data access operations
 * using Exposed database framework. Exceptions propagate to service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed-based implementation of TerminalRepository.
 *
 * Provides data access for terminal registration and management.
 * Note: Does NOT wrap in dbQuery - service layer controls transactions.
 */
class TerminalRepositoryImpl : TerminalRepository {
    override fun findByDeviceId(deviceId: String): Terminal? = Terminal.find { TerminalTable.deviceId eq deviceId }.firstOrNull()

    override fun create(
        deviceId: String,
        model: String?,
        brand: String?,
        serialNr: String?,
        swVersion: String?,
    ): Terminal {
        val timestamp = currentTimeStamp()

        // Get next terminal number for human-readable ID
        val nextNumber = getNextTerminalNumberInternal()
        val terminalId = "TER-${nextNumber.toString().padStart(3, '0')}"

        // Get shop info for reference
        val shopInfo = ShopInfo.all().firstOrNull()

        return Terminal.new {
            this.deviceId = deviceId
            this.clientId = terminalId
            this.terminalBrand = brand
            this.terminalModel = model
            this.terminalSerialNr = serialNr
            this.terminalSwBrand = "POS-KMP"
            this.terminalSwVersion = swVersion
            this.isActive = true
            this.shopInfo = shopInfo
            this.registeredAt = timestamp
            this.lastSeenAt = timestamp
        }
    }

    override fun update(
        terminal: Terminal,
        model: String?,
        brand: String?,
        serialNr: String?,
        swVersion: String?,
    ): Terminal? {
        terminal.apply {
            model?.let { terminalModel = it }
            brand?.let { terminalBrand = it }
            serialNr?.let { terminalSerialNr = it }
            swVersion?.let { terminalSwVersion = it }
            lastSeenAt = currentTimeStamp()
        }

        return terminal
    }

    override fun updateLastSeen(deviceId: String) {
        val terminal =
            Terminal.find { TerminalTable.deviceId eq deviceId }.firstOrNull()
                ?: throw NoSuchElementException("Terminal not found: $deviceId")
        terminal.lastSeenAt = currentTimeStamp()
    }

    override fun getAll(): List<Terminal> = Terminal.all().toList()

    override fun getActive(): List<Terminal> = Terminal.find { TerminalTable.isActive eq true }.toList()

    override fun deactivate(terminalId: Long) {
        val terminal =
            Terminal.findById(terminalId)
                ?: throw NoSuchElementException("Terminal not found: $terminalId")
        // lastSeenAt records device contact, not administration: an operator's click must not make
        // a never-seen terminal read "seen today" (real-TSE QA rt14).
        terminal.isActive = false
    }

    override fun reactivate(terminalId: Long) {
        val terminal =
            Terminal.findById(terminalId)
                ?: throw NoSuchElementException("Terminal not found: $terminalId")
        terminal.isActive = true
    }

    override fun getNextTerminalNumber(): Int = getNextTerminalNumberInternal()

    override fun createUnbound(
        name: String?,
        role: String,
        fiscal: Boolean,
    ): Terminal {
        val timestamp = currentTimeStamp()
        val shopInfo = ShopInfo.all().firstOrNull()
        // clientId is UNIQUE (V104). Two managers provisioning at once could collide on max+1;
        // retry a few times before surfacing. Exposed defers the INSERT until flush/commit, so
        // without an eager flush() here the unique violation would surface OUTSIDE this try (at
        // the enclosing transaction's commit) and the retry would never fire — force the flush so
        // the violation, if any, is raised and caught within this loop.
        var lastError: Throwable? = null
        repeat(5) {
            val terminalId = "TER-${getNextTerminalNumberInternal().toString().padStart(3, '0')}"
            try {
                val terminal =
                    Terminal.new {
                        this.deviceId = null
                        this.clientId = terminalId
                        this.name = name ?: terminalId
                        this.role = role
                        this.fiscal = fiscal
                        this.terminalSwBrand = "POS-KMP"
                        this.isActive = true
                        this.shopInfo = shopInfo
                        this.registeredAt = timestamp
                        this.lastSeenAt = timestamp
                    }
                terminal.flush()
                return terminal
            } catch (e: Exception) {
                lastError = e // unique-violation on terminal_id — recompute max+1 and retry
            }
        }
        throw lastError ?: IllegalStateException("Failed to allocate a terminal id")
    }

    override fun findById(id: Long): Terminal? = Terminal.findById(id)

    override fun updateDetails(
        terminal: Terminal,
        name: String?,
        location: String?,
        role: String?,
    ) {
        // A blank string is the back office clearing the field; null is "not part of this request".
        name?.let { terminal.name = it.trim().ifBlank { null } }
        location?.let { terminal.location = it.trim().ifBlank { null } }
        role?.let { terminal.role = it }
    }

    override fun bindDevice(
        terminal: Terminal,
        deviceId: String,
        model: String?,
        brand: String?,
        serialNr: String?,
        swVersion: String?,
        deviceTokenHash: String,
        tokenExpiresAt: LocalDateTime,
        clientKind: ClientKind,
    ): Terminal {
        val timestamp = currentTimeStamp()
        terminal.apply {
            this.deviceId = deviceId
            model?.let { terminalModel = it }
            brand?.let { terminalBrand = it }
            serialNr?.let { terminalSerialNr = it }
            swVersion?.let { terminalSwVersion = it }
            this.deviceTokenHash = deviceTokenHash
            this.deviceTokenExpiresAt = tokenExpiresAt
            this.boundAt = timestamp
            this.lastSeenAt = timestamp
            this.isActive = true
            this.clientKind = clientKind.name
        }
        return terminal
    }

    override fun findBoundBrowserTerminals(): List<Terminal> =
        Terminal
            .find { (TerminalTable.deviceId.isNotNull()) and (TerminalTable.clientKind eq ClientKind.BROWSER.name) }
            .toList()

    override fun findByDeviceTokenHash(deviceTokenHash: String): Terminal? = Terminal.find { TerminalTable.deviceTokenHash eq deviceTokenHash }.firstOrNull()

    override fun clearBinding(terminal: Terminal) {
        terminal.deviceId = null
        terminal.deviceTokenHash = null
        terminal.deviceTokenExpiresAt = null
        terminal.boundAt = null
    }

    /**
     * Internal method to get next terminal number.
     * Parses existing TER-XXX format terminal IDs to find the next number.
     */
    private fun getNextTerminalNumberInternal(): Int {
        val maxTerminal =
            Terminal
                .all()
                .map { it.clientId }
                .filter { it.startsWith("TER-") }
                .mapNotNull { it.removePrefix("TER-").toIntOrNull() }
                .maxOrNull() ?: 0

        return maxTerminal + 1
    }
}
