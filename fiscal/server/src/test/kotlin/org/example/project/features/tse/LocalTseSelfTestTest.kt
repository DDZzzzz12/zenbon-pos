package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.provider.local.LocalTseSelfTest
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The self test the power-loss recovery and the manual "Selbsttest" both run.
 *
 * Found on a real Swissbit TSE 1.x (2026-10-10): neither passed a client, and nothing had set one
 * on the driver after the stick was plugged in while the server ran, so both failed with "no client
 * ID is configured" until the server was restarted. A TSE 1.x self test needs a *registered* client;
 * TSE 2.0 needs none.
 */
@DisplayName("Local TSE self test — run for the registered register")
class LocalTseSelfTestTest {
    private class Harness(
        client: String?,
        tseV2: Boolean = false,
    ) {
        val ran = mutableListOf<Pair<String?, Boolean>>()
        val selfTest =
            LocalTseSelfTest(
                resolveClient = { client },
                isTseV2 = { tseV2 },
                execute = { clientId, force ->
                    ran += clientId to force
                    TseDto.SelfTestResultDto(success = true, message = "ok", forced = force, durationMs = 1L)
                },
            )
    }

    @Test
    fun `TSE 1x runs against the registered terminal`() {
        val h = Harness(client = "TER-003")

        runBlocking { h.selfTest.run(force = false) }

        assertEquals(listOf<Pair<String?, Boolean>>("TER-003" to false), h.ran)
    }

    @Test
    fun `TSE 1x with no registered terminal refuses before touching the device`() {
        val h = Harness(client = null)

        val ex = assertFailsWith<PosException> { runBlocking { h.selfTest.run(force = false) } }

        assertTrue(h.ran.isEmpty(), "no device call without a registered client")
        assertEquals(PosErrorCode.TSE_NOT_OPERATIONAL, ex.code)
        assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
        assertTrue("register" in ex.message.orEmpty(), "the operator must learn what to do: ${ex.message}")
    }

    @Test
    fun `TSE 2_0 runs without a client`() {
        val h = Harness(client = null, tseV2 = true)

        runBlocking { h.selfTest.run(force = false) }

        assertEquals(listOf<Pair<String?, Boolean>>(null to false), h.ran, "TSE 2.0 takes the no-argument overload")
    }

    @Test
    fun `an explicitly named client wins over the resolved one`() {
        val h = Harness(client = "TER-003")

        runBlocking { h.selfTest.run(force = true, explicitClient = "TER-007") }

        assertEquals(listOf<Pair<String?, Boolean>>("TER-007" to true), h.ran)
    }
}
