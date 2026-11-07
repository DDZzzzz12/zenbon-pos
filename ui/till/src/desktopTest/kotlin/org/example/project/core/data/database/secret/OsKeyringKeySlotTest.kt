package org.example.project.core.data.database.secret

import co.touchlab.kermit.Severity
import com.github.javakeyring.KeyringStorageType
import com.github.javakeyring.PasswordAccessException
import org.example.project.testutils.fakes.CapturingLogWriter
import org.example.project.testutils.fakes.FakeKeyringEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** No test here opens a real keychain: every slot runs over [FakeKeyringEntries]. Messages are java-keyring 1.0.4's. */
class OsKeyringKeySlotTest {
    @Test
    fun the_verified_not_found_on_macos_and_windows_reads_as_absent_without_a_probe() {
        val verified =
            listOf(
                KeyringStorageType.OSX_KEYCHAIN to "No stored credentials match POS account: $ACCOUNT",
                KeyringStorageType.WINDOWS_CREDENTIAL_STORE to "Error code 1168",
            )
        for ((storageType, message) in verified) {
            val entries = FakeKeyringEntries(storageType, missingMessage = message)

            assertNull(slot(entries).read(), "$storageType")
            assertEquals(0, entries.sets, "$storageType")
        }
    }

    @Test
    fun an_ambiguous_not_found_confirmed_by_the_probe_reads_as_absent_and_removes_the_probe() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)

        assertNull(slot(entries).read())

        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
        assertEquals(1, entries.deletes)
        assertTrue(entries.stored.isEmpty())
    }

    @Test
    fun an_ambiguous_failure_that_clears_before_the_reread_returns_the_stored_value() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)
        entries.stored[SERVICE to ACCOUNT] = "stored-key"
        var realReads = 0
        entries.getError = { _, account ->
            if (account == ACCOUNT && ++realReads == 1) PasswordAccessException(entries.missingMessage) else null
        }

        assertEquals("stored-key", slot(entries).read())
        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
    }

    @Test
    fun too_many_matching_entries_throws_on_the_first_read_on_every_backend_without_a_probe() {
        for (storageType in KeyringStorageType.entries) {
            val entries = FakeKeyringEntries(storageType)
            entries.getError = { _, account -> if (account == ACCOUNT) PasswordAccessException("$TOO_MANY POS account: $ACCOUNT") else null }

            assertFailsWith<IllegalStateException>("$storageType") { slot(entries).read() }

            assertEquals(0, entries.sets, "$storageType")
            assertEquals(listOf(ACCOUNT), entries.readAccounts, "$storageType")
        }
    }

    @Test
    fun too_many_matching_entries_on_the_reread_after_the_probe_throws() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)
        var realReads = 0
        entries.getError = { _, account ->
            if (account == ACCOUNT && ++realReads == 2) PasswordAccessException("$TOO_MANY POS account: $ACCOUNT") else null
        }

        assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
        assertEquals(2, realReads)
    }

    @Test
    fun a_macos_keychain_failure_other_than_not_found_throws_without_a_probe() {
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN, missingMessage = "Failed to get credential. User interaction is not allowed.")

        val thrown = assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertIs<PasswordAccessException>(thrown.cause)
        assertEquals(0, entries.sets)
        assertEquals(listOf(ACCOUNT), entries.readAccounts)
    }

    @Test
    fun an_unverified_windows_or_legacy_macos_failure_is_absent_when_it_matches_a_never_written_account() {
        val unverified =
            listOf(
                KeyringStorageType.WINDOWS_CREDENTIAL_STORE to "Error code 0",
                KeyringStorageType.LEGACY_OSX_KEYCHAIN to "The specified item could not be found in the keychain.",
            )
        for ((storageType, message) in unverified) {
            val entries = FakeKeyringEntries(storageType, missingMessage = message)

            assertNull(slot(entries).read(), "$storageType")

            assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts, "$storageType")
            val absentReads = entries.readAccounts.filter { it.startsWith(ABSENT_PREFIX) }
            assertEquals(1, absentReads.size, "$storageType")
            assertEquals(listOf(ACCOUNT, PROBE_ACCOUNT, absentReads.single(), ACCOUNT), entries.readAccounts, "$storageType")
        }
    }

    @Test
    fun a_windows_reread_failing_differently_from_the_never_written_account_throws() {
        val entries = FakeKeyringEntries(KeyringStorageType.WINDOWS_CREDENTIAL_STORE)
        entries.missingMessageFor = { account -> if (account == ACCOUNT) "Error code 1726" else "Error code 1168" }

        val thrown = assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertIs<PasswordAccessException>(thrown.cause)
        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
    }

    @Test
    fun a_windows_failure_that_clears_before_the_reread_returns_the_stored_value() {
        val entries = FakeKeyringEntries(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, missingMessage = "Error code 0")
        entries.stored[SERVICE to ACCOUNT] = "stored-key"
        var realReads = 0
        entries.getError = { _, account ->
            if (account == ACCOUNT && ++realReads == 1) PasswordAccessException("Error code 1726") else null
        }

        assertEquals("stored-key", slot(entries).read())
        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
    }

    @Test
    fun a_never_written_account_that_returns_a_value_throws() {
        val entries = FakeKeyringEntries(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, missingMessage = "Error code 0")
        entries.readOverride = { account -> if (account.startsWith(ABSENT_PREFIX)) "phantom" else null }

        assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
    }

    @Test
    fun a_never_written_account_failing_with_another_exception_throws() {
        val entries = FakeKeyringEntries(KeyringStorageType.LEGACY_OSX_KEYCHAIN, missingMessage = "not found")
        val boom = RuntimeException("JNA call failed")
        entries.getError = { _, account -> if (account.startsWith(ABSENT_PREFIX)) boom else null }

        val thrown = assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertSame(boom, thrown.cause)
    }

    @Test
    fun a_kwallet_reread_failure_after_a_working_probe_reads_as_absent() {
        val entries = FakeKeyringEntries(KeyringStorageType.KWALLET, missingMessage = "Password is not in wallet")

        assertNull(slot(entries).read())

        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
        assertTrue(entries.readAccounts.none { it.startsWith(ABSENT_PREFIX) })
    }

    @Test
    fun a_failed_probe_write_throws_and_never_writes_the_real_account() {
        val entries = FakeKeyringEntries(KeyringStorageType.KWALLET, missingMessage = "Password is not in wallet").apply { failWrites = true }

        val thrown = assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertIs<PasswordAccessException>(thrown.cause)
        assertEquals(1, thrown.suppressed.size)
        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
    }

    @Test
    fun a_failed_probe_read_throws() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)
        entries.getError = { _, account -> if (account == PROBE_ACCOUNT) PasswordAccessException("NoReply") else null }

        val thrown = assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertEquals(1, thrown.suppressed.size)
        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
    }

    @Test
    fun a_probe_that_reads_back_a_different_value_throws() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)
        entries.readOverride = { account -> if (account == PROBE_ACCOUNT) "not-the-nonce" else null }

        val thrown = assertFailsWith<IllegalStateException> { slot(entries).read() }

        assertEquals(1, thrown.suppressed.size)
        assertEquals(listOf(PROBE_ACCOUNT), entries.writtenAccounts)
    }

    @Test
    fun a_probe_that_cannot_be_removed_does_not_fail_the_read() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING).apply { failDeletes = true }

        assertNull(slot(entries).read())
        assertEquals(1, entries.deletes)
    }

    @Test
    fun a_blank_value_reads_as_absent() {
        val entries = FakeKeyringEntries(KeyringStorageType.OSX_KEYCHAIN)
        entries.stored[SERVICE to ACCOUNT] = "   "

        assertNull(slot(entries).read())
        assertEquals(0, entries.sets)
    }

    @Test
    fun a_stored_value_is_returned_as_is() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)
        entries.stored[SERVICE to ACCOUNT] = "stored-key"

        assertEquals("stored-key", slot(entries).read())
        assertEquals(0, entries.sets)
    }

    @Test
    fun an_exception_other_than_a_password_access_exception_propagates() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)
        val dbusDown = IllegalStateException("D-Bus connection closed")
        entries.getError = { _, _ -> dbusDown }

        assertSame(dbusDown, assertFailsWith<IllegalStateException> { slot(entries).read() })
        assertEquals(0, entries.sets)
    }

    @Test
    fun write_stores_the_value_under_the_account() {
        val entries = FakeKeyringEntries(KeyringStorageType.GNOME_KEYRING)

        slot(entries).write("new-key")

        assertEquals("new-key", entries.stored[SERVICE to ACCOUNT])
    }

    @Test
    fun the_macos_keychain_not_found_message_is_a_missing_entry() {
        val notFound = PasswordAccessException("No stored credentials match POS account: local-secrets-v1")

        assertTrue(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.OSX_KEYCHAIN, notFound))
    }

    @Test
    fun the_windows_not_found_message_is_a_missing_entry() {
        assertTrue(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, PasswordAccessException("Error code 1168")))
    }

    @Test
    fun another_message_is_not_a_missing_entry() {
        val macLocked = PasswordAccessException("Failed to get credential. User interaction is not allowed.")
        val windowsNoSession = PasswordAccessException("Error code 1312")
        val windowsLookalike = PasswordAccessException("Error code 11680")

        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.OSX_KEYCHAIN, macLocked))
        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, windowsNoSession))
        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, windowsLookalike))
        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, PasswordAccessException("No stored credentials match")))
        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.WINDOWS_CREDENTIAL_STORE, PasswordAccessException("Password not Found")))
    }

    @Test
    fun backends_that_cannot_tell_absence_from_failure_never_report_a_missing_entry() {
        val secretServiceNotFound = PasswordAccessException("No stored credentials match POS account: local-secrets-v1")
        val kwalletNotFound = PasswordAccessException("Password is not in wallet")
        val legacyMacNotFound = PasswordAccessException("The specified item could not be found in the keychain.")

        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.GNOME_KEYRING, secretServiceNotFound))
        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.KWALLET, kwalletNotFound))
        assertFalse(OsKeyringKeySlot.isMissingEntry(KeyringStorageType.LEGACY_OSX_KEYCHAIN, legacyMacNotFound))
    }

    @Test
    fun a_null_message_is_not_a_missing_entry() {
        for (storageType in KeyringStorageType.entries) {
            assertFalse(OsKeyringKeySlot.isMissingEntry(storageType, PasswordAccessException(null)))
        }
    }

    @Test
    fun a_failing_probe_is_warned_once_until_it_passes_again() {
        val log = CapturingLogWriter()
        val problems = KeySlotProblemReporter(log.logger)
        val entries = FakeKeyringEntries(KeyringStorageType.KWALLET, missingMessage = "Password is not in wallet").apply { failWrites = true }

        repeat(2) { assertFailsWith<IllegalStateException> { slot(entries, problems).read() } }
        entries.failWrites = false
        assertNull(slot(entries, problems).read())
        entries.failWrites = true
        assertFailsWith<IllegalStateException> { slot(entries, problems).read() }

        assertEquals(2, log.messages(Severity.Warn).count { "probe" in it })
        assertEquals(1, log.messages(Severity.Info).count { "probe" in it })
    }

    private fun slot(
        entries: FakeKeyringEntries,
        problems: KeySlotProblemReporter = KeySlotProblemReporter(),
    ) = OsKeyringKeySlot(entries, problems, service = SERVICE, account = ACCOUNT)

    private companion object {
        const val SERVICE = "POS"
        const val ACCOUNT = "local-secrets-v1"
        const val PROBE_ACCOUNT = "local-secrets-v1-probe"
        const val ABSENT_PREFIX = "local-secrets-v1-absent-"
        const val TOO_MANY = "Too many stored credentials match"
    }
}
