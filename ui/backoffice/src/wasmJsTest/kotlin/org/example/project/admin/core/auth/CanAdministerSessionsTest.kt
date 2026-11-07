package org.example.project.admin.core.auth

import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.core.auth.TokenStore
import kotlin.test.Test
import kotlin.test.assertEquals

class CanAdministerSessionsTest {
    // LocalStorageKeyValueStore is the real wasm store backed by browser localStorage; the existing
    // TransportErrorMappingTest uses it the same way. Each case writes `role` before reading, so
    // values left over from a previous case cannot leak.
    // Clears `capabilities` as well as setting `role`. LocalStorageKeyValueStore is real browser
    // localStorage shared across every test in the run, and since the capability track landed there
    // are THREE keys that decide a gate — a case that writes only one inherits the others from
    // whichever test ran before it. That is not hypothetical: it broke this file the first time the
    // suite ran as a batch rather than alone.
    private fun storeWithRole(role: String?): TokenStore =
        TokenStore(LocalStorageKeyValueStore()).apply {
            this.role = role
            this.capabilities = null
        }

    @Test
    fun admin_mayAdministerSessions() {
        assertEquals(true, storeWithRole("ADMIN").canAdministerSessions)
    }

    @Test
    fun manager_mayNotAdministerSessions() {
        assertEquals(false, storeWithRole("MANAGER").canAdministerSessions)
    }

    @Test
    fun waiter_mayNotAdministerSessions() {
        assertEquals(false, storeWithRole("WAITER").canAdministerSessions)
    }

    @Test
    fun missingRole_mayNotAdministerSessions() {
        assertEquals(false, storeWithRole(null).canAdministerSessions)
    }
}
