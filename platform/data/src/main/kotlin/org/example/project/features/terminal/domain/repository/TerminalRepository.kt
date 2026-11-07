/**
 * Repository interface for Terminal entity operations.
 *
 * Defines the contract for terminal data access operations.
 * Returns plain entities; services wrap calls in dbQuery with Result.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.features.terminal.domain.models.Terminal

/**
 * Interface defining terminal repository operations.
 *
 * Defines the contract for terminal data access operations including
 * registration, lookup, and management of client devices.
 */
interface TerminalRepository {
    /**
     * Finds a terminal by its device ID.
     *
     * @param deviceId Unique device identifier from client
     * @return Terminal entity or null if not found
     */
    fun findByDeviceId(deviceId: String): Terminal?

    /**
     * Creates a new terminal registration.
     *
     * @param deviceId Unique device identifier from client
     * @param model Device model information
     * @param brand Device brand/platform
     * @param serialNr Device serial number
     * @param swVersion Software version
     * @return Created Terminal entity
     */
    fun create(
        deviceId: String,
        model: String?,
        brand: String?,
        serialNr: String?,
        swVersion: String?,
    ): Terminal

    /**
     * Updates a terminal's information.
     *
     * @param terminal Terminal entity to update
     * @param model New model information
     * @param brand New brand information
     * @param serialNr New serial number
     * @param swVersion New software version
     * @return Updated Terminal entity or null if not found
     */
    fun update(
        terminal: Terminal,
        model: String?,
        brand: String?,
        serialNr: String?,
        swVersion: String?,
    ): Terminal?

    /**
     * Updates the last seen timestamp for a terminal.
     *
     * @param deviceId Device ID of the terminal
     */
    fun updateLastSeen(deviceId: String)

    /**
     * Retrieves all registered terminals.
     *
     * @return List of all Terminal entities
     */
    fun getAll(): List<Terminal>

    /**
     * Retrieves all active terminals.
     *
     * @return List of active Terminal entities
     */
    fun getActive(): List<Terminal>

    /**
     * Deactivates a terminal by ID.
     * Throws if not found.
     *
     * @param terminalId Database ID of the terminal
     */
    fun deactivate(terminalId: Long)

    /**
     * Reactivates a terminal by ID (sets isActive = true).
     * Throws if not found.
     *
     * @param terminalId Database ID of the terminal
     */
    fun reactivate(terminalId: Long)

    /**
     * Gets the next terminal ID sequence number.
     *
     * @return The next available terminal number (e.g., 3 for "T003")
     */
    fun getNextTerminalNumber(): Int

    /** Provision a new unbound terminal (no device). Generates the next TER-XXX clientId. */
    fun createUnbound(
        name: String?,
        role: String,
        fiscal: Boolean,
    ): Terminal

    fun findById(id: Long): Terminal?

    /**
     * Edits the operator-facing details of a terminal. A null argument leaves that field unchanged.
     *
     * Separate from [update], which exists for a device re-announcing its hardware on registration.
     * This one is the back office renaming a till or changing what it is for, and deliberately
     * cannot touch `fiscal` — see `UpdateTerminalRequestDto` for why.
     */
    fun updateDetails(
        terminal: Terminal,
        name: String?,
        location: String?,
        role: String?,
    )

    /** Bind (or rebind) a device to a terminal, overwriting any prior binding. */
    fun bindDevice(
        terminal: Terminal,
        deviceId: String,
        model: String?,
        brand: String?,
        serialNr: String?,
        swVersion: String?,
        deviceTokenHash: String,
        tokenExpiresAt: LocalDateTime,
        clientKind: ClientKind,
    ): Terminal

    /** Terminals currently bound to a device whose client kind is BROWSER. */
    fun findBoundBrowserTerminals(): List<Terminal>

    /** Resolve a bound terminal by its stored device-token hash (device-principal auth, A4). */
    fun findByDeviceTokenHash(deviceTokenHash: String): Terminal?

    /** Remove the binding (revoke): clears deviceId + token hash so the device drops to needs-pairing. */
    fun clearBinding(terminal: Terminal)
}
