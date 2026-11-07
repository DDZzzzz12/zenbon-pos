package org.example.project.admin.features.auth

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.auth.presentation.SessionEndedNotice
import org.example.project.admin.features.auth.presentation.loginErrorMessageKey
import org.example.project.core.auth.SessionStatus
import org.example.project.core.error.PosErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionEndedNoticeTest {
    @Test
    fun `a forced end while signed in raises the notice`() {
        val notice = SessionEndedNotice()
        notice.markEndedFrom(SessionStatus.AUTHENTICATED)
        assertTrue(notice.pending)
    }

    @Test
    fun `a failed startup resume does not raise the notice`() {
        // Every anonymous page load starts in CHECKING and its cookie refresh fails with no cookie. The
        // HttpOnly cookie is invisible to the page, so "never signed in" and "session expired" cannot
        // be told apart; saying "expired" to someone who never signed in is the worse error.
        val notice = SessionEndedNotice()
        notice.markEndedFrom(SessionStatus.CHECKING)
        assertFalse(notice.pending)
    }

    @Test
    fun `a rejection while already signed out does not raise it`() {
        val notice = SessionEndedNotice()
        notice.markEndedFrom(SessionStatus.UNAUTHENTICATED)
        assertFalse(notice.pending)
    }

    @Test
    fun `a user-initiated sign-out never marks it, and the next attempt clears it`() {
        val notice = SessionEndedNotice()
        assertFalse(notice.pending)
        notice.markEnded()
        notice.clear()
        assertFalse(notice.pending)
    }

    @Test
    fun `no permission group gets its own message, any other refusal the credentials one`() {
        assertEquals(AdminStringKeys.LoginErrorNoPermissionGroup, loginErrorMessageKey(PosErrorCode.NO_PERMISSION_GROUP))
        assertEquals(AdminStringKeys.LoginErrorInvalidCredentials, loginErrorMessageKey(PosErrorCode.AUTH_INVALID_CREDENTIALS))
    }
}
