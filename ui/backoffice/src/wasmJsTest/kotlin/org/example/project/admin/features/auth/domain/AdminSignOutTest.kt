package org.example.project.admin.features.auth.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.data.InMemoryKeyValueStore
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AdminSignOutTest {
    private val tokenStore = TokenStore(InMemoryKeyValueStore())
    private val session =
        SessionState(tokenStore, StartupPolicy.RESUME_VIA_REFRESH_COOKIE).apply {
            tokenStore.storeTokens(accessToken = "access", refreshToken = null, expiresIn = 900)
            tokenStore.role = "ADMIN"
            markLoggedIn()
        }

    @Test
    fun server_logout_runs_while_the_token_is_still_stored_then_session_ends() =
        runTest {
            var tokenAtServerCall: String? = null
            var statusAtServerCall: SessionStatus? = null
            val api =
                FakeAuthApiClient {
                    tokenAtServerCall = tokenStore.accessToken
                    statusAtServerCall = session.status
                }

            AdminSignOut(api, session)()

            assertEquals(1, api.logoutCalls)
            // The bearer the server call authenticates with must still be there when it runs.
            assertEquals("access", tokenAtServerCall)
            assertEquals(SessionStatus.AUTHENTICATED, statusAtServerCall)
            assertEquals(SessionStatus.UNAUTHENTICATED, session.status)
            assertNull(tokenStore.accessToken)
            assertNull(tokenStore.role)
        }

    @Test
    fun a_failing_server_logout_still_signs_out_locally() =
        runTest {
            val api = FakeAuthApiClient { throw RuntimeException("server unreachable") }

            AdminSignOut(api, session)()

            assertEquals(1, api.logoutCalls)
            assertEquals(SessionStatus.UNAUTHENTICATED, session.status)
            assertNull(tokenStore.accessToken)
        }

    @Test
    fun a_hanging_server_logout_times_out_and_signs_out_locally() =
        runTest {
            val api = FakeAuthApiClient { awaitCancellation() }

            AdminSignOut(api, session)()

            assertEquals(1, api.logoutCalls)
            // Virtual time: the timeout fired, rather than the call returning on its own.
            assertTrue(currentTime >= 5_000, "expected the 5 s timeout to elapse, was $currentTime ms")
            assertEquals(SessionStatus.UNAUTHENTICATED, session.status)
            assertNull(tokenStore.accessToken)
        }
}
