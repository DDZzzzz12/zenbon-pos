package org.example.project.core.domain.enums.tse

/**
 * Enumeration of available TSE admin commands.
 *
 * These commands are used for administrative operations on the TSE hardware:
 * - Setup and initialization
 * - Information retrieval and health checks
 * - Time synchronization
 * - Client management
 * - Transaction management
 * - Data export and deletion
 * - User management (PIN/PUK)
 * - CTSS interface control
 *
 * @property commandName The string name used in API requests
 * @property description Human-readable description of the command
 * @property requiresConnection Whether the command requires TSE hardware connection
 *
 * @since 1.0.0
 */
enum class TseAdminCommand(
    val commandName: String,
    val description: String,
    val requiresConnection: Boolean = true,
) {
    // Status and Information
    STATUS("status", "Get TSE status information", requiresConnection = false),
    GET_INFO("getinfo", "Get detailed TSE information"),
    CHECK("check", "Perform TSE health check"),
    FLASH_HEALTH("flashhealth", "Get flash memory health summary"),

    // Setup and Initialization
    SETUP("setup", "Initialize TSE hardware setup"),
    FACTORY_RESET("factory_reset", "Reset TSE to factory defaults (development firmware only)"),

    // Self-Test
    SELF_TEST("selftest", "Run TSE self-test"),
    FORCE_SELF_TEST("forceselftest", "Force TSE self-test execution"),

    // Time Synchronization
    UPDATE_TIME("updatetime", "Synchronize TSE time with system time"),

    // Client Management
    REGISTER_CLIENT("registerclient", "Register a client ID with the TSE"),
    DEREGISTER_CLIENT("deregisterclient", "Deregister a client ID from the TSE"),
    LIST_CLIENTS("listclients", "List all registered clients"),

    // Transaction Management
    LIST_TRANSACTIONS("listtransactions", "List all started (unfinished) transactions"),
    LAST_TRANSACTION("lasttransaction", "Get the last transaction response"),

    // Data Export
    EXPORT_LOCAL("exportlocal", "Export TSE data as TAR archive"),
    DELETE_EXPORTED_DATA("deleteexporteddata", "Delete exported data from TSE storage (permanent)"),

    // User Management
    USER_UNBLOCK("userunblock", "Unblock user with PUK"),
    CHANGE_PIN("changepin", "Change user PIN"),

    // CTSS Interface Control
    CTSS_ENABLE("ctss_enable", "Enable CTSS interface"),
    CTSS_DISABLE("ctss_disable", "Disable CTSS interface"),
    ;

    companion object {
        /**
         * Get command by its string name (case-insensitive).
         *
         * @param command The command name string
         * @return The matching TseAdminCommand or null if not found
         */
        fun fromString(command: String): TseAdminCommand? = entries.find { it.commandName.equals(command, ignoreCase = true) }

        /**
         * Get all available command names.
         *
         * @return List of all command name strings
         */
        fun getAllCommandNames(): List<String> = entries.map { it.commandName }

        /**
         * Get commands that don't require TSE connection.
         *
         * @return List of commands that work without hardware connection
         */
        fun getOfflineCommands(): List<TseAdminCommand> = entries.filter { !it.requiresConnection }

        /**
         * Get commands that require TSE connection.
         *
         * @return List of commands that require hardware connection
         */
        fun getOnlineCommands(): List<TseAdminCommand> = entries.filter { it.requiresConnection }
    }
}
