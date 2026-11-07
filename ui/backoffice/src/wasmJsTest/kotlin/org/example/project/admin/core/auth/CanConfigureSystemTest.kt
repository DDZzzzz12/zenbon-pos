package org.example.project.admin.core.auth

import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.core.auth.TokenStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gate is now `CONFIG_WRITE`, the capability the server requires on the configuration PUTs.
 *
 * **The permission leg is gone, because it had already stopped working.** The old gate was
 * `isManagerOrHigher() || EXCEPTION_SYSTEM_CONFIG in jwt.permissions`, and the second half could not
 * be true after Stage 2a retired per-employee grants — the token's permission claim has been empty
 * ever since. Its intent was real and is now expressible properly: a group can confer `CONFIG_WRITE`
 * without making anyone a manager, which is what the dead grant was reaching for.
 *
 * The token is still written in each case because `TokenStore` reads several keys, and a value left
 * by a previous case must not leak into the next. It no longer influences this gate.
 */
class CanConfigureSystemTest {
    @OptIn(ExperimentalEncodingApi::class)
    private fun tokenWith(vararg permissions: String): String {
        val list = permissions.joinToString(",") { "\"$it\"" }
        val payload = """{"sub":"emp-1","employeeNumber":"1001","permissionEnums":[$list]}"""
        // Padding is kept: JwtDecoder re-pads by length % 4, so an already-padded segment is fine.
        return "header.${Base64.UrlSafe.encode(payload.encodeToByteArray())}.signature"
    }

    private fun store(
        role: String?,
        token: String?,
    ): TokenStore =
        TokenStore(LocalStorageKeyValueStore()).apply {
            this.role = role
            this.accessToken = token
            // The third key. localStorage is shared across the whole run, so a case that leaves one
            // of the three unset inherits it from the previous test — which is how a gate under test
            // here starts answering with another file's fixture.
            this.capabilities = null
        }

    @Test
    fun manager_mayConfigureSystem() {
        assertEquals(true, store("MANAGER", tokenWith()).canConfigureSystem)
    }

    @Test
    fun admin_mayConfigureSystem() {
        assertEquals(true, store("ADMIN", tokenWith()).canConfigureSystem)
    }

    @Test
    fun cashierHoldingTheRetiredGrant_mayNotConfigureSystem() {
        // CHANGED, and it is a correction rather than a regression: this asserted a path that has
        // been dead since Stage 2a. Nothing has populated the token's permission claim since, so no
        // cashier has been able to reach system configuration this way regardless of what the test
        // said. The replacement is a group conferring CONFIG_WRITE.
        assertEquals(false, store("CASHIER", tokenWith("EXCEPTION_SYSTEM_CONFIG")).canConfigureSystem)
    }

    @Test
    fun cashierWithoutThePermission_mayNotConfigureSystem() {
        assertEquals(false, store("CASHIER", tokenWith("EXCEPTION_CANCEL_BILL")).canConfigureSystem)
    }

    @Test
    fun malformedToken_mayNotConfigureSystem() {
        assertEquals(false, store("CASHIER", "not-a-jwt").canConfigureSystem)
    }

    @Test
    fun noRoleNoToken_mayNotConfigureSystem() {
        assertEquals(false, store(null, null).canConfigureSystem)
    }

    @Test
    fun onlyAdminMayBrowseServerDirectories() {
        // Unchanged, and worth keeping: the first mapping attempt used SYSTEM_CONFIG here, which
        // looks apt and sits in the MANAGER bundle despite being RESERVED — this case caught the
        // widening. BACKUP_CREATE is what GET /api/v1/backup/directories actually names.
        assertEquals(true, store("ADMIN", tokenWith()).canBrowseServerDirectories)
        assertEquals(false, store("MANAGER", tokenWith("EXCEPTION_SYSTEM_CONFIG")).canBrowseServerDirectories)
    }
}
