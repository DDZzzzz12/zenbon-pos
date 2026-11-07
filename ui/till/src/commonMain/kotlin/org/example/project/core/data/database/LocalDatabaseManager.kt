package org.example.project.core.data.database

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.network.PinnedRootTrust
import org.example.project.core.network.defaultApiBaseUrl
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class LocalDatabaseManager(
    private val keyValueStore: KeyValueStore,
) {
    companion object {
        private const val LOGIN_REMEMBER_ME = "rememberMe"
        private const val LOGIN_USERDATA = "userData"
        private const val LOGIN_PASSWORD = "password"
        private const val LAST_SYNCED_AT = "lastSyncedAt"
        private const val LAST_SEEN_SEQ = "last_seen_seq"

        private const val USER_ID = "user_id"
        private const val USER_NAME = "user_name"
        private const val EMPLOYEE_NUMBER = "employee_number"
        private const val EMPLOYEE_NAME = "employee_name"
        private const val EMPLOYEE_ROLE = "employee_role"
        private const val EMPLOYEE_CAPABILITIES = "employee_capabilities"

        private const val API_URL = "api_base_url"
        private const val AUTO_RECONNECT = "auto_reconnect"

        private const val EMPLOYEE_TRAINING_MODE = "employee_training_mode"

        // Terminal registration tracking
        private const val TERMINAL_REGISTERED = "terminal_registered"
        private const val TERMINAL_ID = "terminal_id"
        private const val TERMINAL_DATABASE_ID = "terminal_database_id"
        private const val DEVICE_TOKEN = "device_token"
        private const val PINNED_ROOT_PEM = "pinned_root_pem"
    }

    // No device-token read here. The token is sealed, and Koin first builds this on the UI thread; a read that caught
    // the key store unready would also leave the till without X-Device-Token until a restart. DeviceTokenProvider
    // loads it on demand instead — see installDeviceTokenLoader.
    init {
        // The root pinned at pairing is not a secret (a public certificate): read it here so the TLS
        // trust managers have it before the first request.
        PinnedRootTrust.set(getPinnedRootPem())
    }

    // User Preferences
    fun setPreference(
        key: String,
        value: String,
    ) {
        keyValueStore.setString(key, value)
    }

    fun getPreference(key: String): String? = keyValueStore.getString(key)

    fun getAllPreferences(): Map<String, String> = keyValueStore.getAll()

    fun setApiBaseUrl(url: String): Boolean {
        setPreference(API_URL, url)
        return true
    }

    fun getAutoReconnect(): Boolean {
        getPreference(AUTO_RECONNECT)?.let {
            return it.toBoolean()
        }
        return false
    }

    fun getApiBaseUrl(): String {
        // A stored preference always wins; the default is per platform (see [defaultApiBaseUrl]).
        val api = getPreference(API_URL) ?: defaultApiBaseUrl()
        return if (api.endsWith("/")) api else "$api/"
    }

    fun getEmployeeNumber(): String? = getPreference(EMPLOYEE_NUMBER)

    fun getEmployeeName(): String? = getPreference(EMPLOYEE_NAME)

    fun getLoginRememberMe(): Boolean? {
        getPreference(LOGIN_REMEMBER_ME)?.let {
            return it.toBoolean()
        }
        return null
    }

    fun getLoginUserData(): String? = getPreference(LOGIN_USERDATA)

    fun getLoginPassword(): String? = getPreference(LOGIN_PASSWORD)

    fun setLoginUserData(
        username: String,
        password: String,
        rememberMe: Boolean,
    ) {
        if (rememberMe) {
            setPreference(LOGIN_USERDATA, username)
            setPreference(LOGIN_PASSWORD, password)
            setPreference(LOGIN_REMEMBER_ME, "true")
        } else {
            clearLoginUserData()
        }
    }

    fun setUserInfo(employeeInfo: EmployeeInfoDto.EmployeeInfo) {
        var employeeName = ""
        employeeInfo.firstName?.let { employeeName += it }
        employeeInfo.lastName?.let { employeeName += " $it" }
        setPreference(USER_ID, employeeInfo.id)
        setPreference(USER_NAME, employeeInfo.username)
        setPreference(EMPLOYEE_NUMBER, employeeInfo.employeeNumber)
        setPreference(EMPLOYEE_NAME, employeeName)
        setPreference(EMPLOYEE_ROLE, employeeInfo.role)
        setPreference(EMPLOYEE_TRAINING_MODE, employeeInfo.trainingMode.toString())
        // Persisted so a restart has the real answer before `/api/auth/me` returns. Without this the
        // app would gate on the role fallback for the first moments of every launch, which flickers
        // controls in and out for anyone whose group is narrower than their role.
        setPreference(EMPLOYEE_CAPABILITIES, employeeInfo.capabilities.sorted().joinToString(","))
    }

    fun clearUserInfo() {
        setPreference(USER_ID, "")
        setPreference(USER_NAME, "")
        setPreference(EMPLOYEE_NUMBER, "")
        setPreference(EMPLOYEE_NAME, "")
        setPreference(EMPLOYEE_ROLE, "")
        setPreference(EMPLOYEE_TRAINING_MODE, "false")
        setPreference(EMPLOYEE_CAPABILITIES, "")
    }

    /**
     * The capability names the server last reported, or **null when it reported none**.
     *
     * Null means *unknown*, never "holds nothing". A server predating the capability field returns
     * an empty set, and a client that read that as "holds nothing" would hide every control in the
     * UI; the caller falls back to role-based gating instead — see `PermissionManager.accessPolicy`.
     *
     * The one case this cannot distinguish is a real employee who belongs to no permission group and
     * therefore genuinely holds nothing. They can reach nothing server-side either way, so the
     * fallback shows them controls that 403 — the same as today's behaviour for them, and better
     * than a blank screen with no explanation.
     */
    fun getCapabilities(): Set<String>? =
        getPreference(EMPLOYEE_CAPABILITIES)
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()

    fun getUserId(): String? = getPreference(USER_ID)

    fun isTrainingMode(): Boolean = getPreference(EMPLOYEE_TRAINING_MODE)?.toBoolean() ?: false

    fun getRole(): EmployeeRole {
        val role = getPreference(EMPLOYEE_ROLE)
        return try {
            if (role == null) throw Exception("Role is null")
            EmployeeRole.getRole(role)
        } catch (e: Exception) {
            throw Exception("Role is null")
        }
    }

    fun setLastSyncedAt(reset: Boolean = false) {
        if (!reset) {
            setPreference(
                LAST_SYNCED_AT,
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .toString(),
            )
        } else {
            setPreference(LAST_SYNCED_AT, LocalDateTime(2000, 1, 1, 0, 0, 0, 0).toString())
        }
    }

    fun getLastSyncedAt(reset: Boolean = false): LocalDateTime {
        if (reset) return LocalDateTime(2000, 1, 1, 0, 0, 0, 0)

        getPreference(LAST_SYNCED_AT)?.let {
            return LocalDateTime.parse(it)
        }
        return LocalDateTime(2000, 1, 1, 0, 0, 0, 0)
    }

    fun setLastSeenSeq(seq: Long) = setPreference(LAST_SEEN_SEQ, seq.toString())

    fun getLastSeenSeq(): Long = getPreference(LAST_SEEN_SEQ)?.toLongOrNull() ?: 0L

    fun clearLoginUserData() {
        setPreference(LOGIN_USERDATA, "")
        setPreference(LOGIN_PASSWORD, "")
        setPreference(LOGIN_REMEMBER_ME, "false")
    }

    // Terminal Registration Management

    /**
     * Checks if this terminal has been registered with the server.
     *
     * @return true if terminal is registered, false otherwise
     */
    fun isTerminalRegistered(): Boolean = getPreference(TERMINAL_REGISTERED)?.toBoolean() ?: false

    /**
     * Marks this terminal as registered and stores terminal info.
     *
     * @param terminalId Human-readable terminal ID (e.g., "T001")
     * @param databaseId Terminal's database ID from server
     */
    fun setTerminalRegistered(
        terminalId: String,
        databaseId: Long,
    ) {
        setPreference(TERMINAL_REGISTERED, "true")
        setPreference(TERMINAL_ID, terminalId)
        setPreference(TERMINAL_DATABASE_ID, databaseId.toString())
    }

    /**
     * Gets the registered terminal's human-readable ID.
     *
     * @return Terminal ID like "T001", or null if not registered
     */
    fun getTerminalId(): String? = getPreference(TERMINAL_ID)?.takeIf { it.isNotBlank() }

    /**
     * Gets the registered terminal's database ID.
     *
     * @return Database ID, or null if not registered
     */
    fun getTerminalDatabaseId(): Long? = getPreference(TERMINAL_DATABASE_ID)?.toLongOrNull()

    /**
     * Clears terminal registration data.
     * Called when connecting to a different server or during reset.
     */
    fun clearTerminalRegistration() {
        setPreference(TERMINAL_REGISTERED, "false")
        setPreference(TERMINAL_ID, "")
        setPreference(TERMINAL_DATABASE_ID, "")
        clearDeviceToken()
    }

    /** Persists the pairing device token and syncs the request-time holder. */
    fun setDeviceToken(token: String) {
        setPreference(DEVICE_TOKEN, token)
        DeviceTokenProvider.token = token
    }

    /** Returns the persisted pairing device token, or null if the device is unpaired. */
    fun getDeviceToken(): String? = getPreference(DEVICE_TOKEN)?.takeIf { it.isNotBlank() }

    /**
     * Persists the server root certificate this device was told to trust at pairing and publishes it
     * to [PinnedRootTrust], which every TLS handshake reads. Replaces any earlier pin.
     */
    fun setPinnedRootPem(pem: String) {
        setPreference(PINNED_ROOT_PEM, pem)
        PinnedRootTrust.set(pem)
    }

    /** The persisted pinned root (PEM), or null when none was pinned. */
    fun getPinnedRootPem(): String? = getPreference(PINNED_ROOT_PEM)?.takeIf { it.isNotBlank() }

    /** Clears the pairing device token (revoke / unpair) and the request-time holder. */
    fun clearDeviceToken() {
        setPreference(DEVICE_TOKEN, "")
        DeviceTokenProvider.token = null
    }
}

data class OfflineQueueItem(
    val id: Long,
    val actionType: String,
    val endpoint: String,
    val payload: String,
    val createdAt: Long,
    val retryCount: Int,
    val maxRetries: Int,
)
