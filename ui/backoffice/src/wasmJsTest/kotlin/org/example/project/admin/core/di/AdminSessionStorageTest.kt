package org.example.project.admin.core.di

import kotlinx.browser.localStorage
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.KeyValueStore
import org.example.project.ui.auth.SessionState
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the storage property of the admin session at the DI level, against real browser localStorage:
 * no token ever lands in localStorage, the tokens an earlier build left there are wiped, and nothing
 * else the panel stores is.
 *
 * Resolved through [adminModule] itself rather than by constructing the pieces, because the property
 * is a *binding* decision — swapping `InMemoryKeyValueStore` back to the localStorage store compiles
 * perfectly and silently re-exposes the access token to every script on the origin.
 *
 * localStorage is shared across the whole test run, so every seeded key is restored afterwards.
 */
class AdminSessionStorageTest {
    @Test
    fun resolving_the_key_value_store_wipes_legacy_tokens_and_keeps_everything_else() =
        withSeededStorage { koin ->
            koin.koin.get<KeyValueStore>()

            assertNull(localStorage.getItem(LEGACY_REFRESH_KEY))
            assertNull(localStorage.getItem(LEGACY_ACCESS_KEY))
            assertEquals(LANGUAGE_VALUE, localStorage.getItem(LANGUAGE_KEY))
            assertEquals(DEVICE_ID_VALUE, localStorage.getItem(DEVICE_ID_KEY))
        }

    @Test
    fun the_token_store_never_writes_to_local_storage() =
        withSeededStorage { koin ->
            val tokenStore = koin.koin.get<TokenStore>()

            tokenStore.accessToken = "x"
            tokenStore.role = "ADMIN"

            assertEquals("x", tokenStore.accessToken)
            // The seeded legacy value is still there, untouched: the token store reads and writes
            // somewhere else entirely.
            assertEquals(LEGACY_ACCESS_VALUE, localStorage.getItem(LEGACY_ACCESS_KEY))
            assertNull(localStorage.getItem(ROLE_KEY))
        }

    @Test
    fun a_wiped_and_token_written_store_leaves_no_access_token_in_local_storage() =
        withSeededStorage { koin ->
            koin.koin.get<KeyValueStore>()
            koin.koin.get<TokenStore>().accessToken = "x"

            assertNull(localStorage.getItem(LEGACY_ACCESS_KEY))
        }

    @Test
    fun the_session_starts_by_checking_the_refresh_cookie() =
        withSeededStorage { koin ->
            assertEquals(SessionStatus.CHECKING, koin.koin.get<SessionState>().status)
        }

    private fun withSeededStorage(block: (KoinApplication) -> Unit) {
        val seeded =
            mapOf(
                LEGACY_REFRESH_KEY to "legacy-refresh",
                LEGACY_ACCESS_KEY to LEGACY_ACCESS_VALUE,
                LANGUAGE_KEY to LANGUAGE_VALUE,
                DEVICE_ID_KEY to DEVICE_ID_VALUE,
            )
        val tracked = seeded.keys + ROLE_KEY
        val originals = tracked.associateWith { localStorage.getItem(it) }
        localStorage.removeItem(ROLE_KEY)
        seeded.forEach { (key, value) -> localStorage.setItem(key, value) }

        // `koinApplication`, not `startKoin`: a private instance, so nothing leaks into the global
        // context another test might read. Definitions resolve lazily, so no HTTP client is built.
        val app = koinApplication { modules(adminModule) }
        try {
            block(app)
        } finally {
            app.close()
            originals.forEach { (key, value) ->
                if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value)
            }
        }
    }

    private companion object {
        // The physical keys an earlier build wrote: TokenStore's logical keys under the admin namespace.
        const val LEGACY_REFRESH_KEY = "admin:jwt_refresh_token"
        const val LEGACY_ACCESS_KEY = "admin:jwt_access_token"
        const val LEGACY_ACCESS_VALUE = "legacy-access"
        const val ROLE_KEY = "admin:jwt_role"

        // AdminApp's private KEY_LANGUAGE and DeviceInfoProvider.wasmJs's ADMIN_STORAGE_KEY.
        const val LANGUAGE_KEY = "admin.language"
        const val LANGUAGE_VALUE = "en"
        const val DEVICE_ID_KEY = "admin_device_id"
        const val DEVICE_ID_VALUE = "test-device-id"
    }
}
