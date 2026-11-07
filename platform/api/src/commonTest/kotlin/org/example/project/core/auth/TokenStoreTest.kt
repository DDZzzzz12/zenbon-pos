package org.example.project.core.auth

import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.testutils.FakeKeyValueStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TokenStoreTest {
    private fun store() = TokenStore(FakeKeyValueStore())

    @Test
    fun storeTokens_persists_access_refresh_and_expiry() {
        val tokenStore = store()

        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 3600)

        assertEquals("access-1", tokenStore.accessToken)
        assertEquals("refresh-1", tokenStore.refreshToken)
        val now = Clock.System.now().epochSeconds
        assertTrue(tokenStore.expiresAt in (now + 3590)..(now + 3610))
    }

    @Test
    fun role_round_trips() {
        val tokenStore = store()

        tokenStore.role = "MANAGER"

        assertEquals("MANAGER", tokenStore.role)
    }

    @Test
    fun setting_a_property_to_null_removes_it() {
        val tokenStore = store()
        tokenStore.accessToken = "access-1"

        tokenStore.accessToken = null

        assertNull(tokenStore.accessToken)
    }

    // R6-4: display-only, but it must not outlive the session that reported it.
    @Test
    fun permissionGroup_round_trips_and_is_wiped_by_clear() {
        val tokenStore = store()
        tokenStore.permissionGroupCode = "AUDITOR"
        tokenStore.permissionGroupName = "Auditor"

        assertEquals("AUDITOR", tokenStore.permissionGroupCode)
        assertEquals("Auditor", tokenStore.permissionGroupName)

        tokenStore.clear()

        assertNull(tokenStore.permissionGroupCode)
        assertNull(tokenStore.permissionGroupName)
    }

    @Test
    fun clear_wipes_every_key() {
        val tokenStore = store()
        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 3600)
        tokenStore.role = "ADMIN"

        tokenStore.clear()

        assertNull(tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
        assertNull(tokenStore.role)
        assertEquals(0L, tokenStore.expiresAt)
    }

    @Test
    fun isAccessTokenExpired_is_true_within_the_five_minute_buffer() {
        val tokenStore = store()

        // Expires in 4 minutes — inside the 5-minute refresh buffer.
        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 240)

        assertTrue(tokenStore.isAccessTokenExpired())
    }

    @Test
    fun isAccessTokenExpired_is_false_outside_the_buffer() {
        val tokenStore = store()

        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 3600)

        assertFalse(tokenStore.isAccessTokenExpired())
    }

    @Test
    fun isAccessTokenExpired_is_true_with_no_token_at_all() {
        assertTrue(store().isAccessTokenExpired())
    }

    @Test
    fun isAccessTokenExpired_falls_back_to_decoding_the_jwt_when_no_expiry_was_stored() {
        val tokenStore = store()
        // Access token set directly (no storeTokens), so expiresAt stays 0 and the
        // decoder fallback is the only source of truth.
        tokenStore.accessToken = jwtExpiringIn(3600)

        assertFalse(tokenStore.isAccessTokenExpired())

        tokenStore.accessToken = jwtExpiringIn(60)

        assertTrue(tokenStore.isAccessTokenExpired())
    }

    @Test
    fun getTimeUntilExpiration_counts_down_from_the_stored_expiry() {
        val tokenStore = store()

        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 1800)

        assertTrue(tokenStore.getTimeUntilExpiration() in 1790..1800)
    }

    @Test
    fun getTimeUntilExpiration_is_zero_when_empty() {
        assertEquals(0L, store().getTimeUntilExpiration())
    }

    @Test
    fun stationRole_round_trips_and_is_null_for_a_person() {
        val tokenStore = store()
        assertNull(tokenStore.stationRole)

        tokenStore.stationRole = "ORDER_STATION"

        assertEquals("ORDER_STATION", tokenStore.stationRole)
    }

    @Test
    fun clear_wipes_the_station_role() {
        val tokenStore = store()
        tokenStore.stationRole = "ORDER_STATION"

        tokenStore.clear()

        assertNull(tokenStore.stationRole)
    }

    @Test
    fun storeStationSession_keeps_no_refresh_token_and_marks_the_station() {
        val tokenStore = store()
        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 3600)

        tokenStore.storeStationSession(
            TerminalDto.StationSessionResponseDto(
                accessToken = "station-1",
                expiresAt = "2030-01-01T00:00:00Z",
                terminalId = "TER-007",
                role = "ORDER_STATION",
                capabilities = setOf("ORDER_READ"),
            ),
        )

        assertEquals("station-1", tokenStore.accessToken)
        assertNull(tokenStore.refreshToken, "a station has no refresh token — renewal re-exchanges the device token")
        assertEquals("ORDER_STATION", tokenStore.stationRole)
        assertEquals("TERMINAL_DEVICE", tokenStore.role)
        assertEquals(setOf("ORDER_READ"), tokenStore.capabilities)
        assertEquals(1893456000L, tokenStore.expiresAt)
    }

    @Test
    fun storing_a_pair_without_a_refresh_token_removes_the_previous_one() {
        val tokenStore = store()
        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 900)

        tokenStore.storeTokens("access-2", null, expiresIn = 900)

        assertEquals("access-2", tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
    }

    @Test
    fun storeTokens_clears_the_station_marker_because_a_login_is_a_person() {
        val tokenStore = store()
        tokenStore.storeStationSession(
            TerminalDto.StationSessionResponseDto(
                accessToken = "station-1",
                expiresAt = "2030-01-01T00:00:00Z",
                terminalId = "TER-007",
                role = "ORDER_STATION",
                capabilities = setOf("ORDER_READ"),
            ),
        )

        // A login does not clear() first — only logout does. Without the reset in storeTokens an
        // employee logging into a device that had run a station session inherits the marker.
        tokenStore.storeTokens("access-1", "refresh-1", expiresIn = 3600)

        assertNull(tokenStore.stationRole, "a token pair is a person's session, never a station's")
        assertEquals("access-1", tokenStore.accessToken)
        assertEquals("refresh-1", tokenStore.refreshToken)
    }

    /** Builds an unsigned JWT whose payload carries only the claims [JwtDecoder] needs. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun jwtExpiringIn(seconds: Long): String {
        val exp = Clock.System.now().epochSeconds + seconds
        val payload = """{"sub":"1","employeeNumber":"E1","exp":$exp}"""
        val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
        return "header.$encoded.signature"
    }
}
