package org.example.project.admin.core.data

import kotlinx.browser.localStorage
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.StorageNamespace
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F52: the back office and the browser till share one `localStorage` (same origin). These run
 * against the real browser storage, standing in for the two apps with two namespaces.
 */
class StorageNamespaceCollisionTest {
    private val admin = LocalStorageKeyValueStore(StorageNamespace.ADMIN)
    private val till = LocalStorageKeyValueStore(StorageNamespace.POS)

    @BeforeTest
    @AfterTest
    fun wipe() {
        admin.clear()
        till.clear()
        localStorage.removeItem(UNRELATED_KEY)
    }

    @Test
    fun theStoreWritesUnderItsNamespacePrefix() {
        admin.setString("jwt_access_token", "a")

        assertEquals("a", localStorage.getItem("admin:jwt_access_token"))
        assertNull(localStorage.getItem("jwt_access_token"))
        assertEquals(mapOf("jwt_access_token" to "a"), admin.getAll())
    }

    @Test
    fun aTillLoginDoesNotOverwriteTheBackOfficeSession() {
        val adminTokens = TokenStore(admin)
        val tillTokens = TokenStore(till)
        adminTokens.storeTokens("admin-access", "admin-refresh", expiresIn = 900)

        tillTokens.storeTokens("till-access", "till-refresh", expiresIn = 900)

        assertEquals("admin-access", adminTokens.accessToken)
        assertEquals("admin-refresh", adminTokens.refreshToken)
        assertEquals("till-access", tillTokens.accessToken)
    }

    @Test
    fun anUnpairedTillClearingItsSessionLeavesTheBackOfficeSignedIn() {
        val adminTokens = TokenStore(admin)
        adminTokens.storeTokens("admin-access", "admin-refresh", expiresIn = 900)
        adminTokens.role = "ADMIN"

        TokenStore(till).clear()
        till.clear()

        assertEquals("admin-access", adminTokens.accessToken)
        assertEquals("ADMIN", adminTokens.role)
    }

    @Test
    fun clearIsScopedToTheNamespace() {
        localStorage.setItem(UNRELATED_KEY, "keep")
        admin.setString("x", "1")
        till.setString("x", "2")

        admin.clear()

        assertNull(admin.getString("x"))
        assertEquals("2", till.getString("x"))
        assertEquals("keep", localStorage.getItem(UNRELATED_KEY))
    }

    private companion object {
        // Stands in for admin_device_id / pos_preview_device_id, which live outside every namespace.
        const val UNRELATED_KEY = "f52_test_unrelated_device_id"
    }
}
