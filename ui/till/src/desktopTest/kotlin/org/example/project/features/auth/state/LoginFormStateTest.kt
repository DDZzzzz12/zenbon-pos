package org.example.project.features.auth.state

import org.example.project.features.auth.presentation.state.LoginFormState
import org.example.project.features.auth.presentation.state.RememberedLogin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The remembered credentials load off the UI thread, so they can arrive after the user has started typing. A late
 * pre-fill must never overwrite what the user entered.
 */
class LoginFormStateTest {
    private val remembered = RememberedLogin(identifier = "EMP001", secret = "password123")

    @Test
    fun `pre-fill fills a form the user has not touched`() {
        val form = LoginFormState()

        form.prefill(remembered)

        assertEquals("EMP001", form.identifier)
        assertEquals("password123", form.secret)
        assertTrue(form.rememberMe)
    }

    @Test
    fun `a late pre-fill keeps what the user already typed`() {
        val form = LoginFormState()
        form.onIdentifierChange("EMP009")

        form.prefill(remembered)

        assertEquals("EMP009", form.identifier)
        assertEquals("", form.secret, "a remembered password must not be paired with a user the operator typed")
        assertFalse(form.rememberMe)
    }

    @Test
    fun `toggling remember-me alone also keeps the form from being pre-filled`() {
        val form = LoginFormState()
        form.onRememberMeChange(false)

        form.prefill(remembered)

        assertEquals("", form.identifier)
        assertEquals("", form.secret)
        assertFalse(form.rememberMe)
    }
}
