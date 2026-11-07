package org.example.project.core.data.database.secret

import co.touchlab.kermit.Severity
import com.github.javakeyring.BackendNotSupportedException
import org.example.project.testutils.fakes.CapturingLogWriter
import org.example.project.testutils.fakes.FakeKeySlot
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopMasterKeyTest {
    private val keychainKey = ByteArray(DesktopMasterKey.KEY_BYTES) { it.toByte() }
    private val fileKey = ByteArray(DesktopMasterKey.KEY_BYTES) { (it + 100).toByte() }
    private val problems = KeySlotProblemReporter()

    @Test
    fun load_returns_the_keychain_key_first_then_the_file_key() {
        val keychain = FakeKeySlot(encode(keychainKey))
        val file = FakeKeySlot(encode(fileKey))

        val loaded = DesktopMasterKey.load(keychain, file, problems)

        assertEquals(2, loaded.keys.size)
        assertContentEquals(keychainKey, loaded.keys[0])
        assertContentEquals(fileKey, loaded.keys[1])
        assertTrue(loaded.primaryUsable)
        assertTrue(loaded.complete)
    }

    @Test
    fun load_returns_a_key_held_only_in_the_file() {
        val loaded = DesktopMasterKey.load(FakeKeySlot(), FakeKeySlot(encode(fileKey)), problems)

        assertEquals(1, loaded.keys.size)
        assertContentEquals(fileKey, loaded.keys.single())
        assertTrue(loaded.primaryUsable)
        assertTrue(loaded.complete)
    }

    @Test
    fun load_with_no_key_anywhere_is_empty_and_complete() {
        val loaded = DesktopMasterKey.load(FakeKeySlot(), FakeKeySlot(), problems)

        assertTrue(loaded.keys.isEmpty())
        assertTrue(loaded.complete)
        assertTrue(loaded.primaryUsable)
    }

    @Test
    fun a_garbled_or_wrong_size_stored_key_is_ignored_and_the_load_stays_complete() {
        val garbled = FakeKeySlot("not base64 !!")
        val tooShort = FakeKeySlot(encode(ByteArray(16)))

        val fromGarbledKeychain = DesktopMasterKey.load(garbled, tooShort, problems)

        assertTrue(fromGarbledKeychain.keys.isEmpty())
        assertTrue(fromGarbledKeychain.complete)
        assertTrue(fromGarbledKeychain.primaryUsable)
        val garbledNextToGood = DesktopMasterKey.load(FakeKeySlot("not base64 !!"), FakeKeySlot(encode(fileKey)), problems)
        assertContentEquals(fileKey, garbledNextToGood.keys.single())
        assertTrue(garbledNextToGood.complete)
    }

    @Test
    fun a_malformed_stored_key_is_warned_about_once_and_noted_once_it_is_fixed() {
        val log = CapturingLogWriter()
        val reporter = KeySlotProblemReporter(log.logger)
        val file = FakeKeySlot("not base64 !!")

        repeat(3) { DesktopMasterKey.load(FakeKeySlot(), file, reporter) }
        val warnings = log.messages(Severity.Warn)
        file.value = encode(fileKey)
        repeat(2) { DesktopMasterKey.load(FakeKeySlot(), file, reporter) }

        assertEquals(1, warnings.count { "malformed" in it }, "$warnings")
        assertEquals(warnings, log.messages(Severity.Warn))
        assertEquals(1, log.messages(Severity.Info).size, "${log.messages(Severity.Info)}")
    }

    @Test
    fun a_keychain_that_cannot_be_read_still_loads_the_file_key_but_is_incomplete() {
        val keychain = FakeKeySlot(encode(keychainKey)).apply { readError = IllegalStateException("locked") }
        val file = FakeKeySlot(encode(fileKey))

        val loaded = DesktopMasterKey.load(keychain, file, problems)

        assertContentEquals(fileKey, loaded.keys.single())
        assertFalse(loaded.complete)
        assertFalse(loaded.primaryUsable)
        assertTrue(loaded.fallbackReadable)
    }

    @Test
    fun no_keychain_slot_at_all_is_deterministic_so_the_load_is_complete() {
        val loaded = DesktopMasterKey.load(null, FakeKeySlot(encode(fileKey)), problems)

        assertContentEquals(fileKey, loaded.keys.single())
        assertTrue(loaded.complete)
        assertFalse(loaded.primaryUsable)
    }

    @Test
    fun an_unavailable_keychain_backend_makes_the_load_incomplete_and_a_new_key_goes_to_the_file() {
        val backendDown = BackendNotSupportedException("Error connecting to dbus")
        val keychain = UnavailableKeySlot("OS keychain (unavailable)", backendDown)
        val file = FakeKeySlot()

        val loaded = DesktopMasterKey.loadOrCreate(keychain, file, problems)

        assertContentEquals(Base64.getDecoder().decode(file.value), loaded.keys.single())
        assertFalse(loaded.complete)
        assertFalse(loaded.primaryUsable)
        assertEquals(1, file.writes)
        assertSame(backendDown, assertFailsWith<IllegalStateException> { keychain.write("key") }.cause)
    }

    @Test
    fun the_result_names_the_slot_the_sealing_key_came_from() {
        val bothKeys = DesktopMasterKey.load(FakeKeySlot(encode(keychainKey), KEYCHAIN), FakeKeySlot(encode(fileKey), FILE), problems)
        val fileKeyOnly = DesktopMasterKey.load(FakeKeySlot(description = KEYCHAIN), FakeKeySlot(encode(fileKey), FILE), problems)
        val noKey = DesktopMasterKey.load(FakeKeySlot(description = KEYCHAIN), FakeKeySlot(description = FILE), problems)
        val createdInKeychain = DesktopMasterKey.loadOrCreate(FakeKeySlot(description = KEYCHAIN), FakeKeySlot(description = FILE), problems)
        val lockedKeychain = FakeKeySlot(description = KEYCHAIN).apply { readError = IllegalStateException("locked") }
        val createdInFile = DesktopMasterKey.loadOrCreate(lockedKeychain, FakeKeySlot(description = FILE), problems)

        assertEquals(KEYCHAIN, bothKeys.sealingKeySlot)
        assertEquals(FILE, fileKeyOnly.sealingKeySlot)
        assertNull(noKey.sealingKeySlot)
        assertEquals(KEYCHAIN, createdInKeychain.sealingKeySlot)
        assertEquals(FILE, createdInFile.sealingKeySlot)
    }

    @Test
    fun a_native_error_from_one_slot_makes_only_that_slot_unreadable() {
        val keychain = FakeKeySlot(encode(keychainKey)).apply { readError = UnsatisfiedLinkError("jnidispatch blocked") }
        val file = FakeKeySlot(encode(fileKey))

        val loaded = DesktopMasterKey.load(keychain, file, problems)

        assertContentEquals(fileKey, loaded.keys.single())
        assertFalse(loaded.complete)
        assertFalse(loaded.primaryUsable)
        assertTrue(loaded.fallbackReadable)
    }

    @Test
    fun a_virtual_machine_error_from_a_slot_propagates() {
        val keychain = FakeKeySlot().apply { readError = OutOfMemoryError("simulated") }

        assertFailsWith<OutOfMemoryError> { DesktopMasterKey.load(keychain, FakeKeySlot(), problems) }

        assertEquals(0, keychain.writes)
    }

    @Test
    fun a_file_that_cannot_be_read_makes_the_load_incomplete() {
        val file = FakeKeySlot().apply { readError = IOException("permission denied") }

        val loaded = DesktopMasterKey.load(FakeKeySlot(encode(keychainKey)), file, problems)

        assertContentEquals(keychainKey, loaded.keys.single())
        assertFalse(loaded.complete)
        assertTrue(loaded.primaryUsable)
        assertFalse(loaded.fallbackReadable)
    }

    @Test
    fun load_never_writes() {
        val scenarios =
            listOf(
                FakeKeySlot() to FakeKeySlot(),
                FakeKeySlot().apply { readError = IllegalStateException("locked") } to FakeKeySlot(),
                FakeKeySlot("not base64 !!") to FakeKeySlot().apply { readError = IOException("denied") },
            )
        for ((keychain, file) in scenarios) {
            DesktopMasterKey.load(keychain, file, problems)

            assertEquals(0, keychain.writes + file.writes)
        }
    }

    @Test
    fun load_or_create_with_a_usable_keychain_stores_the_key_in_the_keychain_only() {
        val keychain = FakeKeySlot()
        val file = FakeKeySlot()

        val created = DesktopMasterKey.loadOrCreate(keychain, file, problems).keys.single()

        assertEquals(DesktopMasterKey.KEY_BYTES, created.size)
        assertContentEquals(created, Base64.getDecoder().decode(keychain.value))
        assertEquals(1, keychain.writes)
        assertEquals(0, file.writes)
        assertNull(file.value)
    }

    @Test
    fun load_or_create_never_writes_a_keychain_whose_read_threw() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val file = FakeKeySlot()

        val created = DesktopMasterKey.loadOrCreate(keychain, file, problems).keys.single()

        assertContentEquals(created, Base64.getDecoder().decode(file.value))
        assertEquals(0, keychain.writes)
        assertEquals(1, file.writes)
    }

    @Test
    fun load_or_create_falls_back_to_the_file_when_the_keychain_refuses_the_write() {
        val keychain = FakeKeySlot().apply { failWrites = true }
        val file = FakeKeySlot()

        val created = DesktopMasterKey.loadOrCreate(keychain, file, problems).keys.single()

        assertContentEquals(created, Base64.getDecoder().decode(file.value))
        assertNull(keychain.value)
    }

    @Test
    fun load_or_create_with_no_keychain_backend_uses_the_file() {
        val file = FakeKeySlot()

        val created = DesktopMasterKey.loadOrCreate(null, file, problems).keys.single()

        assertContentEquals(created, Base64.getDecoder().decode(file.value))
        assertEquals(1, file.writes)
    }

    @Test
    fun load_or_create_throws_when_the_file_could_not_be_read() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val file = FakeKeySlot().apply { readError = IOException("denied") }

        assertFailsWith<IllegalStateException> { DesktopMasterKey.loadOrCreate(keychain, file, problems) }

        assertEquals(0, keychain.writes + file.writes)
    }

    @Test
    fun load_or_create_with_a_usable_keychain_stores_the_key_there_even_when_the_file_could_not_be_read() {
        val keychain = FakeKeySlot()
        val file = FakeKeySlot().apply { readError = IOException("root-owned config folder") }

        val created = DesktopMasterKey.loadOrCreate(keychain, file, problems).keys.single()

        assertContentEquals(created, Base64.getDecoder().decode(keychain.value))
        assertEquals(1, keychain.writes)
        assertEquals(0, file.writeAttempts)
    }

    @Test
    fun load_or_create_throws_when_the_keychain_refuses_the_write_and_the_file_could_not_be_read() {
        val keychain = FakeKeySlot().apply { failWrites = true }
        val file = FakeKeySlot().apply { readError = IOException("denied") }

        assertFailsWith<IllegalStateException> { DesktopMasterKey.loadOrCreate(keychain, file, problems) }

        assertEquals(1, keychain.writeAttempts)
        assertEquals(0, file.writeAttempts)
    }

    @Test
    fun load_or_create_falls_back_to_the_file_when_the_keychain_write_throws_a_native_error() {
        val keychain = FakeKeySlot().apply { writeError = UnsatisfiedLinkError("jnidispatch blocked") }
        val file = FakeKeySlot()

        val created = DesktopMasterKey.loadOrCreate(keychain, file, problems).keys.single()

        assertContentEquals(created, Base64.getDecoder().decode(file.value))
        assertNull(keychain.value)
    }

    @Test
    fun load_or_create_throws_when_neither_slot_accepts_the_key() {
        val keychain = FakeKeySlot().apply { failWrites = true }
        val file = FakeKeySlot().apply { failWrites = true }

        assertFailsWith<IllegalStateException> { DesktopMasterKey.loadOrCreate(keychain, file, problems) }

        assertNull(keychain.value)
        assertNull(file.value)
    }

    @Test
    fun load_or_create_returns_an_existing_key_without_writing() {
        val keychain = FakeKeySlot(encode(keychainKey))
        val file = FakeKeySlot()

        val loaded = DesktopMasterKey.loadOrCreate(keychain, file, problems)

        assertContentEquals(keychainKey, loaded.keys.single())
        assertEquals(0, keychain.writes + file.writes)
    }

    @Test
    fun load_or_create_carries_the_load_flags_with_the_new_key() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val file = FakeKeySlot()

        val loaded = DesktopMasterKey.loadOrCreate(keychain, file, problems)

        assertContentEquals(Base64.getDecoder().decode(file.value), loaded.keys.single())
        assertFalse(loaded.complete)
        assertFalse(loaded.primaryUsable)
    }

    @Test
    fun load_or_create_uses_the_key_another_caller_stored_in_the_file_first() {
        val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
        val file = FakeKeySlot()
        file.beforeWriteIfAbsent = { file.value = encode(fileKey) }

        val loaded = DesktopMasterKey.loadOrCreate(keychain, file, problems)

        assertContentEquals(fileKey, loaded.keys.single())
        assertEquals(file.description, loaded.sealingKeySlot)
        assertEquals(encode(fileKey), file.value)
        assertEquals(0, file.writes)
    }

    @Test
    fun load_or_create_never_replaces_a_malformed_key_the_file_already_holds() {
        for (malformed in listOf("not base64 !!", encode(ByteArray(16)))) {
            val keychain = FakeKeySlot().apply { readError = IllegalStateException("locked") }
            val file = FakeKeySlot(malformed)

            assertFailsWith<IllegalStateException> { DesktopMasterKey.loadOrCreate(keychain, file, problems) }

            assertEquals(malformed, file.value)
            assertEquals(0, keychain.writeAttempts)
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val KEYCHAIN = "keychain slot"
        const val FILE = "file slot"
    }
}
