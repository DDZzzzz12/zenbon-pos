package org.example.project.core.auth

import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeTokenStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The role load is split so the sealed access token is opened off the UI thread while the StateFlow writes stay on the
 * caller's context: reading the snapshot publishes nothing, and applying it publishes everything. A logout that lands
 * between the two is kept out by `SessionRoleLoader`, which applies only while the session is still authenticated.
 */
class PermissionManagerTest {
    private val db = FakeLocalDatabaseManager.create()
    private val tokenStore = FakeTokenStore.create()
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
    }

    @Test
    fun reading_the_role_snapshot_publishes_nothing() {
        signInAnAdmin()

        val snapshot = manager.readRoleSnapshot()

        assertNotNull(snapshot)
        assertEquals(EmployeeRole.ADMIN, snapshot.role)
        assertNull(manager.currentRole.value, "reading must not publish the role")
        assertNull(manager.effectiveRole.value)
        assertNull(manager.currentEmployeeNumber.value)
        assertFalse(manager.accessPolicy.value.allows(Capability.BILL_READ), "reading must not publish the capabilities")
    }

    @Test
    fun applying_the_snapshot_publishes_the_role_and_capabilities() {
        signInAnAdmin()
        val snapshot = assertNotNull(manager.readRoleSnapshot())

        manager.applyRoleSnapshot(snapshot)

        assertEquals(EmployeeRole.ADMIN, manager.currentRole.value)
        assertEquals(EmployeeRole.ADMIN, manager.effectiveRole.value)
        assertEquals("42", manager.currentEmployeeNumber.value)
        assertTrue(manager.accessPolicy.value.allows(Capability.BILL_READ))
    }

    @Test
    fun without_an_access_token_there_is_no_snapshot() {
        signInAnAdmin()
        tokenStore.clear()

        assertNull(manager.readRoleSnapshot())
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
