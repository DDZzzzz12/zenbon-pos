package org.example.project.ui.auth

import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.testutils.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStateTest {
    private fun tokenStore() = TokenStore(FakeKeyValueStore())

    @Test
    fun wipe_policy_clears_tokens_and_starts_unauthenticated_even_when_a_session_was_persisted() {
        val store = tokenStore()
        store.storeTokens("access-1", "refresh-1", expiresIn = 3600)
        store.role = "MANAGER"

        val session = SessionState(store, StartupPolicy.WIPE)

        assertEquals(SessionStatus.UNAUTHENTICATED, session.status)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertNull(store.role)
    }

    @Test
    fun markLoggedIn_moves_to_authenticated() {
        val session = SessionState(tokenStore(), StartupPolicy.RESUME_VIA_REFRESH_COOKIE)

        session.markLoggedIn()

        assertEquals(SessionStatus.AUTHENTICATED, session.status)
    }

    @Test
    fun logout_clears_the_store_and_moves_to_unauthenticated() {
        val store = tokenStore()
        store.storeTokens("access-1", "refresh-1", expiresIn = 3600)
        val session = SessionState(store, StartupPolicy.RESUME_VIA_REFRESH_COOKIE)
        session.markLoggedIn()

        session.logout()

        assertEquals(SessionStatus.UNAUTHENTICATED, session.status)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun logout_is_idempotent_so_a_refresh_storm_cannot_compound() {
        val store = tokenStore()
        store.storeTokens("access-1", "refresh-1", expiresIn = 3600)
        val session = SessionState(store, StartupPolicy.RESUME_VIA_REFRESH_COOKIE)
        session.markLoggedIn()

        session.logout()
        // A second token pair arriving after sign-out must not be wiped by a stale
        // in-flight logout — the no-op guard is what makes concurrent 401s safe.
        store.storeTokens("access-2", "refresh-2", expiresIn = 3600)
        session.logout()

        assertEquals(SessionStatus.UNAUTHENTICATED, session.status)
        assertEquals("access-2", store.accessToken)
    }

    @Test
    fun resume_via_refresh_cookie_starts_checking_even_with_an_empty_store() {
        val session = SessionState(tokenStore(), StartupPolicy.RESUME_VIA_REFRESH_COOKIE)

        assertEquals(SessionStatus.CHECKING, session.status)
    }
}
