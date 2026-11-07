package org.example.project.core.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The role is read off the UI thread and published back on it. A logout on the UI thread can land in between, and the
 * effect that started the read is not cancelled until the next recomposition — so the publish has to check the session.
 */
class SessionRoleLoaderTest {
    private val db = FakeLocalDatabaseManager.create()
    private val tokenStore = FakeTokenStore.create()

    // Built before anything is stored: StartupPolicy.WIPE clears the token store.
    private val session = SessionState(tokenStore, StartupPolicy.WIPE)
    private val manager = PermissionManager(db, tokenStore)

    private fun signInAnAdmin() {
        db.setUserInfo(
            EmployeeInfoDto.EmployeeInfo(
                id = "emp-1",
                username = "anna",
                employeeNumber = "42",
                role = EmployeeRole.ADMIN.name,
                trainingMode = false,
                capabilities = setOf("BILL_READ"),
            ),
        )
        tokenStore.accessToken = ADMIN_JWT
        session.markLoggedIn()
    }

    /** What `LoginViewModel.clearLocalSession` does, in its order. */
    private fun signOut() {
        tokenStore.clear()
        session.logout()
        manager.clearRole()
    }

    @Test
    fun the_role_of_a_signed_in_session_is_published() =
        runTest {
            signInAnAdmin()

            val published = SessionRoleLoader(manager, session, StandardTestDispatcher(testScheduler)).load()

            assertTrue(published)
            assertEquals(EmployeeRole.ADMIN, manager.currentRole.value)
            assertTrue(manager.accessPolicy.value.allows(Capability.BILL_READ))
        }

    @Test
    fun a_role_read_that_returns_after_a_sign_out_publishes_nothing() =
        runTest {
            signInAnAdmin()
            var tokenStoredAtSignOut = false
            val io =
                ThenRun(StandardTestDispatcher(testScheduler)) {
                    tokenStoredAtSignOut = tokenStore.accessToken != null
                    signOut()
                }

            val published = SessionRoleLoader(manager, session, io).load()

            assertTrue(tokenStoredAtSignOut, "precondition: the read finished while the session still held its token")
            assertFalse(published)
            assertNull(manager.currentRole.value, "the signed-out principal's role must not be published back")
            assertNull(manager.effectiveRole.value)
            assertNull(manager.currentEmployeeNumber.value)
            assertFalse(manager.accessPolicy.value.allows(Capability.BILL_READ))
        }

    /**
     * Runs each task on [delegate], then [afterTask] — after the read has returned, and before the caller resumes with
     * its result, which is dispatched back to the test's own dispatcher and waits its turn.
     */
    private class ThenRun(
        private val delegate: CoroutineDispatcher,
        private val afterTask: () -> Unit,
    ) : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            delegate.dispatch(
                context,
                Runnable {
                    block.run()
                    afterTask()
                },
            )
        }
    }

    private companion object {
        /** An unsigned but realistically shaped token, so the decode branch is actually exercised. */
        @OptIn(ExperimentalEncodingApi::class)
        val ADMIN_JWT: String =
            run {
                val payload = """{"sub":"emp-1","employeeNumber":"42","role":"ADMIN"}"""
                val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
                "eyJhbGciOiJub25lIn0.$encoded.sig"
            }
    }
}
