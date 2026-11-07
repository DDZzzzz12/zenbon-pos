package org.example.project.admin.core.data

import co.touchlab.kermit.Logger
import kotlinx.browser.localStorage
import org.example.project.core.data.database.StorageNamespace

/**
 * One-time move of the back office's pre-namespace `localStorage` entries into [namespace] (F52).
 *
 * Before namespacing, the admin session lived under bare `TokenStore` keys (`jwt_access_token`, …)
 * that the browser till wrote too. Adopting them on the first start after the update keeps an
 * already-signed-in operator signed in instead of bouncing them to the login screen.
 *
 * - **Tokens** are adopted only when the namespace holds no access token yet and the legacy set is
 *   not a station session (a station session can only ever be the till's). The till side adopts
 *   nothing, because the same keys may equally be the back office's.
 * - **`admin.*` preferences** (language, sidebar) were only ever written by the back office, so they
 *   are adopted whenever the namespace lacks them.
 * - Every legacy key in [ADOPTABLE_TOKEN_KEYS] and every `admin.*` key is then deleted, adopted or not.
 *   Other bare keys (`employee_number`, the till's settings) are left alone: they are not ours.
 *
 * A marker key makes the move run once, so a stale tab still on the old build cannot keep feeding
 * bare tokens into the back office after the fact.
 */
class LegacyStorageMigration(
    private val namespace: StorageNamespace = StorageNamespace.ADMIN,
) {
    private val log = Logger.withTag("StorageMigration")

    /** Runs the move unless it already ran; returns the logical keys adopted. */
    fun run(): Set<String> {
        if (localStorage.getItem(namespace.physicalKey(MARKER_KEY)) != null) return emptySet()
        val legacy = rawLocalStorage().filterKeys(::isLegacyAdminKey)
        val adopted = legacy.filterKeys { shouldAdopt(it, legacy) }
        adopted.forEach { (key, value) -> localStorage.setItem(namespace.physicalKey(key), value) }
        legacy.keys.forEach(localStorage::removeItem)
        localStorage.setItem(namespace.physicalKey(MARKER_KEY), "1")
        log.i { "Legacy localStorage migration: adopted ${adopted.size}, removed ${legacy.size}" }
        return adopted.keys
    }

    private fun shouldAdopt(
        key: String,
        legacy: Map<String, String>,
    ): Boolean {
        if (localStorage.getItem(namespace.physicalKey(key)) != null) return false
        if (key !in ADOPTABLE_TOKEN_KEYS) return true
        val namespaceHasSession = localStorage.getItem(namespace.physicalKey(ACCESS_TOKEN_KEY)) != null
        val legacyIsStation = !legacy[STATION_ROLE_KEY].isNullOrEmpty()
        return !namespaceHasSession && !legacyIsStation
    }

    private fun isLegacyAdminKey(physicalKey: String): Boolean = physicalKey in ADOPTABLE_TOKEN_KEYS || physicalKey.startsWith(LEGACY_ADMIN_PREF_PREFIX)

    companion object {
        /** Stored inside the namespace, so it can never be confused with a legacy key. */
        const val MARKER_KEY = "storage.legacy_migrated"

        private const val ACCESS_TOKEN_KEY = "jwt_access_token"
        private const val STATION_ROLE_KEY = "jwt_station_role"
        private const val LEGACY_ADMIN_PREF_PREFIX = "admin."

        /** The bare keys `TokenStore` wrote before namespacing. Must match its private constants. */
        val ADOPTABLE_TOKEN_KEYS =
            setOf(
                ACCESS_TOKEN_KEY,
                "jwt_refresh_token",
                "jwt_expires_at",
                "jwt_role",
                "jwt_capabilities",
                "jwt_permission_group_code",
                "jwt_permission_group_name",
                STATION_ROLE_KEY,
            )
    }
}
