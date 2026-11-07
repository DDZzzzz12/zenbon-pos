package org.example.project.core.data.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageNamespaceTest {
    @Test
    fun physicalKeysOfTheTwoWasmAppsNeverCollide() {
        val key = "jwt_access_token"

        assertNotEquals(StorageNamespace.ADMIN.physicalKey(key), StorageNamespace.POS.physicalKey(key))
        assertEquals("admin:jwt_access_token", StorageNamespace.ADMIN.physicalKey(key))
        assertEquals("pos:jwt_access_token", StorageNamespace.POS.physicalKey(key))
    }

    @Test
    fun aNamespaceSeesOnlyItsOwnEntriesUnderTheirLogicalKeys() {
        val storage =
            mapOf(
                "admin:jwt_access_token" to "admin-token",
                "pos:jwt_access_token" to "till-token",
                "admin_device_id" to "admin-abc",
                "jwt_refresh_token" to "legacy",
            )

        assertEquals(mapOf("jwt_access_token" to "admin-token"), StorageNamespace.ADMIN.viewOf(storage))
        assertEquals(mapOf("jwt_access_token" to "till-token"), StorageNamespace.POS.viewOf(storage))
    }

    @Test
    fun aLegacyDottedAdminKeyIsNotMistakenForANamespacedOne() {
        // adminApp wrote `admin.language` before namespacing; it must not read back as logical `language`.
        assertNull(StorageNamespace.ADMIN.logicalKeyOrNull("admin.language"))
        assertFalse(StorageNamespace.ADMIN.owns("admin.language"))
        assertTrue(StorageNamespace.ADMIN.owns("admin:admin.language"))
    }

    @Test
    fun anEmptyPrefixIsRefused() {
        assertFailsWith<IllegalArgumentException> { StorageNamespace("") }
        Unit
    }
}
