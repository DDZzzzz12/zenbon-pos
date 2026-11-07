package org.example.project.core.auth

import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * JWT access/refresh token persistence for both clients, over a platform [KeyValueStore]
 * (composeApp: SQLDelight; adminApp: in memory — its refresh token is an HttpOnly cookie).
 *
 * Every member is non-suspend on purpose: [org.example.project.ui.auth.SessionState]'s
 * `logout()` and the HTTP client's `onUnauthorized` callback are both non-suspend, and the
 * Ktor `Auth` provider already serialises refreshes per provider — so there is no lock here
 * and no second write path to guard.
 *
 * The storage keys match the former `JwtTokenManager` preference names so composeApp reads its
 * own historical values. adminApp's store is in memory; the `jwt_*` keys an earlier build left in
 * its `localStorage` are wiped at startup.
 */
@OptIn(ExperimentalTime::class)
class TokenStore(
    private val keyValueStore: KeyValueStore,
) {
    var accessToken: String?
        get() = keyValueStore.getString(KEY_ACCESS)
        set(value) = write(KEY_ACCESS, value)

    var refreshToken: String?
        get() = keyValueStore.getString(KEY_REFRESH)
        set(value) = write(KEY_REFRESH, value)

    /** The employee role from the login/refresh response, used for client-side write-gating. */
    var role: String?
        get() = keyValueStore.getString(KEY_ROLE)
        set(value) = write(KEY_ROLE, value)

    /**
     * The capability names from the login/refresh response, or **null when the server reported none**.
     *
     * Kept here rather than decoded from the JWT because capabilities are deliberately not a token
     * claim — baking them in was defect D5, which Stage 2a fixed by resolving them per request from
     * the permission group. Storing them alongside the tokens is what makes a token REFRESH also
     * refresh them: `/api/auth/refresh` answers a full `LoginResponse`, so the current set arrives
     * on every refresh with no extra round-trip, and staleness is bounded by the refresh interval.
     *
     * Null means unknown, never "holds nothing" — an older server reports nothing, and a client that
     * read that as an empty grant would hide every control in the UI.
     */
    var capabilities: Set<String>?
        get() =
            keyValueStore
                .getString(KEY_CAPABILITIES)
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.toSet()
        set(value) = write(KEY_CAPABILITIES, value?.sorted()?.joinToString(","))

    /**
     * The signed-in employee's permission group as `(code, stored display name)`, from the
     * login/refresh response — or null when the server reported none (no group, or a server that
     * predates the field). **Display only**: gating reads [capabilities], never this.
     *
     * Kept because the group, not [role], says what the person is (R6-4): an AUDITOR carries role
     * CASHIER, and a footer reading the role labelled them "Kassierer".
     */
    var permissionGroupCode: String?
        get() = keyValueStore.getString(KEY_GROUP_CODE)
        set(value) = write(KEY_GROUP_CODE, value)

    /** See [permissionGroupCode]. */
    var permissionGroupName: String?
        get() = keyValueStore.getString(KEY_GROUP_NAME)
        set(value) = write(KEY_GROUP_NAME, value)

    /** Access-token expiry as epoch seconds, or 0 when unknown. */
    var expiresAt: Long
        get() = keyValueStore.getString(KEY_EXPIRES_AT)?.toLongOrNull() ?: 0L
        set(value) = keyValueStore.setString(KEY_EXPIRES_AT, value.toString())

    /**
     * The `TerminalRole` name of the login-less station this session belongs to — `"ORDER_STATION"`
     * — or null for a person's session.
     *
     * The client mirror of the server's `station` JWT claim: written only by [storeStationSession],
     * never by a login. It is what tells the HTTP client to renew by re-exchanging the device token
     * instead of calling `/api/auth/refresh`, and what the shell reads to answer "is this an
     * unattended station" where a role literal used to.
     */
    var stationRole: String?
        get() = keyValueStore.getString(KEY_STATION_ROLE)
        set(value) = write(KEY_STATION_ROLE, value)

    /** Persists a fresh token pair from a login or refresh response. */
    fun storeTokens(
        accessToken: String,
        refreshToken: String?,
        expiresIn: Long,
    ) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = Clock.System.now().epochSeconds + expiresIn
        // A token PAIR is by definition a person's session, so the station marker cannot survive it.
        // A login does not call clear() first (only logout does), so without this an employee
        // logging into a device that previously ran a station session would inherit the marker —
        // and the shell reads stationRole to decide whether to hide the person-only surfaces.
        this.stationRole = null
    }

    /**
     * Persists a station session: the access token, NO refresh token, the station marker, and the
     * masked capability set the exchange reported. The role is always TERMINAL_DEVICE — a station
     * never runs as a person. An unparseable expiry is stored as 0, which [isAccessTokenExpired]
     * treats as "decode the JWT instead".
     */
    fun storeStationSession(session: TerminalDto.StationSessionResponseDto) {
        accessToken = session.accessToken
        refreshToken = null
        expiresAt = runCatching { Instant.parse(session.expiresAt).epochSeconds }.getOrDefault(0L)
        role = EmployeeRole.TERMINAL_DEVICE.name
        permissionGroupCode = null
        permissionGroupName = null
        stationRole = session.role
        capabilities = session.capabilities.ifEmpty { null }
    }

    /** Wipes every persisted key. Safe to call repeatedly. */
    fun clear() {
        keyValueStore.remove(KEY_ACCESS)
        keyValueStore.remove(KEY_REFRESH)
        keyValueStore.remove(KEY_ROLE)
        keyValueStore.remove(KEY_CAPABILITIES)
        keyValueStore.remove(KEY_GROUP_CODE)
        keyValueStore.remove(KEY_GROUP_NAME)
        keyValueStore.remove(KEY_EXPIRES_AT)
        keyValueStore.remove(KEY_STATION_ROLE)
    }

    /**
     * True when the access token is expired or falls inside the [REFRESH_BUFFER_SECONDS]
     * window. Prefers the stored expiry (cheap); falls back to decoding the JWT.
     */
    fun isAccessTokenExpired(): Boolean {
        val storedExpiry = expiresAt
        if (storedExpiry > 0) {
            return Clock.System.now().epochSeconds >= storedExpiry - REFRESH_BUFFER_SECONDS
        }
        val token = accessToken ?: return true
        return JwtDecoder.getTimeUntilExpiration(token) <= REFRESH_BUFFER_SECONDS
    }

    /** Seconds until the access token expires, or 0 when expired/absent. */
    fun getTimeUntilExpiration(): Long {
        val storedExpiry = expiresAt
        if (storedExpiry > 0) {
            return (storedExpiry - Clock.System.now().epochSeconds).coerceAtLeast(0L)
        }
        val token = accessToken ?: return 0L
        return JwtDecoder.getTimeUntilExpiration(token)
    }

    private fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) keyValueStore.remove(key) else keyValueStore.setString(key, value)
    }

    private companion object {
        const val KEY_ACCESS = "jwt_access_token"
        const val KEY_REFRESH = "jwt_refresh_token"
        const val KEY_EXPIRES_AT = "jwt_expires_at"
        const val KEY_ROLE = "jwt_role"
        const val KEY_CAPABILITIES = "jwt_capabilities"
        const val KEY_GROUP_CODE = "jwt_permission_group_code"
        const val KEY_GROUP_NAME = "jwt_permission_group_name"
        const val KEY_STATION_ROLE = "jwt_station_role"
        const val REFRESH_BUFFER_SECONDS = 300L
    }
}

/**
 * Whether this session belongs to a login-less station rather than to a person.
 *
 * The one spelling of the question, so the shell, the nav gate and the table rules cannot disagree
 * about it — it was written three ways (`stationRole != null` twice, `stationRole == null` once)
 * before the order-station shell settled. Reads [TokenStore.stationRole], which hits the platform
 * key-value store, so callers in a composition must hoist it out of recomposition.
 */
val TokenStore.isStation: Boolean
    get() = stationRole != null
