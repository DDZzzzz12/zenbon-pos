package org.example.project.core.data.database.secret

import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeSecretCipher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretProtectingKeyValueStoreTest {
    // Returns "" exactly as written, like SqlDelightKeyValueStore does on the real till.
    private val disk = FakeKeyValueStore(emptyReadsAsNull = false)
    private val cipher = FakeSecretCipher()
    private val store = SecretProtectingKeyValueStore(disk, cipher)

    @AfterTest
    fun resetGlobalDeviceToken() {
        // LocalDatabaseManager mirrors the device token into this process-wide holder.
        DeviceTokenProvider.reset()
    }

    @Test
    fun a_secret_is_sealed_on_disk_and_opened_on_read() {
        store.setString("device_token", "tok-123")

        assertTrue(SealedSecret.isSealed(disk.getString("device_token")!!))
        assertEquals("tok-123", store.getString("device_token"))
    }

    @Test
    fun a_non_secret_is_stored_as_is() {
        store.setString("api_base_url", "https://kasse.example.de/")

        assertEquals("https://kasse.example.de/", disk.getString("api_base_url"))
    }

    @Test
    fun plaintext_left_by_an_older_build_is_returned_and_resealed() {
        disk.setString("password", "hunter2")

        assertEquals("hunter2", store.getString("password"))
        assertTrue(SealedSecret.isSealed(disk.getString("password")!!), "the plaintext must leave the disk on first read")
        assertEquals("hunter2", store.getString("password"), "the re-sealed value must open for its own key")
    }

    @Test
    fun a_value_that_cannot_be_opened_is_discarded() {
        store.setString("device_token", "tok-123")
        cipher.canOpen = false

        assertNull(store.getString("device_token"))
        assertNull(disk.getString("device_token"), "an unreadable secret must not linger")
    }

    @Test
    fun an_empty_value_means_cleared_and_is_not_sealed() {
        // LocalDatabaseManager.clearDeviceToken() writes "" rather than removing the row.
        store.setString("device_token", "")

        assertFalse(disk.getAll().getValue("device_token").startsWith(SealedSecret.PREFIX))
    }

    @Test
    fun getAll_opens_secrets() {
        store.setString("jwt_refresh_token", "refresh-1")
        store.setString("api_base_url", "https://x/")

        assertEquals(mapOf("jwt_refresh_token" to "refresh-1", "api_base_url" to "https://x/"), store.getAll())
    }

    @Test
    fun getAll_lists_a_temporarily_unopenable_secret_as_empty_and_does_not_throw() {
        store.setString("device_token", "tok-123")
        store.setString("api_base_url", "https://x/")
        val before = disk.getAll()
        cipher.openError = IllegalStateException("key store unavailable")

        assertEquals(mapOf("device_token" to "", "api_base_url" to "https://x/"), store.getAll())
        assertEquals(before, disk.getAll())
    }

    @Test
    fun every_secret_the_app_actually_writes_lands_sealed() {
        // Guards key-name drift: these are the real writers, not the constants in SecretPreferenceKeys.
        TokenStore(store).storeTokens(accessToken = "access-1", refreshToken = "refresh-1", expiresIn = 900)
        LocalDatabaseManager(store).apply {
            setDeviceToken("device-1")
            setLoginUserData(username = "anna", password = "pw-1", rememberMe = true)
        }

        val written = disk.getAll()
        val secrets = written.filterValues { SealedSecret.isSealed(it) }.keys
        assertEquals(setOf("jwt_access_token", "jwt_refresh_token", "device_token", "password"), secrets)
        assertEquals("anna", written["userData"], "the username is not a secret")
    }

    @Test
    fun an_empty_string_from_sqlite_is_returned_as_is_and_never_sealed() {
        disk.setString("device_token", "")

        assertEquals("", store.getString("device_token"))
        assertEquals("", disk.getString("device_token"), "reading a cleared secret must not write anything")
    }

    @Test
    fun getAll_never_writes_even_with_legacy_and_unopenable_values() {
        disk.setString("password", "hunter2")
        store.setString("device_token", "tok-123")
        cipher.canOpen = false
        val before = disk.getAll()

        store.getAll()

        assertEquals(before, disk.getAll())
    }

    @Test
    fun a_failing_seal_writes_nothing() {
        cipher.canSeal = false

        assertFailsWith<IllegalStateException> { store.setString("password", "pw") }
        assertNull(disk.getString("password"), "a secret that could not be sealed must never reach the disk")
    }

    @Test
    fun a_failing_seal_during_legacy_read_still_returns_the_plaintext() {
        disk.setString("password", "pw")
        cipher.canSeal = false

        assertEquals("pw", store.getString("password"))
        assertEquals("pw", disk.getString("password"), "the legacy value stays until a later read can seal it")
    }

    @Test
    fun a_non_secret_value_that_looks_sealed_is_returned_as_is() {
        disk.setString("api_base_url", "enc:v1:abc")

        assertEquals("enc:v1:abc", store.getString("api_base_url"))
    }

    @Test
    fun clearing_the_device_token_over_a_sealed_value() {
        LocalDatabaseManager(store).apply {
            setDeviceToken("device-1")
            clearDeviceToken()
        }

        assertEquals("", disk.getString("device_token"))
        assertNull(LocalDatabaseManager(store).getDeviceToken())
    }

    @Test
    fun a_temporarily_unavailable_cipher_keeps_the_value() {
        store.setString("device_token", "tok-123")
        val sealed = disk.getString("device_token")
        cipher.openError = IllegalStateException("key store unavailable")

        assertNull(store.getString("device_token"))
        assertEquals(sealed, disk.getString("device_token"), "a transient key-store failure must not destroy the secret")

        cipher.openError = null
        assertEquals("tok-123", store.getString("device_token"))
    }

    @Test
    fun a_native_error_while_opening_keeps_the_value() {
        store.setString("device_token", "tok-123")
        val sealed = disk.getString("device_token")
        // An Error, not an Exception — stands in for JNA's UnsatisfiedLinkError, which common code cannot name.
        cipher.openError = NotImplementedError("no keyring")

        assertNull(store.getString("device_token"))
        assertEquals(sealed, disk.getString("device_token"), "a native keychain failure must not destroy the secret")
        assertEquals("", store.getAll()["device_token"])
    }

    @Test
    fun upgrading_with_a_plaintext_device_token_keeps_the_till_paired() {
        disk.setString("device_token", "device-1")

        // The manager no longer reads the token when built; the first read is what re-seals it.
        assertEquals("device-1", LocalDatabaseManager(store).getDeviceToken())
        assertTrue(SealedSecret.isSealed(disk.getString("device_token")!!))
        assertEquals("device-1", LocalDatabaseManager(store).getDeviceToken(), "the next boot must open the re-sealed token")
    }

    @Test
    fun a_sealed_value_moved_to_another_key_does_not_open() {
        store.setString("device_token", "tok-123")
        disk.setString("password", disk.getString("device_token")!!)

        assertNull(store.getString("password"))
        assertNull(disk.getString("password"), "a value bound to another key can never open and is discarded")
    }

    @Test
    fun sealing_legacy_secrets_seals_each_plaintext_secret_and_counts_it() {
        disk.setString("device_token", "device-1")
        disk.setString("password", "pw-1")
        store.setString("jwt_access_token", "access-1")
        val sealedAccessToken = disk.getString("jwt_access_token")

        assertEquals(2, store.sealLegacySecrets())

        assertTrue(SealedSecret.isSealed(disk.getString("device_token")!!))
        assertTrue(SealedSecret.isSealed(disk.getString("password")!!))
        assertEquals(sealedAccessToken, disk.getString("jwt_access_token"), "an already sealed secret is not sealed again")
        assertEquals("device-1", store.getString("device_token"))
        assertEquals("pw-1", store.getString("password"))
    }

    @Test
    fun sealing_legacy_secrets_skips_cleared_newer_format_and_non_secret_values() {
        disk.setString("device_token", "")
        disk.setString("password", "enc:v2:abc")
        disk.setString("api_base_url", "https://kasse.example.de/")
        disk.setString("userData", "anna")
        val before = disk.getAll()

        assertEquals(0, store.sealLegacySecrets())

        assertEquals(before, disk.getAll())
    }

    @Test
    fun sealing_legacy_secrets_with_a_failing_cipher_counts_nothing_and_keeps_the_plaintext() {
        disk.setString("password", "pw-1")
        cipher.canSeal = false

        assertEquals(0, store.sealLegacySecrets())

        assertEquals("pw-1", disk.getString("password"), "a secret that could not be sealed stays until a later run seals it")
    }

    @Test
    fun a_legacy_secret_is_found_without_being_sealed() {
        disk.setString("password", "pw-1")

        assertTrue(store.hasLegacySecrets())

        assertEquals("pw-1", disk.getString("password"), "looking for legacy plaintext must not seal it")
    }

    @Test
    fun sealed_cleared_newer_format_and_non_secret_values_are_not_legacy_secrets() {
        store.setString("device_token", "device-1")
        disk.setString("jwt_access_token", "")
        disk.setString("password", "enc:v2:abc")
        disk.setString("api_base_url", "https://kasse.example.de/")

        assertFalse(store.hasLegacySecrets())
    }

    @Test
    fun a_value_sealed_by_a_newer_format_is_neither_used_nor_destroyed() {
        disk.setString("password", "enc:v2:abc")

        assertNull(store.getString("password"))
        assertEquals("enc:v2:abc", disk.getString("password"), "a newer build's value must be neither discarded nor re-sealed")
        assertEquals("", store.getAll()["password"])
    }
}
