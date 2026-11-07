package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.service.TseAdminUnlock
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2E round 4, R4-1: `POST /api/tse/admin/unlock` answered `unlocked: true` for any PIN, so a typo
 * surfaced only at the next admin action — as another failed Admin login on the card.
 */
@DisplayName("TSE admin unlock — only a PIN the device accepts opens a sitting")
class TseAdminUnlockTest {
    @AfterEach
    fun teardown() = TseAdminSession.clear()

    private fun provider(listClients: () -> Any): TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listClients" -> listClients()
                "toString" -> "FakeTseAdminProvider"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
            }
        } as TseAdminProvider

    private fun cardAnswer(error: String): Nothing =
        throw PosException(
            code = PosErrorCode.TSE_OPERATION_REJECTED,
            httpStatus = HttpStatusCode.UnprocessableEntity,
            message = "TSE rejected the operation: $error",
        )

    @Test
    fun `a PIN the device accepts opens the sitting`(): Unit =
        runBlocking {
            TseAdminUnlock(provider { TseDto.ClientListResultDto(true, listOf("TER-001"), 1) }).unlock("54321")

            assertTrue(TseAdminSession.isUnlocked())
        }

    @Test
    fun `a PIN the device refuses is a 422 and leaves administration locked`(): Unit =
        runBlocking {
            val refusal =
                assertFailsWith<PosException> {
                    TseAdminUnlock(provider { cardAnswer("WORM_ERROR_AUTHENTICATION_FAILED") }).unlock("00000")
                }

            assertEquals(PosErrorCode.TSE_INVALID_PIN, refusal.code)
            assertEquals(HttpStatusCode.UnprocessableEntity, refusal.httpStatus)
            assertFalse(TseAdminSession.isUnlocked(), "a refused PIN must not stay in the session")
        }

    @Test
    fun `a blocked PIN says the PUK is needed`(): Unit =
        runBlocking {
            val refusal =
                assertFailsWith<PosException> {
                    TseAdminUnlock(provider { cardAnswer("WORM_ERROR_AUTHENTICATION_PIN_BLOCKED") }).unlock("00000")
                }

            assertTrue(refusal.message.orEmpty().contains("PUK"), refusal.message)
            assertFalse(TseAdminSession.isUnlocked())
        }

    /** Not commissioned, unreachable, no SDK: nothing was learned about the PIN. */
    @Test
    fun `a failure that says nothing about the PIN keeps the sitting`(): Unit =
        runBlocking {
            TseAdminUnlock(provider { cardAnswer("WORM_ERROR_TSE_NOT_INITIALIZED") }).unlock("54321")

            assertTrue(TseAdminSession.isUnlocked())
        }
}
