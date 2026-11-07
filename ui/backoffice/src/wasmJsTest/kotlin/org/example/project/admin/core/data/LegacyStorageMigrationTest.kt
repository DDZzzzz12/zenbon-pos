package org.example.project.admin.core.data

import kotlinx.browser.localStorage
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.StorageNamespace
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyStorageMigrationTest {
    private val admin = LocalStorageKeyValueStore(StorageNamespace.ADMIN)
    private val till = LocalStorageKeyValueStore(StorageNamespace.POS)

    @BeforeTest
    @AfterTest
    fun wipe() {
        admin.clear()
        till.clear()
        (LegacyStorageMigration.ADOPTABLE_TOKEN_KEYS + LEGACY_PREFS.keys + "employee_number")
            .forEach(localStorage::removeItem)
    }

    @Test
    fun aSignedInAdminStaysSignedInAcrossTheUpdate() {
        seedLegacySession()
        LEGACY_PREFS.forEach { (key, value) -> localStorage.setItem(key, value) }

        LegacyStorageMigration().run()

        val tokens = TokenStore(admin)
        assertEquals("legacy-access", tokens.accessToken)
        assertEquals("legacy-refresh", tokens.refreshToken)
        assertEquals("ADMIN", tokens.role)
        assertEquals(setOf("CATALOG_MANAGE", "TSE_ADMIN"), tokens.capabilities)
        assertEquals("de", admin.getString("admin.language"))
        assertEquals("true", admin.getString("admin.sidebar.group.master_data"))
    }

    @Test
    fun theLegacyKeysAreDeletedAndTheTillSeesNothing() {
        seedLegacySession()
        localStorage.setItem("employee_number", "42")

        LegacyStorageMigration().run()

        LegacyStorageMigration.ADOPTABLE_TOKEN_KEYS.forEach { assertNull(localStorage.getItem(it), it) }
        assertNull(TokenStore(till).accessToken)
        // Not ours to adopt or delete — it is the till's legacy key.
        assertEquals("42", localStorage.getItem("employee_number"))
        assertNull(admin.getString("employee_number"))
    }

    @Test
    fun theMigrationRunsOnlyOnce() {
        seedLegacySession()
        LegacyStorageMigration().run()
        TokenStore(admin).clear()

        // A stale tab on the old build writes bare tokens again after the move.
        localStorage.setItem("jwt_access_token", "stale-tab-access")
        val adoptedOnSecondRun = LegacyStorageMigration().run()

        assertTrue(adoptedOnSecondRun.isEmpty())
        assertNull(TokenStore(admin).accessToken)
    }

    @Test
    fun anExistingNamespacedSessionIsNotOverwrittenByLegacyTokens() {
        TokenStore(admin).storeTokens("current-access", "current-refresh", expiresIn = 900)
        seedLegacySession()

        LegacyStorageMigration().run()

        assertEquals("current-access", TokenStore(admin).accessToken)
        assertEquals("current-refresh", TokenStore(admin).refreshToken)
        assertNull(localStorage.getItem("jwt_access_token"))
    }

    @Test
    fun aLegacyStationSessionIsNeverAdopted() {
        seedLegacySession()
        localStorage.setItem("jwt_station_role", "ORDER_STATION")

        LegacyStorageMigration().run()

        assertNull(TokenStore(admin).accessToken)
        assertNull(localStorage.getItem("jwt_access_token"))
    }

    private fun seedLegacySession() {
        localStorage.setItem("jwt_access_token", "legacy-access")
        localStorage.setItem("jwt_refresh_token", "legacy-refresh")
        localStorage.setItem("jwt_expires_at", "4102444800")
        localStorage.setItem("jwt_role", "ADMIN")
        localStorage.setItem("jwt_capabilities", "CATALOG_MANAGE,TSE_ADMIN")
    }

    private companion object {
        val LEGACY_PREFS =
            mapOf(
                "admin.language" to "de",
                "admin.sidebar.group.master_data" to "true",
            )
    }
}
