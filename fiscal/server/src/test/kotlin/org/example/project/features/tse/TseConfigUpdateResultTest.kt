package org.example.project.features.tse

import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.data.service.legacyApplyStatus
import org.example.project.features.tse.data.service.mapInitResult
import org.example.project.features.tse.domain.model.TseInitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase E (Task E4): the TseInitResult -> TseConfigUpdateResultDto mapping must surface the
 * mode-change outcome honestly:
 *  - operational mode change reports OPERATIONAL,
 *  - an unreachable device is NOT classified as an auth failure (no PUK hint, no auto-retry framing),
 *  - an auth failure reports AUTH_FAILED, sets mayBeLocked, and hints the PUK unblock flow.
 */
class TseConfigUpdateResultTest {
    private val baseConfig =
        TseConfigDto.TseConfig(
            mode = TseMode.NORMAL,
            timeAdminPin = "12345",
        )

    @Test
    fun `operational init surfaces OPERATIONAL`() {
        val dto = mapInitResult(baseConfig, TseInitResult.Operational)
        assertEquals(TseConfigDto.TseModeApplyStatus.OPERATIONAL, dto.applyStatus)
        assertFalse(dto.mayBeLocked)
        // The PIN itself is blanked; only whether one is stored is echoed.
        assertEquals(baseConfig.copy(timeAdminPin = "", timeAdminPinConfigured = true), dto.config)
    }

    @Test
    fun `the echoed config never carries the TimeAdmin PIN`() {
        // T9-14: credentials are never returned by the API.
        val dto = mapInitResult(baseConfig.copy(timeAdminPin = "80417"), TseInitResult.Operational)
        assertEquals("", dto.config.timeAdminPin)
    }

    @Test
    fun `unreachable init surfaces UNREACHABLE and is not an auth failure`() {
        val dto = mapInitResult(baseConfig, TseInitResult.Unreachable("hardware not detected"))
        assertEquals(TseConfigDto.TseModeApplyStatus.UNREACHABLE, dto.applyStatus)
        assertFalse(dto.mayBeLocked)
        assertTrue(dto.message.contains("offline"))
    }

    @Test
    fun `auth failure with remaining attempts surfaces AUTH_FAILED with count, not locked`() {
        val dto =
            mapInitResult(
                baseConfig,
                TseInitResult.AuthFailed("login failed", remainingRetries = 2, locked = false),
            )
        assertEquals(TseConfigDto.TseModeApplyStatus.AUTH_FAILED, dto.applyStatus)
        assertEquals(2, dto.remainingRetries)
        assertFalse(dto.mayBeLocked)
        assertTrue(dto.message.contains("2"), "auth-failure message should include the remaining attempt count")
        assertTrue(
            dto.message.contains("NOT be retried", ignoreCase = true),
            "auth-failure message should state login is not auto-retried",
        )
    }

    @Test
    fun `auth failure locked surfaces AUTH_FAILED with mayBeLocked and a PUK hint, no auto-retry`() {
        val dto =
            mapInitResult(
                baseConfig,
                TseInitResult.AuthFailed("pin blocked", remainingRetries = 0, locked = true),
            )
        assertEquals(TseConfigDto.TseModeApplyStatus.AUTH_FAILED, dto.applyStatus)
        assertTrue(dto.mayBeLocked)
        assertTrue(dto.message.contains("PUK"), "auth-failure message should hint the PUK unblock flow")
        assertTrue(
            dto.message.contains("NOT be retried", ignoreCase = true),
            "auth-failure message should state login is not auto-retried",
        )
    }

    @Test
    fun `not commissioned surfaces NOT_COMMISSIONED, never AUTH_FAILED, and no PIN or PUK hint`() {
        val dto = mapInitResult(baseConfig, TseInitResult.NotCommissioned)

        assertEquals(TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED, dto.applyOutcome)
        assertEquals(TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED, dto.effectiveApplyStatus)
        assertFalse(dto.mayBeLocked)
        assertEquals(null, dto.remainingRetries)
        assertFalse(dto.message.contains("PIN"), "an uncommissioned TSE is not a PIN problem: ${dto.message}")
        assertFalse(dto.message.contains("PUK"), "an uncommissioned TSE is not a PUK problem: ${dto.message}")
    }

    @Test
    fun `the legacy applyStatus carries only values a client built before NOT_COMMISSIONED can decode`() {
        val dto = mapInitResult(baseConfig, TseInitResult.NotCommissioned)

        assertEquals(TseConfigDto.TseModeApplyStatus.UNREACHABLE, dto.applyStatus)
        val legacy =
            TseConfigDto.TseModeApplyStatus.entries.filter { it != TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED }
        TseConfigDto.TseModeApplyStatus.entries.forEach { status ->
            assertTrue(legacyApplyStatus(status) in legacy, "$status must not reach applyStatus as-is")
        }
    }

    @Test
    fun `an older client decodes a NOT_COMMISSIONED result`() {
        val json =
            kotlinx.serialization.json.Json.encodeToString(
                TseConfigDto.TseConfigUpdateResultDto.serializer(),
                mapInitResult(baseConfig, TseInitResult.NotCommissioned),
            )
        // What a pre-NOT_COMMISSIONED client sees: it knows no applyOutcome key, and the
        // repo-wide client Json ignores unknown keys. Its applyStatus must be a known value.
        val applyStatus = Regex("\"applyStatus\":\"([A-Z_]+)\"").find(json)?.groupValues?.get(1)
        assertEquals("UNREACHABLE", applyStatus, json)
    }

    @Test
    fun `auth failure on a commissioned TSE keeps AUTH_FAILED in both fields`() {
        val dto = mapInitResult(baseConfig, TseInitResult.AuthFailed("login failed", remainingRetries = 2, locked = false))

        assertEquals(TseConfigDto.TseModeApplyStatus.AUTH_FAILED, dto.applyStatus)
        assertEquals(TseConfigDto.TseModeApplyStatus.AUTH_FAILED, dto.applyOutcome)
    }
}
